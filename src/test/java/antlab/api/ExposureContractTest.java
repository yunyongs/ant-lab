package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.exposure.FinishedSeasonOnly;
import antlab.member.CurrentMember;
import antlab.member.MemberRepository;
import antlab.season.SeasonRepository;
import antlab.account.CashAccountRepository;
import antlab.support.ExposureCatalog;
import antlab.support.SeasonAccountFixture;
import antlab.support.ExposureRules;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노출 상한 계약. /api/v1/** 의 모든 응답 record 가 {@code [노출]} 열(또는 식별자 허용 목록)에서만 오고,
 * 실제 응답에 금지 값이 없는지 확인한다. 새 응답 DTO 는 별도 작업 없이 이 시험에 들어온다.
 * 시즌이 있어야 하는 경로(턴 조회·보유 조회)의 실제 응답 본문·로그 검사는 {@code antlab.api.turn.TurnViewExposureTest} 가 한다.
 */
@IntegrationTest
@Transactional
@Sql("/fixtures/history_pool.sql")
class ExposureContractTest {

    /** 응답 본문에 나오면 안 되는 키: 날짜·창·환율·순위·통화·그룹 코드·가격 원값. */
    static final Set<String> FORBIDDEN_KEYS = Set.of("currency", "code", "listedOn", "baseDate", "windowStart",
            "windowEnd", "monthsUsed", "marketCapRank", "baseFxRate", "basePrice", "createdAt");

    static final String DEV_PATH_PREFIX = "/api/v1/dev/";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    CurrentMember currentMember;

    @Autowired
    MemberRepository members;

    @Autowired
    SeasonRepository seasons;

    @Autowired
    CashAccountRepository accounts;

    record Endpoint(String path, Class<?> responseType, boolean finishedSeasonOnly) {
    }

    private List<Endpoint> apiEndpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = e.getKey().getPathPatternsCondition().getPatternValues();
            ResolvableType type = ResolvableType.forMethodReturnType(e.getValue().getMethod());
            if (ResponseEntity.class.isAssignableFrom(type.toClass())) {
                type = type.getGeneric(0);
            }
            for (String p : patterns) {
                // 개발 엔드포인트(local 전용, prod 미등록)는 값을 보여 주는 도구라 노출 검사 예외다(소유자 결정 2026-11-11).
                if (p.startsWith("/api/v1/") && !p.startsWith(DEV_PATH_PREFIX)) {
                    out.add(new Endpoint(p, type.toClass(), e.getValue().hasMethodAnnotation(FinishedSeasonOnly.class)));
                }
            }
        }
        return out;
    }

    @Test
    void 카탈로그는_모든_표의_모든_열에_태그가_있다() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        List<String> untagged = catalog.tags().entrySet().stream()
                .filter(e -> e.getValue() == null).map(Map.Entry::getKey).toList();
        assertThat(untagged).as("태그 없는 열").isEmpty();
        long tables = catalog.tags().keySet().stream().map(k -> k.substring(0, k.indexOf('.'))).distinct().count();
        assertThat(tables).as("표 수").isEqualTo(22);
    }

    @Test
    void 응답_record의_모든_구성_요소가_노출_상한을_지킨다() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        List<Endpoint> endpoints = apiEndpoints();
        assertThat(endpoints).as("검사한 /api/v1 운영").isNotEmpty();
        for (Endpoint ep : endpoints) {
            assertThat(ep.responseType().isRecord()).as(ep.path() + " 응답은 record").isTrue();
            assertThat(ExposureRules.check(ep.responseType(), catalog, ep.finishedSeasonOnly())).as(ep.path()).isEmpty();
        }
    }

    @Test
    void 실제_응답의_키는_record_구성_요소와_같고_금지_값이_없다() throws Exception {
        for (Endpoint ep : apiEndpoints()) {
            if (!ep.path().equals("/api/v1/pool")) {
                continue; // 경로 변수가 없는 조회만 고정본으로 호출한다. 이후 기능이 호출 사례를 더한다.
            }
            String body = mvc.get().uri(ep.path()).exchange().getResponse().getContentAsString();
            JsonNode json = JsonMapper.shared().readTree(body);
            List<String> problems = new ArrayList<>();
            keysMatch(ep.responseType(), json, "$", problems);
            assertThat(problems).as(ep.path() + " 키 대조").isEmpty();
            assertThat(allKeys(json)).as("금지 키").doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
            // 고정본의 날짜·환율 값이 본문에 문자열로 나오지 않는다.
            assertThat(body).doesNotContain("1999-01-04", "2002-10-14", "2003-05-01", "1996-05-09", "2026-01-02",
                    "2020-01-01", "2025-12-01", "1350");
            assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    @Test
    void 끝난_시즌_전용_핸들러는_진행_중_시즌에_409로_답한다() throws Exception {
        List<Endpoint> restricted = apiEndpoints().stream().filter(Endpoint::finishedSeasonOnly).toList();
        assertThat(restricted).as("@FinishedSeasonOnly 핸들러").isNotEmpty();
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long seasonId = fixture.newSeason(members.findById(currentMember.ensureId()).orElseThrow()).getId();
        for (Endpoint ep : restricted) {
            var response = mvc.get().uri(ep.path().replace("{seasonId}", Long.toString(seasonId))).exchange().getResponse();
            assertThat(response.getStatus()).as(ep.path()).isEqualTo(409);
            assertThat(JsonMapper.shared().readTree(response.getContentAsString()).get("code").asString())
                    .as(ep.path()).isEqualTo("SEASON_NOT_FINISHED");
        }
    }

    @Test
    void 확장_허용_원천은_표지_없는_핸들러의_응답에서는_쓰이지_않는다() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        for (Endpoint ep : apiEndpoints()) {
            if (!ep.finishedSeasonOnly()) {
                assertThat(ExposureRules.check(ep.responseType(), catalog, false)).as(ep.path()).isEmpty();
            }
        }
    }

    private static void keysMatch(Class<?> record, JsonNode node, String path, List<String> out) {
        if (node.isArray()) {
            node.forEach(item -> keysMatch(record, item, path + "[]", out));
            return;
        }
        Set<String> expected = new HashSet<>();
        for (RecordComponent rc : record.getRecordComponents()) {
            expected.add(rc.getName());
        }
        Set<String> actual = new HashSet<>(node.propertyNames());
        if (!expected.equals(actual)) {
            out.add(path + " — record " + expected + " / 응답 " + actual);
        }
        for (RecordComponent rc : record.getRecordComponents()) {
            Class<?> nested = nestedRecordOf(rc);
            JsonNode child = node.get(rc.getName());
            if (nested != null && child != null && !child.isNull()) {
                keysMatch(nested, child, path + "." + rc.getName(), out);
            }
        }
    }

    private static Class<?> nestedRecordOf(RecordComponent rc) {
        ResolvableType t = ResolvableType.forType(rc.getGenericType());
        Class<?> c = t.isArray() || t.hasGenerics() ? t.getGeneric(0).toClass() : t.toClass();
        return c.isRecord() && c.getName().startsWith("antlab.") ? c : null;
    }

    private static Set<String> allKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        if (node.isObject()) {
            node.properties().forEach(e -> {
                keys.add(e.getKey());
                keys.addAll(allKeys(e.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(n -> keys.addAll(allKeys(n)));
        }
        return keys;
    }
}

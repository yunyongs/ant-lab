package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.get;
import static antlab.api.season.SeasonApiSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.SeasonDto;
import antlab.season.SeasonListDto;
import antlab.season.TurnPricesDto;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시즌 API 의 실제 응답 노출 상한: 키 = record 구성 요소, 시드·엔진 판·기준일·월 블록·날짜 꼴이 본문과 로그에 없다.
 * (record 정적 검사는 ExposureContractTest 가 새 응답 record 에도 자동으로 한다.)
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@ExtendWith(OutputCaptureExtension.class)
class SeasonExposureTest {

    static final Set<String> FORBIDDEN_KEYS = Set.of("seed", "engineVersion", "dataSnapshotDate", "baseDate",
            "monthBlockId", "monthStart", "memberId", "version", "createdAt", "updatedAt", "abandonedTurnNo",
            "marketCapRank", "baseFxRate", "basePrice", "currency", "code");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 응답_키는_record_구성_요소와_같고_금지_값이_본문과_로그에_없다(CapturedOutput output) throws Exception {
        MockHttpServletResponse created = post(mvc, VALID_BODY);
        JsonNode season = JsonMapper.shared().readTree(created.getContentAsString());
        long id = season.get("id").asLong();
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, id);
        String engine = jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, id);

        List<String> problems = new ArrayList<>();
        check("POST /seasons", created, SeasonDto.class, seed, engine, problems);
        check("GET /seasons/{id}", get(mvc, "/api/v1/seasons/" + id), SeasonDto.class, seed, engine, problems);
        check("GET /seasons", get(mvc, "/api/v1/seasons"), SeasonListDto.class, seed, engine, problems);
        check("GET prices", get(mvc, "/api/v1/seasons/" + id + "/prices"), TurnPricesDto.class, seed, engine,
                problems);
        assertThat(problems).isEmpty();

        // 로그에도 시드·엔진 판이 없다
        assertThat(output.getAll()).doesNotContain(seed).doesNotContain(engine);
        // 로그에 경로의 달 문자열도 없다
        List<String> months = jdbc.queryForList("SELECT mb.month_start::text FROM season_path sp"
                + " JOIN month_block mb ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no",
                String.class, id);
        assertThat(months).isNotEmpty();
        months.forEach(m -> assertThat(output.getAll()).as("로그의 달").doesNotContain(m));
    }

    private static void check(String where, MockHttpServletResponse r, Class<?> type, String seed, String engine,
            List<String> out) throws Exception {
        String body = r.getContentAsString();
        JsonNode json = JsonMapper.shared().readTree(body);
        keysMatch(type, json, where, out);
        Set<String> keys = allKeys(json);
        for (String forbidden : FORBIDDEN_KEYS) {
            if (keys.contains(forbidden)) {
                out.add(where + " — 금지 키 " + forbidden);
            }
        }
        if (body.contains(seed)) {
            out.add(where + " — 시드 값이 본문에 있다");
        }
        if (body.contains(engine)) {
            out.add(where + " — 엔진 판이 본문에 있다");
        }
        if (java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(body).find()) {
            out.add(where + " — 날짜 꼴 문자열이 본문에 있다");
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
            ResolvableType t = ResolvableType.forType(rc.getGenericType());
            Class<?> c = t.hasGenerics() ? t.getGeneric(0).toClass() : t.toClass();
            JsonNode child = node.get(rc.getName());
            if (c.isRecord() && c.getName().startsWith("antlab.") && child != null && !child.isNull()) {
                keysMatch(c, child, path + "." + rc.getName(), out);
            }
        }
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

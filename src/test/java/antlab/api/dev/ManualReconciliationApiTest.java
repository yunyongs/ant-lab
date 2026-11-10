package antlab.api.dev;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.RuleSummaryDto;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 수동 검사 개발 엔드포인트(local 프로필): 응답 모양, 기록 커밋, 불일치도 200, 404, 끝난 시즌, 잠금 SQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import(PostgresTestcontainersConfig.class)
@Sql(scripts = {"/fixtures/cleanup_reconciliation_log.sql", "/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
        "/fixtures/replay_months.sql"})
@Sql(scripts = {"/fixtures/cleanup_reconciliation_log.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ManualReconciliationApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    private long seasonWithOrders() throws Exception {
        return antlab.scoring.ReconciliationApiSupportAccess.seasonWithOrders(mvc, jdbc);
    }

    private MockHttpServletResponse reconcile(long seasonId) {
        return mvc.post().uri("/api/v1/dev/seasons/" + seasonId + "/reconciliation")
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange().getResponse();
    }

    private static Set<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
    }

    private static Set<String> keys(JsonNode node) {
        return node.propertyNames().stream().collect(Collectors.toSet());
    }

    @Test
    void 일치하면_200과_코드_상태_건수와_값을_담고_열다섯_행을_기록한다() throws Exception {
        long season = seasonWithOrders();
        MockHttpServletResponse r = reconcile(season);

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = JsonMapper.shared().readTree(r.getContentAsString());
        assertThat(keys(body)).isEqualTo(componentNames(ManualReconciliationDto.class));
        assertThat(body.get("seasonId").asLong()).isEqualTo(season);
        assertThat(body.get("turnNo").asInt()).isZero();
        assertThat(body.get("attemptNo").asInt()).isEqualTo(1);
        assertThat(body.get("result").asString()).isEqualTo("MATCH");
        assertThat(body.get("mismatchRules").asInt()).isZero();
        JsonNode rules = body.get("rules");
        assertThat(rules.size()).isEqualTo(15);
        rules.forEach(rule -> {
            assertThat(keys(rule)).isEqualTo(componentNames(RuleSummaryDto.class));
            assertThat(rule.get("status").asString()).isEqualTo("MATCH");
        });
        // 값 열이 응답에 있고 reconciliation_log 행과 같다(스칼라 규칙은 BigDecimal 그대로)
        JsonNode cash = rules.valueStream().filter(x -> x.get("checkCode").asString().equals("CASH_SUM")).findFirst().orElseThrow();
        assertThat(cash.get("expectedValue").isString()).isTrue();
        java.util.Map<String, Object> row = jdbc.queryForMap("SELECT expected_value, actual_value, diff_value FROM reconciliation_log WHERE season_id = ? AND attempt_no = 1 AND check_code = 'CASH_SUM'", season);
        assertThat(new java.math.BigDecimal(cash.get("expectedValue").asString())).isEqualByComparingTo((java.math.BigDecimal) row.get("expected_value"));
        assertThat(new java.math.BigDecimal(cash.get("actualValue").asString())).isEqualByComparingTo((java.math.BigDecimal) row.get("actual_value"));
        assertThat(new java.math.BigDecimal(cash.get("diffValue").asString())).isEqualByComparingTo((java.math.BigDecimal) row.get("diff_value"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = 1 AND turn_no = 0 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(15);
        // 호출마다 새 시도 번호
        JsonNode second = JsonMapper.shared().readTree(reconcile(season).getContentAsString());
        assertThat(second.get("attemptNo").asInt()).isEqualTo(2);
    }

    @Test
    void 고정_헤더가_없으면_403_FORBIDDEN이고_기록이_없다() throws Exception {
        long season = seasonWithOrders();
        MockHttpServletResponse none = mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation").exchange().getResponse();
        assertThat(none.getStatus()).isEqualTo(403);
        assertThat(JsonMapper.shared().readTree(none.getContentAsString()).get("code").asString()).isEqualTo("FORBIDDEN");
        MockHttpServletResponse wrong = mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation")
                .header(DevReconciliationController.DEV_HEADER, "false").exchange().getResponse();
        assertThat(wrong.getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isZero();
        assertThat(reconcile(season).getStatus()).isEqualTo(200);
    }

    @Test
    void 불일치여도_200이고_결과는_커밋되며_불일치_규칙만_MISMATCH다() throws Exception {
        long season = seasonWithOrders();
        jdbc.update("UPDATE cash_account SET reserved = 1 WHERE season_id = ?", season);

        MockHttpServletResponse r = reconcile(season);

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = JsonMapper.shared().readTree(r.getContentAsString());
        assertThat(body.get("result").asString()).isEqualTo("MISMATCH");
        assertThat(body.get("mismatchRules").asInt()).isEqualTo(1);
        List<String> mismatched = body.get("rules").valueStream().filter(x -> x.get("status").asString().equals("MISMATCH"))
                .map(x -> x.get("checkCode").asString()).toList();
        assertThat(mismatched).containsExactly("RESERVED_ZERO");
        JsonNode reserved = body.get("rules").valueStream().filter(x -> x.get("checkCode").asString().equals("RESERVED_ZERO")).findFirst().orElseThrow();
        assertThat(new java.math.BigDecimal(reserved.get("actualValue").asString())).isEqualByComparingTo("1");
        assertThat(new java.math.BigDecimal(reserved.get("diffValue").asString())).isEqualByComparingTo("1");
        assertThat(reserved.get("detail").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT classification FROM reconciliation_log WHERE season_id = ? AND check_code = 'RESERVED_ZERO'",
                String.class, season)).isEqualTo("INVESTIGATE");
    }

    @Test
    void 없는_시즌과_다른_회원의_시즌은_404_NOT_FOUND이고_기록이_없다() throws Exception {
        // 고정 회원이 아직 없을 수도 있으므로 먼저 시즌 하나를 만들어 고정 회원을 만든다
        seasonWithOrders();
        MockHttpServletResponse missing = reconcile(Long.MAX_VALUE);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(JsonMapper.shared().readTree(missing.getContentAsString()).get("code").asString()).isEqualTo("NOT_FOUND");

        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        try {
            Season other = fixture.newSeason(fixture.newMember());
            MockHttpServletResponse foreign = reconcile(other.getId());
            assertThat(foreign.getStatus()).isEqualTo(404);
            assertThat(JsonMapper.shared().readTree(foreign.getContentAsString()).get("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, other.getId())).isZero();
        } finally {
            fixture.cleanUp();
        }
    }

    @Test
    void 끝난_시즌도_검사하고_현재_턴으로_기록한다() throws Exception {
        long season = seasonWithOrders();
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        int pathLength = jdbc.queryForObject("SELECT path_length FROM season WHERE id = ?", Integer.class, season);

        MockHttpServletResponse r = reconcile(season);

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(JsonMapper.shared().readTree(r.getContentAsString()).get("turnNo").asInt()).isEqualTo(pathLength);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = ?", Long.class,
                season, pathLength)).isEqualTo(15);
    }

    @Test
    void 첫_시즌_조회가_잠금_조회이고_한_트랜잭션에서_열다섯_행을_쓴다() throws Exception {
        long season = seasonWithOrders();

        SqlRecorder.start();
        MockHttpServletResponse r = reconcile(season);
        List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();

        assertThat(r.getStatus()).isEqualTo(200);
        List<String> seasonReads = sql.stream().filter(q -> q.startsWith("select") && q.contains(" from season ")).toList();
        assertThat(seasonReads.get(0)).as("시즌의 첫 조회").contains("for no key update");
        assertThat(sql).as("회원 행 잠금 없음").noneMatch(q -> q.contains(" from member ") && q.contains("for "));
        // 잠금 앞에는 회원 읽기뿐이다
        int lock = 0;
        while (!(sql.get(lock).contains(" from season ") && sql.get(lock).contains("for no key update"))) {
            lock++;
        }
        assertThat(sql.subList(0, lock)).allMatch(q -> q.contains(" from member ") && !q.contains("for "));
        assertThat(sql.stream().filter(q -> q.startsWith("insert into reconciliation_log")).count()).isEqualTo(15);
    }
}

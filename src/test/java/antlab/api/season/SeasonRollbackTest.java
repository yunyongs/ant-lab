package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.PROBLEM;
import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.body;
import static antlab.api.season.SeasonApiSupport.counts;
import static antlab.api.season.SeasonApiSupport.formation;
import static antlab.api.season.SeasonApiSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스 단계의 거부와 늦은 실패가 6개 표를 건드리지 않는지 본다. 시험 트랜잭션이 없어야(커밋 모드)
 * 서비스 트랜잭션의 롤백이 실제로 일어나므로, 가짜 행을 커밋하고 시험 뒤 지운다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonRollbackTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private void assertRejected(String requestBody, int status, String code) throws Exception {
        Map<String, Long> before = counts(jdbc);
        MockHttpServletResponse r = post(mvc, requestBody);
        assertThat(r.getStatus()).isEqualTo(status);
        assertThat(r.getContentType()).startsWith(PROBLEM.toString());
        assertThat(JsonMapper.shared().readTree(r.getContentAsString()).get("code").asString()).isEqualTo(code);
        assertThat(counts(jdbc)).as("거부된 요청은 6개 표에 행을 남기지 않는다(회원 포함)").isEqualTo(before);
    }

    @Test
    void 경로_길이_10은_운영_출처에서_400() throws Exception {
        assertRejected(body("\"-0.20\"", formation("10", "30", "30", "30"), "10"), 400, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"61", "120"})
    void 경로_길이가_60을_넘으면_422_PATH_TOO_LONG(String length) throws Exception {
        assertRejected(body("\"-0.20\"", formation("10", "30", "30", "30"), length), 422, "PATH_TOO_LONG");
    }

    @Test
    void 풀이_비면_409_POOL_NOT_LOADED() throws Exception {
        jdbc.update("DELETE FROM asset_position WHERE base_date = ?", BASE);
        assertRejected(VALID_BODY, 409, "POOL_NOT_LOADED");
    }

    @Test
    void 판정이_빈_종목이_있으면_409_POOL_NOT_JUDGED() throws Exception {
        jdbc.update("""
                UPDATE asset_position SET window_start = NULL, window_end = NULL, months_used = NULL,
                    up_participation = NULL, down_participation = NULL, volatility = NULL, max_drawdown = NULL,
                    system_position = NULL
                WHERE base_date = ? AND asset_id = (SELECT id FROM asset WHERE ticker = 'SPY')""", BASE);
        assertRejected(VALID_BODY, 409, "POOL_NOT_JUDGED");
    }

    @Test
    void 늦은_실패는_앞선_INSERT가_나간_뒤에도_6개_표를_되돌린다() throws Exception {
        // 종목 번호가 가장 큰 마지막 종목: 기준가 × 환율이 0원으로 반올림되는 합성 값 → 0턴 가격 저장이 마지막에 실패한다
        Long group = jdbc.queryForObject("SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'", Long.class);
        Long last = jdbc.queryForObject("""
                INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
                VALUES ('KR', '999999', '가짜 마지막', 'STOCK', 'KRW', ?, DATE '2000-01-04', 'PRICE') RETURNING id""",
                Long.class, group);
        jdbc.update("""
                INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end,
                    months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
                VALUES (?, DATE '2026-01-02', 0.0001, 1.0000, DATE '2020-01-01', DATE '2025-12-01', 72,
                    1.00000000, 1.00000000, 0.05000000, -0.20000000, 'MIDFIELDER')""", last);
        Map<String, Long> before = counts(jdbc);

        SqlRecorder.start();
        MockHttpServletResponse r = post(mvc, VALID_BODY);
        List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();

        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(r.getContentType()).startsWith(PROBLEM.toString());
        // 롤백 시험이 헛돌지 않게: 앞 표 INSERT 가 실제로 DB 로 나갔다
        assertThat(sql).anyMatch(q -> q.startsWith("insert into season "));
        assertThat(sql).anyMatch(q -> q.startsWith("insert into cash_ledger"));
        assertThat(sql.stream().filter(q -> q.startsWith("insert into turn_price")).count()).isGreaterThanOrEqualTo(1);
        assertThat(counts(jdbc)).isEqualTo(before);
        // 응답에는 내부 내용이 없다
        assertThat(r.getContentAsString()).doesNotContain("0.0001").doesNotContain("turn_price");
    }
}

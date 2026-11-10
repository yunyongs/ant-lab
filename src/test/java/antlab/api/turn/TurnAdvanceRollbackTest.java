package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.state;
import static antlab.api.turn.TurnApiSupport.assetId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleCatalog;
import antlab.scoring.RuleResult;
import antlab.season.TurnService;
import antlab.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대사 불일치 시 전체 롤백 + 롤백 뒤 기록. 커밋된 상태를 깨뜨려(보유가 있는데 라인업 구간을 닫음 — 드릴 대본의 첫 단계) 전환하면
 * 409 RECONCILIATION_FAILED 이고 가격·스냅샷·현재 턴·버전이 그대로이며 reconciliation_log 만 17행 늘어난다.
 * 예외는 17행 결과를 불변 값으로 싣는다. {@code TurnService} 를 직접 부르면 기록은 없다(기록은 {@code TurnFacade} 의 몫).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceRollbackTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TurnService turnService;

    private long startSeason() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    private long brokenSeason() throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ? AND asset_id = ?", season, assetId(jdbc, "069500"));
        return season;
    }

    @Test
    void 대사가_어긋나면_409_RECONCILIATION_FAILED이고_가격_스냅샷_현재_턴이_그대로다() throws Exception {
        long season = brokenSeason();
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("RECONCILIATION_FAILED");
        assertThat(json(r).get("detail").asString()).isEqualTo("대사가 맞지 않아 턴을 넘기지 않았다");
        assertThat(r.getContentAsString()).doesNotContain("LINEUP").doesNotContain("POSITION");
        Map<String, Object> after = state(jdbc, season);
        assertThat(after.get("turn")).isEqualTo(before.get("turn"));
        assertThat(after.get("turn_price")).isEqualTo(before.get("turn_price"));
        assertThat(after.get("season")).isEqualTo(before.get("season"));
        assertThat(after.get("account")).isEqualTo(before.get("account"));
        // 롤백 뒤 새 트랜잭션에 17행이 남았다 — 되돌려지지 않는 것은 reconciliation_log 뿐이다.
        assertThat((Long) after.get("reconciliation_log")).isEqualTo((Long) before.get("reconciliation_log") + 17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 1", Long.class, season))
                .isZero();
    }

    @Test
    void 예외는_17행_결과를_싣고_불일치_규칙은_라인업_쪽이다() throws Exception {
        long season = brokenSeason();
        assertThatThrownBy(() -> turnService.advance(season, 0)).isInstanceOfSatisfying(ReconciliationFailedException.class, e -> {
            assertThat(e.getSeasonId()).isEqualTo(season);
            assertThat(e.getTurnNo()).isEqualTo(1);
            assertThat(e.getResults()).hasSize(17);
            List<Integer> mismatched = e.getResults().stream().filter(x -> x.status() == ReconciliationStatus.MISMATCH)
                    .map(RuleResult::ruleNo).collect(Collectors.toList());
            System.out.println("[T8] 불일치 규칙 번호 = " + mismatched);
            assertThat(mismatched).containsExactly(17, 19);
        });
        // 퍼사드를 거치지 않았으므로 기록은 없다 — 안쪽 트랜잭션의 롤백과 함께 로그도 사라진다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isZero();
    }

    @Test
    void 롤백_뒤_남은_17행은_불일치_두_개와_분류_상세_규칙을_지킨다() throws Exception {
        long season = brokenSeason();
        Map<String, Object> before = state(jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(409);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT check_code, attempt_no, turn_no, status, classification, detail IS NOT NULL AS has_detail"
                        + " FROM reconciliation_log WHERE season_id = ? ORDER BY check_code", season);
        assertThat(rows).hasSize(17);
        assertThat(rows).allSatisfy(row -> {
            assertThat(((Number) row.get("turn_no")).intValue()).isEqualTo(1);
            assertThat(((Number) row.get("attempt_no")).intValue()).isEqualTo(1);
            boolean mismatch = "MISMATCH".equals(row.get("status"));
            assertThat(row.get("classification")).isEqualTo(mismatch ? "INVESTIGATE" : null);
            assertThat((Boolean) row.get("has_detail")).isEqualTo(mismatch);
        });
        assertThat(rows.stream().map(row -> (String) row.get("check_code")).collect(Collectors.toSet()))
                .isEqualTo(RuleCatalog.allCodes().stream().map(Enum::name).collect(Collectors.toSet()));
        assertThat(rows.stream().filter(row -> "MISMATCH".equals(row.get("status")))
                .map(row -> (String) row.get("check_code")).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("LINEUP_HOLDING", "POSITION_VALUE");
        // detail 항목의 키는 계약(드릴과 같은 10개) 안이고 금액 키가 없다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log l, jsonb_array_elements(l.detail -> 'items') i,"
                + " jsonb_object_keys(i) k WHERE l.season_id = ? AND k NOT IN ('kind', 'kinds', 'turn_no', 'asset_id',"
                + " 'lineup_id', 'other_lineup_id', 'order_id', 'execution_id', 'ledger_id', 'formation_change_id')",
                Long.class, season)).isZero();
        // 깨뜨린 라인업은 닫힌 채, 계좌·시즌은 그대로(실패 기록은 season.version 을 올리지 않는다)
        Map<String, Object> after = state(jdbc, season);
        assertThat(after.get("season")).isEqualTo(before.get("season"));
        assertThat(after.get("account")).isEqualTo(before.get("account"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lineup WHERE season_id = ? AND asset_id = ? AND end_turn IS NOT NULL",
                Long.class, season, assetId(jdbc, "069500"))).isEqualTo(1L);
    }

    @Test
    void 고치지_않고_다시_보내면_시도_2도_실패_17행이고_고친_뒤에는_시도_3이_17_일치다() throws Exception {
        long season = brokenSeason();
        Map<String, Object> before = state(jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(409);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(409); // 고치지 않았다 → 시도 2 도 실패
        assertThat(attempt(season, 2, "MISMATCH")).isEqualTo(2);
        jdbc.update("UPDATE lineup SET end_turn = NULL WHERE season_id = ? AND asset_id = ?", season, assetId(jdbc, "069500"));
        MockHttpServletResponse ok = advance(mvc, season, 0);
        assertThat(ok.getStatus()).isEqualTo(201);

        assertThat(attempt(season, 1, "MISMATCH")).isEqualTo(2);
        assertThat(attempt(season, 2, "MISMATCH")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = 3 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17L);
        assertThat((Long) state(jdbc, season).get("reconciliation_log")).isEqualTo((Long) before.get("reconciliation_log") + 51);
        // 성공: 현재 턴 +1, 버전 +1 (실패 때는 그대로였다)
        assertThat(state(jdbc, season).get("season")).isEqualTo("IN_PROGRESS:1:" + (versionOf(before) + 1));
        // 흐르는 시계: 시도마다 묶음 생성 시각이 순서대로 늘어난다
        List<java.time.OffsetDateTime> firsts = jdbc.query(
                "SELECT min(created_at) FROM reconciliation_log WHERE season_id = ? GROUP BY attempt_no ORDER BY attempt_no",
                (rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class), season);
        assertThat(firsts).hasSize(3);
        assertThat(firsts.get(0)).isBefore(firsts.get(1));
        assertThat(firsts.get(1)).isBefore(firsts.get(2));
    }

    private long versionOf(Map<String, Object> state) {
        String[] parts = ((String) state.get("season")).split(":");
        return Long.parseLong(parts[2]);
    }

    /** 그 시도 번호 묶음에서 주어진 상태의 행 수(불일치 시도는 2, 일치 시도는 17). */
    private long attempt(long season, int attemptNo, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = ? AND status = ?",
                Long.class, season, attemptNo, status);
    }
}

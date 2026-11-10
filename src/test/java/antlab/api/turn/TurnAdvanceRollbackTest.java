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
 * 대사 불일치 시 전체 롤백. 커밋된 상태를 깨뜨려(보유가 있는데 라인업 구간을 닫음 — 드릴 대본의 첫 단계) 전환하면
 * 409 RECONCILIATION_FAILED 이고 가격·스냅샷·현재 턴이 그대로다. 예외는 17행 결과를 불변 값으로 싣는다.
 * <b>reconciliation_log 행 수는 단언하지 않는다</b> — 롤백 뒤 새 트랜잭션에 기록하는 일은 P0-34 의 몫이다(거기서 "롤백 뒤 17행"을 더한다).
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
        assertThat(after.get("reconciliation_log")).isEqualTo(before.get("reconciliation_log")); // P0-34 가 "롤백 뒤 17행 기록"으로 바꾼다(지금은 롤백과 함께 사라진다)
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
    }
}

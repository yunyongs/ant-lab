package antlab.api.formation;

import static antlab.api.formation.FormationApiSupport.A;
import static antlab.api.formation.FormationApiSupport.change;
import static antlab.api.formation.FormationApiSupport.state;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 전술 변경의 원자성 — 이력 INSERT 뒤 시즌 UPDATE 가 실패하게 만들어(그 시즌 행의 UPDATE 를 거부하는 시험용 트리거) 이력·현재 포메이션·
 * 버전이 모두 되돌아가는지 본다. 트리거는 시험이 만들고 반드시 지운다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationChangeRollbackTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 시즌_UPDATE가_실패하면_이미_넣은_변경_행도_함께_되돌아간다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        Map<String, Object> before = state(jdbc, season);

        jdbc.execute("CREATE FUNCTION test_reject_season_update() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'season update rejected for test'; END $$");
        jdbc.execute("CREATE TRIGGER test_reject_season_update BEFORE UPDATE ON season FOR EACH ROW "
                + "WHEN (NEW.id = " + season + ") EXECUTE FUNCTION test_reject_season_update()");
        MockHttpServletResponse failed;
        try {
            failed = change(mvc, season, 1, A);
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_season_update ON season");
            jdbc.execute("DROP FUNCTION test_reject_season_update()");
        }

        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(failed.getContentAsString()).doesNotContain("rejected").doesNotContain("season update");
        Map<String, Object> after = state(jdbc, season);
        // 번호표(identity)는 롤백돼도 쓰인다 — 비교에서 뺀다. 이력 행·시즌 상태·현재 포메이션·버전은 그대로다.
        assertThat(after.get("changes")).isEqualTo(before.get("changes")).isEqualTo("");
        assertThat(after.get("season")).isEqualTo(before.get("season"));
        assertThat(after.get("current")).isEqualTo(before.get("current"));
        // 실패한 뒤 같은 요청이 정상으로 처리된다(상태가 깨지지 않았다)
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
    }
}

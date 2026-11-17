package antlab.api.dev;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.turn.TurnApiSupport;
import antlab.support.DefaultBearerConfig;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
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

/** Resume endpoint (local profile): target status, idempotence, header gate, role gate, 404. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({PostgresTestcontainersConfig.class, TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class DevResumeTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestMembers members;

    private static String path(long season) {
        return "/api/v1/dev/seasons/" + season + "/reconciliation/resume";
    }

    private MockHttpServletResponse admin(long season) {
        return mvc.post().uri(path(season)).header("Authorization", "Bearer " + tokens.admin(members.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange()
                .getResponse();
    }

    private static JsonNode body(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    private String status(long season) {
        return jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season);
    }

    @Test
    void haltedSeasonResumesToInProgressAndTheSecondCallChangesNothing() throws Exception {
        long season = TurnApiSupport.startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", season);
        long version = jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season);

        MockHttpServletResponse r = admin(season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(body(r).get("seasonId").asLong()).isEqualTo(season);
        assertThat(body(r).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(body(r).get("resumed").asBoolean()).isTrue();
        assertThat(body(r).size()).isEqualTo(3);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season)).isEqualTo(version + 1);

        MockHttpServletResponse again = admin(season);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(body(again).get("resumed").asBoolean()).isFalse();
        assertThat(body(again).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season)).isEqualTo(version + 1);
    }

    @Test
    void haltedSeasonWithAnAbandonTurnResumesToAbandoning() throws Exception {
        long season = TurnApiSupport.startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'HALTED', abandoned_turn_no = 0 WHERE id = ?", season);
        MockHttpServletResponse r = admin(season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(body(r).get("status").asString()).isEqualTo("ABANDONING");
        assertThat(status(season)).isEqualTo("ABANDONING");
    }

    @Test
    void finishedAndAbandoningSeasonsAreLeftAlone() throws Exception {
        long finished = TurnApiSupport.startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", finished);
        assertThat(body(admin(finished)).get("resumed").asBoolean()).isFalse();
        assertThat(status(finished)).isEqualTo("FINISHED");

        long abandoning = TurnApiSupport.startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", abandoning);
        assertThat(body(admin(abandoning)).get("resumed").asBoolean()).isFalse();
        assertThat(status(abandoning)).isEqualTo("ABANDONING");
    }

    @Test
    void noHeaderOrWrongHeaderIs403ForbiddenAndAUserTokenIs403() throws Exception {
        long season = TurnApiSupport.startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", season);
        MockHttpServletResponse none = mvc.post().uri(path(season))
                .header("Authorization", "Bearer " + tokens.admin(members.defaultId())).exchange().getResponse();
        assertThat(none.getStatus()).isEqualTo(403);
        assertThat(body(none).get("code").asString()).isEqualTo("FORBIDDEN");
        MockHttpServletResponse wrong = mvc.post().uri(path(season))
                .header("Authorization", "Bearer " + tokens.admin(members.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, "false").exchange().getResponse();
        assertThat(wrong.getStatus()).isEqualTo(403);
        MockHttpServletResponse user = mvc.post().uri(path(season))
                .header("Authorization", "Bearer " + tokens.user(members.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange()
                .getResponse();
        assertThat(user.getStatus()).isEqualTo(403);
        assertThat(status(season)).as("no refused call resumed the season").isEqualTo("HALTED");
    }

    @Test
    void unknownSeasonIs404NotFound() throws Exception {
        TurnApiSupport.startSeason(mvc); // make sure the default member exists
        MockHttpServletResponse r = admin(Long.MAX_VALUE);
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(body(r).get("code").asString()).isEqualTo("NOT_FOUND");
    }
}

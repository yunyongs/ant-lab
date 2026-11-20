package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** The runner league routes: create, read and close. Entry into a league is not part of them. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class LeagueRoutesTest {

    private static final String LEAGUES = BASE + "/leagues";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    private MockHttpServletResponse get(String uri, String token) {
        return mvc.get().uri(uri).header(HEADER, token).requestAttr(ANONYMOUS, true).exchange().getResponse();
    }

    private long create(String body) throws Exception {
        MockHttpServletResponse r = post(mvc, LEAGUES, body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private long leagueRows() {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM league", Long.class);
    }

    /** An agent season put in the league by SQL: the entry route is C2, the read and close rules need a seat to look at. */
    private long seat(long league) throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        long season = json(post(mvc, AGENTS + "/" + agent + "/seasons", START_BODY)).get("id").asLong();
        em.flush();
        jdbc.update("INSERT INTO league_entry (league_id, season_id, member_id) VALUES (?, ?, ?)", league, season, agent);
        return season;
    }

    private void sql(String sql, Object... args) {
        em.flush();
        jdbc.update(sql, args);
        em.clear();
    }

    @Test
    void a_created_league_has_the_defaults_no_entries_and_no_seed_in_the_answer() throws Exception {
        MockHttpServletResponse r = post(mvc, LEAGUES, "{\"name\": \"  Spring league \"}");

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode body = json(r);
        assertThat(body.propertyNames()).containsExactly("id", "name", "status", "pathLength", "maxEntries", "entries");
        assertThat(body.get("name").asString()).isEqualTo("Spring league");
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(body.get("pathLength").asInt()).isEqualTo(12);
        assertThat(body.get("maxEntries").asInt()).isEqualTo(8);
        assertThat(body.get("entries")).isEmpty();

        em.flush();
        var row = jdbc.queryForMap("SELECT * FROM league WHERE id = ?", body.get("id").asLong());
        assertThat(row).containsEntry("owner_member_id", OWNER_ID).containsEntry("status", "OPEN")
                .containsEntry("closed_at", null);
        assertThat(row.get("seed")).isNotNull();
        assertThat(r.getContentAsString()).doesNotContain(String.valueOf(row.get("seed")))
                .doesNotContain("seed").doesNotContain("engine").doesNotContain("baseDate");
        // the settings are the ones a season start would write
        var season = jdbc.queryForMap("""
                SELECT base_date, data_snapshot_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate FROM league
                WHERE id = ?""", body.get("id").asLong());
        assertThat(season.get("initial_cash")).isNotNull();
        assertThat(row.get("engine_version")).isNotNull();
    }

    @Test
    void the_length_and_the_seat_limit_can_be_chosen_and_the_seed_cannot() throws Exception {
        MockHttpServletResponse r = post(mvc, LEAGUES,
                "{\"name\": \"Long\", \"pathLength\": 24, \"maxEntries\": 3, \"seed\": 5}");

        if (r.getStatus() == 201) {
            assertThat(json(r).get("pathLength").asInt()).isEqualTo(24);
            assertThat(json(r).get("maxEntries").asInt()).isEqualTo(3);
        } else {
            assertThat(r.getStatus()).isEqualTo(400);
        }
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM league WHERE seed = 5", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\": \"\"}", "{\"name\": \"   \"}", "{\"name\": \"x\", \"maxEntries\": 1}",
            "{\"name\": \"x\", \"maxEntries\": 21}", "{\"name\": \"x\", \"pathLength\": 13}",
            "{\"name\": \"x\", \"pathLength\": 0}"})
    void a_refused_create_leaves_no_league(String body) throws Exception {
        long before = leagueRows();
        MockHttpServletResponse r = post(mvc, LEAGUES, body);

        assertThat(r.getStatus()).isIn(400, 422);
        assertThat(leagueRows()).isEqualTo(before);
    }

    @Test
    void a_name_over_fifty_characters_is_refused() throws Exception {
        assertThat(post(mvc, LEAGUES, "{\"name\": \"" + "x".repeat(51) + "\"}").getStatus()).isEqualTo(400);
        assertThat(post(mvc, LEAGUES, "{\"name\": \"" + "x".repeat(50) + "\"}").getStatus()).isEqualTo(201);
    }

    @Test
    void reading_shows_each_seat_with_the_effective_status_and_nothing_of_the_path() throws Exception {
        long league = create("{\"name\": \"Seats\"}");
        long season = seat(league);

        MockHttpServletResponse r = get(LEAGUES + "/" + league, TOKEN);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode seat = json(r).get("entries").get(0);
        assertThat(seat.propertyNames()).containsExactly("memberId", "memberKind", "displayName", "seasonId", "status",
                "currentTurnNo");
        assertThat(seat.get("seasonId").asLong()).isEqualTo(season);
        assertThat(seat.get("memberKind").asString()).isEqualTo("AGENT");
        assertThat(seat.get("displayName").asString()).isEqualTo("가치 투자자");
        assertThat(seat.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(seat.get("currentTurnNo").asInt()).isZero();

        // a pause past 7 days is an abandon on a read (O-2), whatever the stored status says
        sql("UPDATE season SET status = 'PAUSED', paused_at = now() - interval '8 days' WHERE id = ?", season);
        assertThat(json(get(LEAGUES + "/" + league, TOKEN)).get("entries").get(0).get("status").asString())
                .isEqualTo("ABANDONING");
        sql("UPDATE season SET paused_at = now() - interval '1 day' WHERE id = ?", season);
        assertThat(json(get(LEAGUES + "/" + league, TOKEN)).get("entries").get(0).get("status").asString())
                .isEqualTo("PAUSED");
    }

    @Test
    void a_missing_league_and_another_owners_league_give_the_same_answer() throws Exception {
        em.flush();
        long other = jdbc.queryForObject(
                "INSERT INTO member (email, name, password) VALUES ('other@example.invalid', 'other', '!') RETURNING id",
                Long.class);
        long foreign = jdbc.queryForObject("""
                INSERT INTO league (owner_member_id, name, seed, path_length, base_date, engine_version, data_snapshot_date,
                    initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, max_entries)
                VALUES (?, 'foreign', 1, 12, '2020-01-31', 'test', '2020-01-31', 10000000, 0.001, 0.001, 0.004, 8)
                RETURNING id""", Long.class, other);

        MockHttpServletResponse missing = get(LEAGUES + "/987654321", TOKEN);
        MockHttpServletResponse foreignRead = get(LEAGUES + "/" + foreign, TOKEN);
        MockHttpServletResponse foreignClose = post(mvc, LEAGUES + "/" + foreign + "/close", "");

        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(foreignRead.getStatus()).isEqualTo(404);
        assertThat(foreignClose.getStatus()).isEqualTo(404);
        assertThat(json(foreignRead).get("code").asString()).isEqualTo(json(missing).get("code").asString())
                .isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM league WHERE id = ?", String.class, foreign))
                .isEqualTo("OPEN");
    }

    @Test
    void a_league_number_that_is_not_a_number_is_400() throws Exception {
        assertThat(get(LEAGUES + "/abc", TOKEN).getStatus()).isEqualTo(400);
        assertThat(post(mvc, LEAGUES + "/abc/close", "").getStatus()).isEqualTo(400);
    }

    @Test
    void closing_waits_for_every_seat_to_be_finished_or_void_and_a_repeat_is_not_an_error() throws Exception {
        long league = create("{\"name\": \"Close me\"}");
        long season = seat(league);

        MockHttpServletResponse early = post(mvc, LEAGUES + "/" + league + "/close", "");
        assertThat(early.getStatus()).isEqualTo(409);
        assertThat(json(early).get("code").asString()).isEqualTo("LEAGUE_NOT_SETTLED");
        em.flush();
        assertThat(jdbc.queryForObject("SELECT status FROM league WHERE id = ?", String.class, league)).isEqualTo("OPEN");

        // an overdue pause is an abandon, not a settled season
        sql("UPDATE season SET status = 'PAUSED', paused_at = now() - interval '8 days' WHERE id = ?", season);
        assertThat(post(mvc, LEAGUES + "/" + league + "/close", "").getStatus()).isEqualTo(409);

        sql("UPDATE season SET status = 'VOID', paused_at = NULL, void_reason = 'MODEL_GONE', void_note = 'gone' WHERE id = ?", season);
        MockHttpServletResponse closed = post(mvc, LEAGUES + "/" + league + "/close", "");
        assertThat(closed.getStatus()).isEqualTo(200);
        assertThat(json(closed).get("status").asString()).isEqualTo("CLOSED");
        em.flush();
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM league WHERE id = ?", Boolean.class, league))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM league_entry WHERE league_id = ?", Long.class, league))
                .isOne();

        MockHttpServletResponse again = post(mvc, LEAGUES + "/" + league + "/close", "");
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).get("status").asString()).isEqualTo("CLOSED");
    }

    @Test
    void a_finished_seat_settles_too_and_an_empty_league_can_be_closed() throws Exception {
        long empty = create("{\"name\": \"Empty\"}");
        assertThat(post(mvc, LEAGUES + "/" + empty + "/close", "").getStatus()).isEqualTo(200);

        long league = create("{\"name\": \"Done\"}");
        long season = seat(league);
        sql("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        MockHttpServletResponse r = post(mvc, LEAGUES + "/" + league + "/close", "");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("entries").get(0).get("status").asString()).isEqualTo("FINISHED");
    }

    @Test
    void the_service_token_is_required_on_all_three_routes() throws Exception {
        long league = create("{\"name\": \"Auth\"}");

        assertThat(post(mvc, LEAGUES, "wrong", "{\"name\": \"x\"}", true).getStatus()).isEqualTo(401);
        assertThat(post(mvc, LEAGUES, null, "{\"name\": \"x\"}", true).getStatus()).isEqualTo(401);
        assertThat(get(LEAGUES + "/" + league, "wrong").getStatus()).isEqualTo(401);
        assertThat(post(mvc, LEAGUES + "/" + league + "/close", "wrong", "", true).getStatus()).isEqualTo(401);
    }
}

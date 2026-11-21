package antlab.api.league;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static antlab.api.agentrunner.AgentRunnerSupport.startBody;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.agentrunner.AgentRunnerSupport;
import antlab.support.IntegrationTest;
import antlab.support.TestTokens;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The public league table (AG-04 PR D): the seats as they are, the valuation multiple of FINISHED seats only, the rank
 * only after the league is closed, and nothing that would show the path (seed, dates, engine). Seat states are set by SQL
 * so every status can be tried; entry itself is covered by LeagueEntryTest.
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class LeagueTableRouteTest {

    private static final String LEAGUES = BASE + "/leagues";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    TestTokens tokens;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    private long league(String name) throws Exception {
        MockHttpServletResponse r = post(mvc, LEAGUES, "{\"name\": \"" + name + "\"}");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private long agentSeat(long league, String model) throws Exception {
        long agent = AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", model, "에이전트 " + model));
        MockHttpServletResponse r = post(mvc, AGENTS + "/" + agent + "/seasons", startBody(model, league, null));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private long ownerSeat(long league) throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").header("Authorization", "Bearer " + tokens.user(OWNER_ID))
                .requestAttr(ANONYMOUS, true).contentType(MediaType.APPLICATION_JSON).content("""
                        {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
                         "leagueId": %d}""".formatted(league)).exchange().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private MockHttpServletResponse table(long league, String bearer) {
        em.flush();
        em.clear();
        var request = mvc.get().uri("/api/v1/leagues/" + league).requestAttr(ANONYMOUS, true);
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return request.exchange().getResponse();
    }

    private JsonNode ownerTable(long league) throws Exception {
        MockHttpServletResponse r = table(league, tokens.user(OWNER_ID));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json(r);
    }

    /** Finishes a season with the last-turn valuation {@code total} (whole won, all of it cash). */
    private void finish(long season, long total) {
        em.flush();
        jdbc.update("UPDATE season SET current_turn_no = path_length, status = 'FINISHED' WHERE id = ?", season);
        jdbc.update("""
                INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value,
                                  midfielder_value, forward_value, drawdown)
                SELECT id, path_length, ?, 0, ?, ?, 0, 0, 0, 0 FROM season WHERE id = ?""", total, total, total, season);
        em.clear();
    }

    /** Whole won of the season's initial capital, so a test states multiples as ratios of it. */
    private long initialCash(long season) {
        em.flush();
        return jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", Long.class, season);
    }

    private void voidSeason(long season) {
        em.flush();
        jdbc.update("UPDATE season SET status = 'VOID', void_reason = 'MODEL_GONE', void_note = 'gone' WHERE id = ?", season);
        em.clear();
    }

    private static JsonNode seat(JsonNode table, long season) {
        for (JsonNode e : table.get("entries")) {
            if (e.get("seasonId").asLong() == season) {
                return e;
            }
        }
        throw new AssertionError("no seat for season " + season);
    }

    @Test
    void an_open_league_shows_status_turn_and_the_agent_block_but_no_multiple_rank_or_path_field() throws Exception {
        long league = league("Table league");
        long agentSeason = agentSeat(league, "fake/model-1");
        long humanSeason = ownerSeat(league);
        em.flush();
        Long seed = jdbc.queryForObject("SELECT seed FROM league WHERE id = ?", Long.class, league);

        MockHttpServletResponse r = table(league, tokens.user(OWNER_ID));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode body = json(r);

        assertThat(body.propertyNames()).containsExactly("id", "name", "status", "pathLength", "maxEntries", "entries");
        assertThat(body.get("id").asLong()).isEqualTo(league);
        assertThat(body.get("name").asString()).isEqualTo("Table league");
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(body.get("entries")).hasSize(2);
        JsonNode agent = seat(body, agentSeason);
        JsonNode human = seat(body, humanSeason);
        assertThat(agent.propertyNames()).containsExactly("memberId", "memberKind", "displayName", "seasonId", "status",
                "currentTurnNo", "ai", "ownsAgentsHere", "valuationMultiple", "multipleRank");
        assertThat(agent.get("memberKind").asString()).isEqualTo("AGENT");
        assertThat(agent.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(agent.get("currentTurnNo").asInt()).isZero();
        assertThat(agent.get("ai").propertyNames()).containsExactly("personaKey", "personaVersion", "modelRoute", "modelId",
                "memoryVersion");
        assertThat(agent.get("ai").get("personaKey").asString()).isEqualTo("value-investor");
        assertThat(agent.get("ai").get("personaVersion").asInt()).isEqualTo(1);
        assertThat(agent.get("ai").get("modelRoute").asString()).isEqualTo("OPENROUTER");
        assertThat(agent.get("ai").get("modelId").asString()).isEqualTo("fake/model-1");
        assertThat(agent.get("ai").get("memoryVersion").isNull()).isTrue();
        assertThat(agent.get("ownsAgentsHere").asBoolean()).isFalse();
        assertThat(human.get("memberKind").asString()).isEqualTo("HUMAN");
        assertThat(human.get("ai").isNull()).isTrue();
        assertThat(human.get("ownsAgentsHere").asBoolean()).as("the owner's agent is in this league").isTrue();
        for (JsonNode e : body.get("entries")) {
            assertThat(e.get("valuationMultiple").isNull()).isTrue();
            assertThat(e.get("multipleRank").isNull()).isTrue();
        }
        String text = r.getContentAsString();
        assertThat(text).doesNotContain("seed").doesNotContain("engine").doesNotContain("baseDate").doesNotContain("monthBlock")
                .doesNotContain("yearMonth").doesNotContain(String.valueOf(seed));
        assertThat(text).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void a_human_seat_does_not_own_agents_here_when_the_owners_agents_are_not_in_the_league() throws Exception {
        long league = league("Humans only");
        long humanSeason = ownerSeat(league);

        assertThat(seat(ownerTable(league), humanSeason).get("ownsAgentsHere").asBoolean()).isFalse();
    }

    @Test
    void an_open_league_hides_the_multiple_and_rank_of_a_finished_seat() throws Exception {
        long league = league("Open ranks");
        long a = agentSeat(league, "fake/model-1");
        long b = agentSeat(league, "fake/model-2");
        long c = agentSeat(league, "fake/model-3");
        long human = ownerSeat(league);
        long cash = initialCash(a);
        finish(a, cash * 12_345_679L / 10_000_000L);
        voidSeason(b);
        // an unfinished season with a row at its last turn must still show nothing (the multiple needs FINISHED)
        em.flush();
        jdbc.update("""
                INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value,
                                  midfielder_value, forward_value, drawdown)
                SELECT id, path_length, 99000000, 0, 99000000, 99000000, 0, 0, 0, 0 FROM season WHERE id = ?""", c);

        JsonNode body = ownerTable(league);

        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(seat(body, a).get("status").asString()).isEqualTo("FINISHED");
        for (JsonNode e : body.get("entries")) {
            assertThat(e.get("valuationMultiple").isNull()).as("no multiple while the league is open").isTrue();
            assertThat(e.get("multipleRank").isNull()).isTrue();
        }
        assertThat(seat(body, b).get("status").asString()).isEqualTo("VOID");
        assertThat(seat(body, c).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(seat(body, human).get("status").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void a_closed_league_ranks_by_multiple_with_ties_sharing_the_better_rank_and_leaves_void_seats_unranked() throws Exception {
        long league = league("Closed ranks");
        long a = agentSeat(league, "fake/model-1");
        long b = agentSeat(league, "fake/model-2");
        long c = agentSeat(league, "fake/model-3");
        long d = agentSeat(league, "fake/model-4");
        long human = ownerSeat(league);
        long cash = initialCash(a);
        finish(a, cash * 12 / 10);
        finish(human, cash * 12 / 10);
        finish(b, cash * 9 / 10);
        finish(c, cash * 3 / 10_000_000L);
        voidSeason(d);
        em.flush();
        assertThat(post(mvc, LEAGUES + "/" + league + "/close", "").getStatus()).isEqualTo(200);

        JsonNode body = ownerTable(league);

        assertThat(body.get("status").asString()).isEqualTo("CLOSED");
        assertThat(seat(body, a).get("valuationMultiple").asString()).isEqualTo("1.20000000");
        assertThat(seat(body, human).get("valuationMultiple").asString()).isEqualTo("1.20000000");
        assertThat(seat(body, a).get("multipleRank").asInt()).isEqualTo(1);
        assertThat(seat(body, human).get("multipleRank").asInt()).isEqualTo(1);
        assertThat(seat(body, b).get("valuationMultiple").asString()).isEqualTo("0.90000000");
        assertThat(seat(body, b).get("multipleRank").asInt()).as("two seats ahead, so rank 3").isEqualTo(3);
        assertThat(seat(body, c).get("valuationMultiple").asString()).isEqualTo("0.00000030");
        assertThat(seat(body, c).get("multipleRank").asInt()).isEqualTo(4);
        assertThat(seat(body, d).get("valuationMultiple").isNull()).isTrue();
        assertThat(seat(body, d).get("multipleRank").isNull()).isTrue();
    }

    @Test
    void the_table_reads_a_pause_past_seven_days_as_an_abandon_and_a_shorter_one_as_a_pause() throws Exception {
        long league = league("Pauses");
        long over = agentSeat(league, "fake/model-1");
        long near = agentSeat(league, "fake/model-2");
        em.flush();
        jdbc.update("UPDATE season SET status = 'PAUSED', paused_at = now() - interval '7 days' WHERE id = ?", over);
        jdbc.update("UPDATE season SET status = 'PAUSED', paused_at = now() - interval '6 days 23 hours' WHERE id = ?", near);

        JsonNode body = ownerTable(league);

        assertThat(seat(body, over).get("status").asString()).isEqualTo("ABANDONING");
        assertThat(seat(body, near).get("status").asString()).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, over))
                .as("nothing is written").isEqualTo("PAUSED");
    }

    @Test
    void another_members_league_and_a_missing_one_answer_the_same_404_and_a_missing_token_is_401() throws Exception {
        long league = league("Private");
        long stranger = jdbc.queryForObject("""
                INSERT INTO member (email, name, password) VALUES ('table-stranger@example.invalid', '타인', '!') RETURNING id""",
                Long.class);

        MockHttpServletResponse notOwner = table(league, tokens.user(stranger));
        MockHttpServletResponse missing = table(league + 1000, tokens.user(OWNER_ID));
        MockHttpServletResponse anonymous = table(league, null);

        assertThat(notOwner.getStatus()).isEqualTo(404);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(json(notOwner).get("code").asString()).isEqualTo(json(missing).get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(json(notOwner).get("detail")).isEqualTo(json(missing).get("detail"));
        assertThat(notOwner.getContentAsString()).doesNotContain("Private");
        assertThat(anonymous.getStatus()).isEqualTo(401);
    }

    @Test
    void the_runner_token_alone_cannot_read_the_public_table() throws Exception {
        long league = league("Runner only");
        em.flush();

        MockHttpServletResponse r = mvc.get().uri("/api/v1/leagues/" + league)
                .header(AgentRunnerSupport.HEADER, AgentRunnerSupport.TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();

        assertThat(r.getStatus()).isEqualTo(401);
    }

    @Test
    void the_seat_list_holds_every_entry_in_season_order() throws Exception {
        long league = league("Order");
        List<Long> seasons = new ArrayList<>();
        seasons.add(agentSeat(league, "fake/model-1"));
        seasons.add(agentSeat(league, "fake/model-2"));
        seasons.add(ownerSeat(league));

        JsonNode body = ownerTable(league);

        List<Long> got = new ArrayList<>();
        body.get("entries").forEach(e -> got.add(e.get("seasonId").asLong()));
        assertThat(got).containsExactlyElementsOf(seasons);
    }
}

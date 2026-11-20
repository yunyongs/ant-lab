package antlab.api.agentrunner;

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

import antlab.support.IntegrationTest;
import antlab.support.TestTokens;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
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
 * Entry into a league through the normal season start: agents through the runner route, the owner through the public
 * route. The rollback of an entry refused after the season row exists is in {@link LeagueEntryCommitTest}.
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class LeagueEntryTest {

    private static final String LEAGUES = BASE + "/leagues";
    private static final String PUBLIC_START = "/api/v1/seasons";

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

    private long league(String body) throws Exception {
        MockHttpServletResponse r = post(mvc, LEAGUES, body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private long league() throws Exception {
        return league("{\"name\": \"Entry league\"}");
    }

    private long agent(String model) throws Exception {
        return AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", model, "에이전트 " + model));
    }

    private MockHttpServletResponse enter(long agent, String model, Long league, Integer pathLength) {
        return post(mvc, AGENTS + "/" + agent + "/seasons", startBody(model, league, pathLength));
    }

    /** The owner as a human member, through the public route. */
    private MockHttpServletResponse ownerStarts(long owner, Long league) {
        return mvc.post().uri(PUBLIC_START).header("Authorization", "Bearer " + tokens.user(owner))
                .requestAttr(ANONYMOUS, true).contentType(MediaType.APPLICATION_JSON).content("""
                        {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}%s}"""
                        .formatted(league == null ? "" : ", \"leagueId\": " + league))
                .exchange().getResponse();
    }

    private long seasonRows() {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM season", Long.class);
    }

    private static String code(MockHttpServletResponse r) throws Exception {
        return json(r).get("code").asString();
    }

    @Test
    void two_agents_and_the_owner_play_the_same_path_with_the_same_settings_and_prices() throws Exception {
        long league = league();
        long a = agent("fake/model-1");
        long b = agent("fake/model-2");

        MockHttpServletResponse ra = enter(a, "fake/model-1", league, null);
        MockHttpServletResponse rb = enter(b, "fake/model-2", league, 12);
        MockHttpServletResponse rh = ownerStarts(OWNER_ID, league);

        for (MockHttpServletResponse r : List.of(ra, rb, rh)) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
            JsonNode body = json(r);
            assertThat(body.get("leagueId").asLong()).isEqualTo(league);
            assertThat(r.getContentAsString()).as("no seed or path field in an entry answer").doesNotContain("seed")
                    .doesNotContain("monthBlock").doesNotContain("yearMonth").doesNotContain("engineVersion");
        }
        em.flush();
        List<Long> seasons = List.of(json(ra).get("id").asLong(), json(rb).get("id").asLong(), json(rh).get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM league_entry WHERE league_id = ?", Long.class, league)).isEqualTo(3);

        Map<String, Object> leagueRow = jdbc.queryForMap("SELECT seed, path_length, base_date, engine_version, "
                + "data_snapshot_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate FROM league WHERE id = ?", league);
        for (long season : seasons) {
            Map<String, Object> row = jdbc.queryForMap("SELECT seed, path_length, base_date, engine_version, "
                    + "data_snapshot_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate FROM season WHERE id = ?", season);
            assertThat(row).as("season " + season + " carries the league's fixed settings").isEqualTo(leagueRow);
        }
        List<String> paths = seasons.stream().map(s -> jdbc.queryForObject(
                "SELECT string_agg(turn_no || ':' || month_block_id, ',' ORDER BY turn_no) FROM season_path WHERE season_id = ?",
                String.class, s)).toList();
        assertThat(paths.get(0)).isNotEmpty().isEqualTo(paths.get(1)).isEqualTo(paths.get(2));
        List<String> prices = seasons.stream().map(s -> jdbc.queryForObject(
                "SELECT string_agg(turn_no || ':' || asset_id || ':' || price, ',' ORDER BY turn_no, asset_id) FROM turn_price "
                        + "WHERE season_id = ?", String.class, s)).toList();
        assertThat(prices.get(0)).isNotEmpty().isEqualTo(prices.get(1)).isEqualTo(prices.get(2));

        // a season outside any league keeps a different drawn seed and a null league id
        MockHttpServletResponse outside = ownerStarts(OWNER_ID, null);
        assertThat(outside.getStatus()).isEqualTo(201);
        assertThat(json(outside).get("leagueId").isNull()).isTrue();
        em.flush();
        assertThat(jdbc.queryForObject("SELECT seed FROM season WHERE id = ?", Long.class, json(outside).get("id").asLong()))
                .isNotEqualTo(leagueRow.get("seed"));
    }

    @Test
    void the_season_reads_show_the_league_id_and_the_league_table_shows_the_three_seats() throws Exception {
        long league = league();
        long a = agent("fake/model-1");
        long season = json(enter(a, "fake/model-1", league, null)).get("id").asLong();
        MockHttpServletResponse owner = ownerStarts(OWNER_ID, league);
        long ownerSeason = json(owner).get("id").asLong();
        em.flush();

        MockHttpServletResponse one = mvc.get().uri("/api/v1/seasons/" + ownerSeason)
                .header("Authorization", "Bearer " + tokens.user(OWNER_ID)).requestAttr(ANONYMOUS, true).exchange().getResponse();
        assertThat(json(one).get("leagueId").asLong()).isEqualTo(league);
        MockHttpServletResponse list = mvc.get().uri("/api/v1/seasons")
                .header("Authorization", "Bearer " + tokens.user(OWNER_ID)).requestAttr(ANONYMOUS, true).exchange().getResponse();
        assertThat(json(list).get("items").get(0).get("leagueId").asLong()).isEqualTo(league);

        MockHttpServletResponse table = mvc.get().uri(LEAGUES + "/" + league).header(AgentRunnerSupport.HEADER, AgentRunnerSupport.TOKEN)
                .requestAttr(ANONYMOUS, true).exchange().getResponse();
        JsonNode entries = json(table).get("entries");
        assertThat(entries).hasSize(2);
        assertThat(table.getContentAsString()).doesNotContain("seed").doesNotContain("leagueId");
        assertThat(season).isPositive();
    }

    @Test
    void a_second_entry_of_one_member_is_LEAGUE_SEAT_TAKEN_and_starts_no_season() throws Exception {
        long league = league();
        long a = agent("fake/model-1");
        assertThat(enter(a, "fake/model-1", league, null).getStatus()).isEqualTo(201);
        assertThat(ownerStarts(OWNER_ID, league).getStatus()).isEqualTo(201);
        long before = seasonRows();

        MockHttpServletResponse agentAgain = enter(a, "fake/model-1", league, null);
        MockHttpServletResponse ownerAgain = ownerStarts(OWNER_ID, league);

        assertThat(agentAgain.getStatus()).isEqualTo(409);
        assertThat(code(agentAgain)).isEqualTo("LEAGUE_SEAT_TAKEN");
        assertThat(ownerAgain.getStatus()).isEqualTo(409);
        assertThat(code(ownerAgain)).isEqualTo("LEAGUE_SEAT_TAKEN");
        assertThat(seasonRows()).isEqualTo(before);
    }

    @Test
    void a_closed_league_takes_no_entry() throws Exception {
        long league = league();
        assertThat(post(mvc, LEAGUES + "/" + league + "/close", "").getStatus()).isEqualTo(200);
        long a = agent("fake/model-1");
        long before = seasonRows();

        MockHttpServletResponse r = enter(a, "fake/model-1", league, null);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("LEAGUE_CLOSED");
        assertThat(seasonRows()).isEqualTo(before);
    }

    @Test
    void a_full_league_takes_no_more_entries() throws Exception {
        long league = league("{\"name\": \"Two seats\", \"maxEntries\": 2}");
        long a = agent("fake/model-1");
        long b = agent("fake/model-2");
        long c = agent("fake/model-3");
        assertThat(enter(a, "fake/model-1", league, null).getStatus()).isEqualTo(201);
        assertThat(enter(b, "fake/model-2", league, null).getStatus()).isEqualTo(201);
        long before = seasonRows();

        MockHttpServletResponse r = enter(c, "fake/model-3", league, null);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("LEAGUE_FULL");
        assertThat(seasonRows()).isEqualTo(before);
    }

    @Test
    void the_path_length_is_the_leagues_and_a_different_one_is_refused() throws Exception {
        long league = league("{\"name\": \"Long\", \"pathLength\": 24}");
        long a = agent("fake/model-1");
        long b = agent("fake/model-2");
        long before = seasonRows();

        MockHttpServletResponse wrong = enter(a, "fake/model-1", league, 12);
        assertThat(wrong.getStatus()).isEqualTo(400);
        assertThat(code(wrong)).isEqualTo("INVALID_ARGUMENT");
        assertThat(seasonRows()).isEqualTo(before);

        MockHttpServletResponse omitted = enter(a, "fake/model-1", league, null);
        MockHttpServletResponse equal = enter(b, "fake/model-2", league, 24);
        assertThat(omitted.getStatus()).isEqualTo(201);
        assertThat(json(omitted).get("pathLength").asInt()).isEqualTo(24);
        assertThat(equal.getStatus()).isEqualTo(201);
    }

    @Test
    void a_missing_league_and_a_league_of_another_owner_answer_the_same_404() throws Exception {
        long league = league();
        long a = agent("fake/model-1");
        long before = seasonRows();

        MockHttpServletResponse missing = enter(a, "fake/model-1", league + 1000, null);
        long stranger = jdbc.queryForObject("""
                INSERT INTO member (email, name, password) VALUES ('stranger@example.invalid', '타인', '!') RETURNING id""", Long.class);
        MockHttpServletResponse notOwner = ownerStarts(stranger, league);

        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(notOwner.getStatus()).isEqualTo(404);
        assertThat(code(notOwner)).isEqualTo(code(missing));
        assertThat(json(notOwner).get("detail")).isEqualTo(json(missing).get("detail"));
        assertThat(seasonRows()).isEqualTo(before);
    }

    @Test
    void the_review_of_a_league_season_is_held_until_the_league_closes() throws Exception {
        long league = league();
        long a = agent("fake/model-1");
        long agentSeason = json(enter(a, "fake/model-1", league, null)).get("id").asLong();
        long ownerSeason = json(ownerStarts(OWNER_ID, league)).get("id").asLong();
        em.flush();
        jdbc.update("UPDATE season SET current_turn_no = path_length, status = 'FINISHED' WHERE id IN (?, ?)", agentSeason, ownerSeason);
        em.clear();

        MockHttpServletResponse held = review(ownerSeason);
        assertThat(held.getStatus()).isEqualTo(409);
        assertThat(code(held)).isEqualTo("LEAGUE_OPEN");
        assertThat(held.getContentAsString()).doesNotContain("yearMonth").doesNotContain("\"seed\"");
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, ownerSeason);
        assertThat(held.getContentAsString()).doesNotContain(seed);

        assertThat(post(mvc, LEAGUES + "/" + league + "/close", "").getStatus()).isEqualTo(200);
        MockHttpServletResponse open = review(ownerSeason);
        assertThat(open.getStatus()).as(open.getContentAsString()).isEqualTo(200);
        assertThat(json(open).get("seed").asString()).isEqualTo(seed);
    }

    @Test
    void the_review_hold_does_not_apply_to_a_season_outside_a_league() throws Exception {
        long season = json(ownerStarts(OWNER_ID, null)).get("id").asLong();
        em.flush();
        jdbc.update("UPDATE season SET current_turn_no = path_length, status = 'FINISHED' WHERE id = ?", season);
        em.clear();

        assertThat(review(season).getStatus()).isEqualTo(200);
    }

    @Test
    void a_season_not_yet_finished_is_still_SEASON_NOT_FINISHED_before_the_league_hold() throws Exception {
        long league = league();
        long season = json(ownerStarts(OWNER_ID, league)).get("id").asLong();

        MockHttpServletResponse r = review(season);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("SEASON_NOT_FINISHED");
    }

    private MockHttpServletResponse review(long season) {
        return mvc.get().uri("/api/v1/seasons/" + season + "/review").header("Authorization", "Bearer " + tokens.user(OWNER_ID))
                .requestAttr(ANONYMOUS, true).exchange().getResponse();
    }
}

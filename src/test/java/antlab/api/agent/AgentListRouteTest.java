package antlab.api.agent;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.season.SeasonRepository;
import antlab.api.agentrunner.AgentRunnerSupport;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import antlab.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The public agent list (AG-04 PR D): the owner's agents with raw season counts that follow PLAN section 6.9. Seasons
 * are made by the fixture and moved to each status by SQL, because the runner start refuses a new season while one is
 * paused or abandoning and caps starts per week.
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentListRouteTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    TestTokens tokens;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    private long agent(String persona, String model) throws Exception {
        return AgentRunnerSupport.register(mvc, registerBody(persona, "OPENROUTER", model, "에이전트 " + model));
    }

    private JsonNode list(String bearer) throws Exception {
        em.flush();
        em.clear();
        var request = mvc.get().uri("/api/v1/agents").requestAttr(ANONYMOUS, true);
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse r = request.exchange().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(bearer == null ? 401 : 200);
        return bearer == null ? null : json(r);
    }

    private static JsonNode agentRow(JsonNode body, long agentId) {
        for (JsonNode e : body.get("items")) {
            if (e.get("agentId").asLong() == agentId) {
                return e;
            }
        }
        throw new AssertionError("no agent " + agentId);
    }

    /** A bare season of the agent in the given status; {@code set} is the SQL that makes the row valid for that status. */
    private long season(long agent, String set, Object... args) {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long id = fixture.newSeason(members.findById(agent).orElseThrow()).getId();
        em.flush();
        if (!set.isEmpty()) {
            Object[] all = new Object[args.length + 1];
            System.arraycopy(args, 0, all, 0, args.length);
            all[args.length] = id;
            jdbc.update("UPDATE season SET " + set + " WHERE id = ?", all);
        }
        em.clear();
        return id;
    }

    private void call(long season, int turn, String outcome, boolean isFinal) {
        String sha = "b".repeat(64);
        jdbc.update("""
                INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, request_text, latency_ms,
                                        is_final, outcome, request_sha256)
                VALUES (?, ?, 1, 'TURN_DECISION', 'OK', 'fake/model-1', 'req', 5, ?, ?, ?)""", season, turn, isFinal,
                outcome, isFinal ? sha : null);
    }

    @Test
    void the_counts_follow_the_status_rules_and_an_overdue_pause_is_an_unfinished_abandon() throws Exception {
        long a = agent("value-investor", "fake/model-1");
        long config = json(post(mvc, AGENTS + "/" + a + "/seasons", START_BODY)).get("id").asLong();
        season(a, "status = 'FINISHED', current_turn_no = path_length");
        season(a, "status = 'FINISHED', current_turn_no = path_length");
        season(a, "status = 'VOID', void_reason = 'MODEL_GONE', void_note = 'gone'");
        season(a, "status = 'ABANDONING', abandoned_turn_no = 0");
        season(a, "status = 'PAUSED', paused_at = now() - interval '7 days'");
        season(a, "status = 'PAUSED', paused_at = now() - interval '30 days'");
        season(a, "status = 'PAUSED', paused_at = now() - interval '6 days 23 hours'");
        season(a, "status = 'HALTED'");
        season(a, "");
        em.flush();

        JsonNode row = agentRow(list(tokens.user(OWNER_ID)), a);

        assertThat(row.get("startedSeasons").asInt()).isEqualTo(10);
        assertThat(row.get("countedSeasons").asInt()).isEqualTo(2);
        assertThat(row.get("voidSeasons").asInt()).isEqualTo(1);
        assertThat(row.get("unfinishedAbandonSeasons").asInt()).as("stored ABANDONING and two overdue pauses").isEqualTo(3);
        assertThat(row.get("pausedSeasons").asInt()).as("only the pause under 7 days").isEqualTo(1);
        assertThat(row.get("haltedSeasons").asInt()).isEqualTo(1);
        assertThat(row.get("inProgressSeasons").asInt()).as("the started one and the bare one").isEqualTo(2);
        assertThat(config).isPositive();
    }

    @Test
    void the_failed_turn_count_is_the_final_calls_without_a_decision() throws Exception {
        long a = agent("value-investor", "fake/model-1");
        long b = agent("value-investor", "fake/model-2");
        long sa = json(post(mvc, AGENTS + "/" + a + "/seasons", START_BODY)).get("id").asLong();
        long sb = json(post(mvc, AGENTS + "/" + b + "/seasons", START_BODY.replace("fake/model-1", "fake/model-2")))
                .get("id").asLong();
        em.flush();
        call(sa, 1, "NO_RESPONSE", true);
        call(sa, 2, "INVALID", true);
        call(sa, 3, "DECISION", true);
        jdbc.update("""
                INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, request_text, latency_ms)
                VALUES (?, 4, 1, 'TURN_DECISION', 'ERROR', 'fake/model-1', 'req', 5)""", sa);
        call(sb, 1, "NO_RESPONSE", true);

        JsonNode body = list(tokens.user(OWNER_ID));

        assertThat(agentRow(body, a).get("failedTurnCount").asInt()).as("an attempt that is not final is not a failed turn")
                .isEqualTo(2);
        assertThat(agentRow(body, b).get("failedTurnCount").asInt()).isEqualTo(1);
    }

    @Test
    void an_agent_without_seasons_shows_zeros_and_no_latest_persona_and_the_row_has_no_score_field() throws Exception {
        long idle = agent("quiet-persona", "fake/model-9");

        JsonNode body = list(tokens.user(OWNER_ID));
        JsonNode row = agentRow(body, idle);

        assertThat(row.propertyNames()).containsExactly("agentId", "displayName", "personaKey", "modelRoute", "modelId", "status",
                "latestPersonaVersion", "startedSeasons", "countedSeasons", "voidSeasons", "unfinishedAbandonSeasons",
                "inProgressSeasons", "pausedSeasons", "haltedSeasons", "failedTurnCount");
        assertThat(row.get("personaKey").asString()).isEqualTo("quiet-persona");
        assertThat(row.get("modelRoute").asString()).isEqualTo("OPENROUTER");
        assertThat(row.get("modelId").asString()).isEqualTo("fake/model-9");
        assertThat(row.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(row.get("latestPersonaVersion").isNull()).isTrue();
        for (String key : new String[] {"startedSeasons", "countedSeasons", "voidSeasons", "unfinishedAbandonSeasons",
                "inProgressSeasons", "pausedSeasons", "haltedSeasons", "failedTurnCount"}) {
            assertThat(row.get(key).asInt()).as(key).isZero();
        }
    }

    @Test
    void the_latest_persona_version_is_the_newest_recorded_for_the_key() throws Exception {
        long a = agent("value-investor", "fake/model-1");
        json(post(mvc, AGENTS + "/" + a + "/seasons", START_BODY));
        em.flush();
        jdbc.update("INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES ('value-investor', 2, ?, 'Value')",
                "c".repeat(64));

        assertThat(agentRow(list(tokens.user(OWNER_ID)), a).get("latestPersonaVersion").asInt()).isEqualTo(2);
    }

    @Test
    void another_member_sees_none_of_the_owners_agents_and_a_missing_token_is_401() throws Exception {
        long a = agent("value-investor", "fake/model-1");
        long stranger = jdbc.queryForObject("""
                INSERT INTO member (email, name, password) VALUES ('list-stranger@example.invalid', '타인', '!') RETURNING id""",
                Long.class);

        JsonNode own = list(tokens.user(OWNER_ID));
        JsonNode other = list(tokens.user(stranger));
        list(null);

        assertThat(agentRow(own, a)).isNotNull();
        assertThat(other.get("items")).isEmpty();
    }

    @Test
    void the_agents_come_oldest_first() throws Exception {
        long first = agent("value-investor", "fake/model-1");
        long second = agent("value-investor", "fake/model-2");

        JsonNode items = list(tokens.user(OWNER_ID)).get("items");

        assertThat(items.get(0).get("agentId").asLong()).isEqualTo(first);
        assertThat(items.get(1).get("agentId").asLong()).isEqualTo(second);
    }
}

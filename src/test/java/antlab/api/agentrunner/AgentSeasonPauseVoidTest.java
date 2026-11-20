package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.auth.AgentActingAuthentication;
import antlab.scoring.ScoreInputQuery;
import antlab.season.SeasonDto;
import antlab.season.SeasonQueryService;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * AG-02 A5 through the runner routes: pause, resume, void and abandon of an agent season, what a paused or void season
 * refuses, the pause that blocks a new season, the 7-day rule applied from the clock, and the VOID season left out of
 * the season limit and of scoring. The 7-day boundary to the nanosecond is in AgentSeasonLifecycleServiceTest; here the
 * stored pause time is moved back by whole hours.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentSeasonPauseVoidTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ScoreInputQuery scoreInputs;
    @Autowired
    SeasonQueryService queries;

    private long agent;

    @BeforeEach
    void owner() throws Exception {
        ensureOwner(jdbc);
        agent = AgentRunnerSupport.register(mvc, registerBody("persona-pause", "OPENROUTER", "fake/model-1", "에이전트"));
    }

    @AfterEach
    void cleanUp() {
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
    }

    private String seasons() {
        return AGENTS + "/" + agent + "/seasons";
    }

    private long start() throws Exception {
        MockHttpServletResponse r = post(mvc, seasons(), START_BODY);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private MockHttpServletResponse act(long season, String op, String body) {
        return post(mvc, seasons() + "/" + season + "/" + op, body);
    }

    private static String code(MockHttpServletResponse r) throws Exception {
        return json(r).get("code").asText();
    }

    private String status(long season) {
        return jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season);
    }

    private static String flushBody(int attemptNo) {
        return "{\"calls\": [{\"attemptNo\": " + attemptNo + ", \"kind\": \"TURN_DECISION\", \"ledgerCallId\": \"" + UUID.randomUUID()
                + "\", \"status\": \"TIMEOUT\", \"modelRequested\": \"fake/model-1\", \"requestText\": \"r\","
                + " \"responseText\": \"\", \"latencyMs\": 12}]}";
    }

    @Test
    void aPausedSeasonKeepsItsTurnRefusesDecisionWorkAndBlocksANewSeasonUntilItResumes() throws Exception {
        long season = start();
        assertThat(post(mvc, seasons() + "/" + season + "/turns/0/calls", flushBody(1)).getStatus()).isEqualTo(204);

        MockHttpServletResponse paused = act(season, "pause", "");

        assertThat(paused.getStatus()).isEqualTo(200);
        assertThat(json(paused).get("status").asText()).isEqualTo("PAUSED");
        assertThat(json(paused).get("currentTurnNo").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT paused_at IS NOT NULL FROM season WHERE id = ?", Boolean.class, season))
                .isTrue();
        assertThat(act(season, "pause", "").getStatus()).as("a repeat answers the same").isEqualTo(200);

        MockHttpServletResponse flush = post(mvc, seasons() + "/" + season + "/turns/0/calls", flushBody(2));
        assertThat(flush.getStatus()).isEqualTo(409);
        assertThat(code(flush)).isEqualTo("SEASON_NOT_ACTIVE");
        MockHttpServletResponse advance = post(mvc, seasons() + "/" + season + "/turns", "{\"expectedTurnNo\": 0}");
        assertThat(advance.getStatus()).isEqualTo(409);
        assertThat(code(advance)).isEqualTo("SEASON_NOT_ACTIVE");
        MockHttpServletResponse second = post(mvc, seasons(), START_BODY);
        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("SEASON_LIMIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season WHERE member_id = ?", Long.class, agent))
                .as("the refused start made no season").isEqualTo(1);

        MockHttpServletResponse resumed = act(season, "resume", "");

        assertThat(resumed.getStatus()).isEqualTo(200);
        assertThat(json(resumed).get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT paused_at IS NULL FROM season WHERE id = ?", Boolean.class, season))
                .isTrue();
        assertThat(post(mvc, seasons() + "/" + season + "/turns/0/calls", flushBody(2)).getStatus()).isEqualTo(204);
        assertThat(post(mvc, seasons(), START_BODY).getStatus()).as("no pause is live now").isEqualTo(201);
    }

    @Test
    void aPauseResumedAtSixDaysTwentyThreeHoursResumesAndOneAtEightDaysBecomesAnAbandonThatFinishes() throws Exception {
        long near = start();
        act(near, "pause", "");
        jdbc.update("UPDATE season SET paused_at = now() - interval '6 days 23 hours' WHERE id = ?", near);
        assertThat(json(act(near, "resume", "")).get("status").asText()).isEqualTo("IN_PROGRESS");

        act(near, "pause", "");
        jdbc.update("UPDATE season SET paused_at = now() - interval '8 days' WHERE id = ?", near);

        MockHttpServletResponse late = act(near, "resume", "");

        assertThat(late.getStatus()).isEqualTo(200);
        assertThat(json(late).get("status").asText()).as("not resumed: an abandon").isEqualTo("ABANDONING");
        assertThat(json(late).get("abandonedTurnNo").asInt()).isZero();
        assertThat(json(late).get("notStarted").asBoolean()).isFalse();

        MockHttpServletResponse done = act(near, "abandon", "{\"expectedTurnNo\": 0}");
        assertThat(done.getStatus()).isEqualTo(200);
        assertThat(json(done).get("status").asText()).isEqualTo("FINISHED");
        assertThat(status(near)).isEqualTo("FINISHED");
    }

    @Test
    void anOverduePauseStillBlocksANewSeasonUntilItIsAbandonedAndFinished() throws Exception {
        long old = start();
        act(old, "pause", "");
        jdbc.update("UPDATE season SET paused_at = now() - interval '7 days 1 hour' WHERE id = ?", old);

        MockHttpServletResponse blocked = post(mvc, seasons(), START_BODY);
        assertThat(blocked.getStatus()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("SEASON_LIMIT");
        assertThat(status(old)).as("the start does not convert the pause").isEqualTo("PAUSED");

        assertThat(json(act(old, "abandon", "{\"expectedTurnNo\": 0}")).get("status").asText()).isEqualTo("FINISHED");
        assertThat(post(mvc, seasons(), START_BODY).getStatus()).as("the old season is finished and counted")
                .isEqualTo(201);
    }

    @Test
    void anAbandoningSeasonBlocksANewSeason() throws Exception {
        long old = start();
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", old);

        MockHttpServletResponse blocked = post(mvc, seasons(), START_BODY);

        assertThat(blocked.getStatus()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("SEASON_LIMIT");
    }

    @Test
    void aPauseOverSevenDaysCannotBeVoidedOutOfTheScoreButOneUnderSevenDaysCan() throws Exception {
        long over = start();
        long near = start();
        long abandoning = start();
        act(over, "pause", "");
        act(near, "pause", "");
        jdbc.update("UPDATE season SET paused_at = now() - interval '7 days 1 minute' WHERE id = ?", over);
        jdbc.update("UPDATE season SET paused_at = now() - interval '6 days 23 hours' WHERE id = ?", near);
        String body = "{\"reason\": \"MODEL_GONE\", \"note\": \"gone\"}";

        MockHttpServletResponse refused = act(over, "void", body);
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(status(over)).as("the refusal rolls the conversion back").isEqualTo("PAUSED");
        assertThat(act(near, "void", body).getStatus()).isEqualTo(200);
        assertThat(status(near)).isEqualTo("VOID");

        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", abandoning);
        assertThat(act(abandoning, "void", body).getStatus()).isEqualTo(409);
        jdbc.update("UPDATE season SET status = 'HALTED', abandoned_turn_no = NULL WHERE id = ?", abandoning);
        assertThat(act(abandoning, "void", body).getStatus()).as("a halted season stays voidable").isEqualTo(200);
    }

    @Test
    void aPausedSeasonCanBeAbandonedDirectlyAndAnExpiredOneToo() throws Exception {
        long season = start();
        act(season, "pause", "");

        MockHttpServletResponse done = act(season, "abandon", "{\"expectedTurnNo\": 0}");

        assertThat(done.getStatus()).isEqualTo(200);
        assertThat(json(done).get("status").asText()).isEqualTo("FINISHED");
        assertThat(json(done).get("notStarted").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT paused_at IS NULL FROM season WHERE id = ?", Boolean.class, season)).isTrue();
    }

    @Test
    void voidKeepsTheSeasonWhereItIsRefusesFurtherWorkAndIsLeftOutOfLimitAndScoring() throws Exception {
        long first = start();
        long second = start();
        long third = start();
        MockHttpServletResponse limit = post(mvc, seasons(), START_BODY);
        assertThat(limit.getStatus()).isEqualTo(409);
        assertThat(code(limit)).isEqualTo("SEASON_LIMIT");

        MockHttpServletResponse voided = act(first, "void", "{\"reason\": \"MODEL_GONE\", \"note\": \"model id removed\"}");

        assertThat(voided.getStatus()).isEqualTo(200);
        JsonNode body = json(voided);
        assertThat(body.get("status").asText()).isEqualTo("VOID");
        assertThat(body.get("currentTurnNo").asInt()).isZero();
        assertThat(jdbc.queryForMap("SELECT void_reason, void_note FROM season WHERE id = ?", first))
                .containsEntry("void_reason", "MODEL_GONE").containsEntry("void_note", "model id removed");
        assertThat(act(first, "void", "{\"reason\": \"MODEL_GONE\", \"note\": \"again\"}").getStatus())
                .as("a repeat with the same reason").isEqualTo(200);
        MockHttpServletResponse other = act(first, "void", "{\"reason\": \"ENGINE_DEFECT\", \"note\": \"x\"}");
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(code(other)).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(code(act(first, "pause", ""))).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(code(act(first, "resume", ""))).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(code(act(first, "abandon", "{\"expectedTurnNo\": 0}"))).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(code(post(mvc, seasons() + "/" + first + "/turns", "{\"expectedTurnNo\": 0}")))
                .isEqualTo("SEASON_NOT_ACTIVE");

        assertThat(post(mvc, seasons(), START_BODY).getStatus()).as("a void season is not in progress").isEqualTo(201);
        assertThat(scoreInputs.unscoredFinished(agent)).doesNotContain(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_score WHERE season_id = ?", Long.class, first)).isZero();
        assertThat(status(second)).isEqualTo("IN_PROGRESS");
        assertThat(status(third)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void voidNeedsAReasonAndANonBlankNoteAndAFinishedSeasonCannotBeVoided() throws Exception {
        long season = start();

        assertThat(act(season, "void", "{\"note\": \"x\"}").getStatus()).isEqualTo(400);
        assertThat(act(season, "void", "{\"reason\": \"MODEL_GONE\"}").getStatus()).isEqualTo(400);
        assertThat(act(season, "void", "{\"reason\": \"MODEL_GONE\", \"note\": \"   \"}").getStatus()).isEqualTo(400);
        assertThat(act(season, "void", "{\"reason\": \"BORED\", \"note\": \"x\"}").getStatus()).isEqualTo(400);
        assertThat(act(season, "void", "{\"reason\": \"MODEL_GONE\", \"note\": \"" + "x".repeat(501) + "\"}").getStatus())
                .isEqualTo(400);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");

        assertThat(act(season, "abandon", "{\"expectedTurnNo\": 0}").getStatus()).isEqualTo(200);
        MockHttpServletResponse finished = act(season, "void", "{\"reason\": \"MODEL_GONE\", \"note\": \"x\"}");
        assertThat(finished.getStatus()).isEqualTo(409);
        assertThat(code(finished)).isEqualTo("SEASON_NOT_ACTIVE");
    }

    @Test
    void theNewRoutesAnswerNotFoundForAnotherAgentsSeason() throws Exception {
        long season = start();
        long other = AgentRunnerSupport.register(mvc, registerBody("persona-other", "OPENROUTER", "fake/model-1", "다른"));
        String foreign = AGENTS + "/" + other + "/seasons/" + season + "/";

        assertThat(post(mvc, foreign + "pause", "").getStatus()).isEqualTo(404);
        assertThat(post(mvc, foreign + "resume", "").getStatus()).isEqualTo(404);
        assertThat(post(mvc, foreign + "void", "{\"reason\": \"MODEL_GONE\", \"note\": \"x\"}").getStatus()).isEqualTo(404);
        assertThat(post(mvc, foreign + "abandon", "{\"expectedTurnNo\": 0}").getStatus()).isEqualTo(404);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
    }

    private <T> T acting(Supplier<T> call) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void readsShowAPausePastSevenDaysAsAnAbandonWithoutChangingTheStoredRow() throws Exception {
        long near = start();
        long over = start();
        act(near, "pause", "");
        act(over, "pause", "");
        jdbc.update("UPDATE season SET paused_at = now() - interval '6 days 23 hours' WHERE id = ?", near);
        jdbc.update("UPDATE season SET paused_at = now() - interval '7 days 1 minute' WHERE id = ?", over);

        SeasonDto nearRead = acting(() -> queries.get(near));
        SeasonDto overRead = acting(() -> queries.get(over));
        List<SeasonDto> listed = acting(() -> queries.list()).items();

        assertThat(nearRead.status().name()).isEqualTo("PAUSED");
        assertThat(nearRead.abandonedTurnNo()).isNull();
        assertThat(overRead.status().name()).isEqualTo("ABANDONING");
        assertThat(overRead.abandonedTurnNo()).isZero();
        assertThat(overRead.notStarted()).isFalse();
        assertThat(listed).extracting(d -> d.id() + ":" + d.status().name())
                .containsExactlyInAnyOrder(near + ":PAUSED", over + ":ABANDONING");
        assertThat(status(over)).as("only the agent's next action converts the stored row").isEqualTo("PAUSED");
    }

    @Test
    void theObservationViewsCountAPausePastSevenDaysAsAnAbandon() throws Exception {
        long near = start();
        long over = start();
        act(near, "pause", "");
        act(over, "pause", "");
        jdbc.update("UPDATE season SET paused_at = now() - interval '6 days 23 hours' WHERE id = ?", near);
        jdbc.update("UPDATE season SET paused_at = now() - interval '7 days 1 minute' WHERE id = ?", over);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, season_count FROM obs.season_summary WHERE kind = 'AGENT' ORDER BY status");
        assertThat(rows).extracting(r -> r.get("status") + ":" + r.get("season_count"))
                .containsExactly("ABANDONING:1", "PAUSED:1");
        assertThat(jdbc.queryForObject("SELECT status FROM obs.agent_season WHERE season_id = ?", String.class, over))
                .isEqualTo("ABANDONING");
        assertThat(jdbc.queryForObject("SELECT status FROM obs.agent_season WHERE season_id = ?", String.class, near))
                .isEqualTo("PAUSED");
    }

    @Test
    void scoreInputsCountOnlyFinishedSeasonsEvenWhenAVoidSeasonHasEveryTurn() throws Exception {
        long finished = start();
        long other = start();
        assertThat(act(finished, "abandon", "{\"expectedTurnNo\": 0}").getStatus()).isEqualTo(200);
        assertThat(act(other, "abandon", "{\"expectedTurnNo\": 0}").getStatus()).isEqualTo(200);
        assertThat(scoreInputs.unscoredFinished(agent)).contains(finished, other);

        jdbc.update("UPDATE season SET status = 'VOID', void_reason = 'MODEL_GONE', void_note = 'x' WHERE id = ?", other);

        assertThat(scoreInputs.unscoredFinished(agent)).contains(finished).doesNotContain(other);
    }
}

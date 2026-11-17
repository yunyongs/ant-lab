package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.support.EnginePathAnswers.body;
import static antlab.support.EnginePathAnswers.digestOf;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.SimpleBootstrap;
import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** The agent's season start goes through the same service, so the engine path source and its 503 apply to it too. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentRunnerSeasonStartEngineTest {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final String M2 = "m2-sb6-v1";
    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.path-source", () -> "engine");
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
    }

    @AfterAll
    static void stop() {
        ENGINE.close();
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    MonthBlockRepository repository;

    @BeforeEach
    void owner() {
        ENGINE.reset();
        ensureOwner(jdbc);
    }

    private MockHttpServletResponse start() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        return post(mvc, AGENTS + "/" + agent + "/seasons", START_BODY);
    }

    @Test
    void 엔진_경로로_에이전트_시즌이_시작된다() throws Exception {
        List<ReplayMonth> replayable = repository.findReplayMonths(SimpleBootstrap.REPLAY_START, SimpleBootstrap.replayUpperBound(BASE));
        List<LocalDate> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(LocalDate.of(2008, 5, 1).plusMonths(i));
        }
        ENGINE.respond("/v1/paths", 200, "application/json", body(M2, months, digestOf(M2, replayable, BASE)));

        MockHttpServletResponse r = start();

        assertThat(r.getStatus()).isEqualTo(201);
        long id = json(r).get("id").asLong();
        em.flush();
        assertThat(jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, id)).isEqualTo(M2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_path WHERE season_id = ?", Long.class, id)).isEqualTo(12);
    }

    @Test
    void 엔진이_바쁘면_러너도_503_ENGINE_UNAVAILABLE을_받는다() throws Exception {
        ENGINE.respondFixture("/v1/paths", 503, "problem-engine-busy.json");

        MockHttpServletResponse r = start();

        assertThat(r.getStatus()).isEqualTo(503);
        assertThat(json(r).get("code").asString()).isEqualTo("ENGINE_UNAVAILABLE");
        assertThat(r.getContentAsString()).doesNotContain("busy");
    }

    @Test
    void 엔진의_422는_러너에게도_PATH_TOO_LONG이다() throws Exception {
        ENGINE.respondFixture("/v1/paths", 422, "problem-path-too-long.json");

        MockHttpServletResponse r = start();

        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(json(r).get("code").asString()).isEqualTo("PATH_TOO_LONG");
    }
}

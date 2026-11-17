package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.post;
import static antlab.support.EnginePathAnswers.body;
import static antlab.support.EnginePathAnswers.digestOf;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.BootstrapPathSource;
import antlab.engine.PathRequest;
import antlab.engine.SimpleBootstrap;
import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * With the engine on {@code p0-boot6-v1} and a fixed seed, an engine that answers the months the in-process bootstrap
 * draws gives the very same {@code season_path} rows as the default {@code java} source would.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonStartEngineP0Test {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final String P0 = "p0-boot6-v1";
    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);
    private static final long SEED = 20_261_117L;
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();

    @TestConfiguration
    static class FixedSeed {
        @Bean
        @Primary
        antlab.season.SeedGenerator fixedSeed() {
            return () -> SEED;
        }
    }

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.path-source", () -> "engine");
        registry.add("antlab.engine.path-version", () -> P0);
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
    JsonMapper mapper;

    @Autowired
    MonthBlockRepository repository;

    @Test
    void 엔진이_자바와_같은_달을_말하면_자바_출처와_같은_season_path가_저장된다() throws Exception {
        BootstrapPathSource java = new BootstrapPathSource(repository);
        List<Long> expectedIds = java.generate(new PathRequest(BASE, 12, SEED)).monthBlockIds();
        List<ReplayMonth> replayable = repository.findReplayMonths(SimpleBootstrap.REPLAY_START, SimpleBootstrap.replayUpperBound(BASE));
        List<LocalDate> months = expectedIds.stream()
                .map(id -> replayable.stream().filter(m -> m.monthBlockId().equals(id)).findFirst().orElseThrow().monthStart()).toList();
        ENGINE.respond("/v1/paths", 200, "application/json", body(P0, months, digestOf(P0, replayable, BASE)));

        MockHttpServletResponse r = post(mvc, VALID_BODY);

        assertThat(r.getStatus()).isEqualTo(201);
        long id = mapper.readTree(r.getContentAsString()).get("id").asLong();
        assertThat(jdbc.queryForList("SELECT month_block_id FROM season_path WHERE season_id = ? ORDER BY turn_no", Long.class, id))
                .isEqualTo(expectedIds);
        assertThat(jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, id)).isEqualTo(P0);
        assertThat(ENGINE.requests().get(0).body()).contains("\"seed\":\"" + SEED + "\"", "\"engineVersion\":\"p0-boot6-v1\"");
    }
}

package antlab.regime;

import static antlab.regime.RegimeFixtures.PATH;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.ProcessExit;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The one-shot runner: absent without its property, and with it every outcome becomes an exit code and a log line without months or values. */
class RegimeLabelRunOnceRunnerTest {

    static final List<Integer> EXIT_CODES = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingExit {
        @Bean
        ProcessExit processExit() {
            return EXIT_CODES::add;
        }
    }

    @Nested
    @IntegrationTest
    class Default {

        @Autowired
        ApplicationContext context;

        @Test
        void withoutThePropertyThereIsNoRunnerAndNothingRunsAtStartup() {
            assertThat(context.getBeanNamesForType(RegimeLabelRunOnceRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(RegimeLabelJob.class)).hasSize(1);
        }

        @Test
        void anyOtherValueOfThePropertyIsNoSwitchEither() {
            var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(RegimeLabelRunOnceRunner.class)
                    .withBean(RegimeLabelJob.class, () -> org.mockito.Mockito.mock(RegimeLabelJob.class));
            runner.withPropertyValues("antlab.regime.run-once=LABEL_MONTHS")
                    .run(c -> assertThat(c.getBeanNamesForType(RegimeLabelRunOnceRunner.class)).hasSize(1));
            runner.withPropertyValues("antlab.regime.run-once=OTHER")
                    .run(c -> assertThat(c.getBeanNamesForType(RegimeLabelRunOnceRunner.class)).isEmpty());
            runner.withPropertyValues("antlab.regime.run-once=")
                    .run(c -> assertThat(c.getBeanNamesForType(RegimeLabelRunOnceRunner.class)).isEmpty());
        }
    }

    abstract static class Switched {

        @Autowired
        RegimeLabelRunOnceRunner runner;
        @Autowired
        JdbcTemplate jdbc;

        @BeforeEach
        void reset() {
            jdbc.update("DELETE FROM month_block");
            EXIT_CODES.clear();
        }

        @AfterEach
        void clean() {
            jdbc.execute("ALTER TABLE month_block DROP CONSTRAINT IF EXISTS zz_probe_no_p4");
            jdbc.update("DELETE FROM month_block");
        }

        void months(String... yearMonths) {
            for (String month : yearMonths) {
                jdbc.update("INSERT INTO month_block (month_start) VALUES (?::date)", month + "-01");
            }
        }
    }

    @Nested
    @IntegrationTest
    @TestPropertySource(properties = "antlab.regime.run-once=LABEL_MONTHS")
    @Import(RecordingExit.class)
    @ExtendWith(OutputCaptureExtension.class)
    class Enabled extends Switched {

        static final FakeEngineServer ENGINE = FakeEngineServer.start();

        @DynamicPropertySource
        static void engine(DynamicPropertyRegistry registry) {
            registry.add("antlab.engine.url", ENGINE::url);
            registry.add("antlab.engine.token", () -> "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop");
        }

        @AfterAll
        static void stop() {
            ENGINE.close();
        }

        @BeforeEach
        void engineReset() {
            ENGINE.reset();
        }

        @Test
        void aSuccessExitsWithZeroAndLogsCountsOnly(CapturedOutput output) {
            months("1997-01", "1997-02");
            ENGINE.respondFixture(PATH, 200, "regime-response.json");

            runner.run(null);

            assertThat(EXIT_CODES).containsExactly(0);
            assertThat(output.getAll()).contains("regime.labelled rows=2 evaluated=1 filled=1 unchanged=1")
                    .doesNotContain("1997").doesNotContain("0.2731").doesNotContain("0.4125");
            runner.run(null);
            assertThat(EXIT_CODES).containsExactly(0, 0);
            assertThat(output.getAll()).contains("regime.labelled rows=2 evaluated=1 filled=0 unchanged=2");
        }

        @Test
        void anEmptyTableIsASuccess() {
            runner.run(null);

            assertThat(EXIT_CODES).containsExactly(0);
        }

        @Test
        void aStoredValueThatDiffersExitsWithOneAndNamesTheRowAndColumnOnly(CapturedOutput output) {
            months("1997-01", "1997-02");
            jdbc.update("UPDATE month_block SET rate_dir = 'DOWN', rate_strength = 0.5 WHERE month_start = '1997-01-01'");
            ENGINE.respondFixture(PATH, 200, "regime-response.json");

            runner.run(null);

            assertThat(EXIT_CODES).containsExactly(1);
            assertThat(output.getAll()).contains("regime.failed reason=month_block ").contains("holds a different value in rate_dir")
                    .doesNotContain("1997-0").doesNotContain("DOWN").doesNotContain("0.4125");
        }

        @Test
        void anEngineThatSaysNoExitsWithOne(CapturedOutput output) {
            months("1997-01");
            ENGINE.respondFixture(PATH, 503, "problem-history-unavailable.json");

            runner.run(null);

            assertThat(EXIT_CODES).containsExactly(1);
            assertThat(output.getAll()).contains("regime.failed");
        }

        @Test
        void aDatabaseFailureExitsWithOneAndShowsOnlyTheExceptionClass(CapturedOutput output) {
            months("1996-12", "1997-01");
            jdbc.execute("ALTER TABLE month_block ADD CONSTRAINT zz_probe_no_p4 CHECK (regime_label IS DISTINCT FROM 'P4') NOT VALID");
            // both months answered as the fixture's P4 month, which the probe constraint refuses
            ENGINE.respond(PATH, 200, "application/json", RegimeFixtures.edited(n -> {
                ObjectNode first = RegimeFixtures.month(n, 0).deepCopy();
                ObjectNode second = RegimeFixtures.month(n, 0).deepCopy();
                ArrayNode list = (ArrayNode) n.get("months");
                list.removeAll();
                list.add(first.put("month", "1996-12"));
                list.add(second.put("month", "1997-01"));
                ((ObjectNode) n.get("coverage")).put("evaluated", 2).put("noData", 0);
            }).toString());

            runner.run(null);

            assertThat(EXIT_CODES).containsExactly(1);
            assertThat(output.getAll()).contains("regime.failed reason=DataIntegrityViolationException")
                    .doesNotContain("Failing row").doesNotContain("1997-0").doesNotContain("1996-1");
        }
    }

    @Nested
    @IntegrationTest
    @TestPropertySource(properties = {"antlab.regime.run-once=LABEL_MONTHS", "antlab.engine.url=", "antlab.engine.token="})
    @Import(RecordingExit.class)
    @ExtendWith(OutputCaptureExtension.class)
    class EngineNotConfigured extends Switched {

        @Test
        void withoutAnEngineTheRunExitsWithOneAndWritesNothing(CapturedOutput output) {
            months("1997-01");
            List<String> before = jdbc.queryForList("SELECT to_jsonb(t)::text FROM month_block t", String.class);

            runner.run(null);

            assertThat(EXIT_CODES).containsExactly(1);
            assertThat(output.getAll()).contains("engine.not_configured");
            assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM month_block t", String.class)).isEqualTo(before);
        }
    }
}

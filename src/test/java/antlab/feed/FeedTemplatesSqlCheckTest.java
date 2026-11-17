package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenCleanup;
import antlab.support.SharedPostgres;
import antlab.support.GoldenFixtureSource;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScriptStep;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The SQL gate of the sentence rules ({@code feed/check_templates.sql}, last line {@code PASS}) runs in CI on the real files. */
class FeedTemplatesSqlCheckTest {

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;

    @BeforeAll
    static void start() {
        container = SharedPostgres.container();
        harness = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, null);
    }

    @Test
    void check_templates_sql_passes_on_the_real_feed_files() throws Exception {
        for (String file : new String[] {"feed/check_templates.sql", "feed/kinds.csv", "feed/variables.csv", "feed/templates.csv",
                "feed/denylist.csv"}) {
            harness.runner().putFile(file, Path.of(file));
        }
        DatabaseHandle db = harness.createSqlDatabase("feed_check");
        SqlGoldenHarness.ScriptResult result = harness.run(db,
                new ScriptStep("feed/check_templates.sql", Kind.CHECK, "p0_13_check", Duration.ofMinutes(2)));
        harness.assertCheckPassed(result, "p0_13_check");
        assertThat(result.process().exitCode()).isZero();
    }
}

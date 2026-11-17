package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenCleanup;
import antlab.support.SharedPostgres;
import antlab.support.GoldenFixtureSource;
import antlab.support.PsqlScriptRunner;
import antlab.support.RowSet;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScriptStep;
import antlab.support.SyntheticHistory;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real {@code db/13_calc_attention.sql} runs on a synthetic history (no sample CSV) in a disposable PostgreSQL 17, and every row it
 * stores must equal {@link AttentionCalculator}. Always on, so CI proves SQL = Java on purpose-built edge cases.
 */
class AttentionCalculatorSqlParityTest {

    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(2);

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static DatabaseHandle db;
    static SyntheticHistory history;

    @BeforeAll
    static void start() throws Exception {
        container = SharedPostgres.container();
        harness = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
        PsqlScriptRunner runner = harness.runner();
        for (String file : new String[] {"db/01_schema_history.sql", "db/13_calc_attention.sql", "db/13_calc_attention_def.sql"}) {
            runner.putFile(file, Path.of(file));
        }
        history = SyntheticHistory.standard();
        runner.putFile("synthetic/history.sql", history.insertScript());
        db = harness.createSqlDatabase("attention_parity");
        for (String file : new String[] {"db/01_schema_history.sql", "synthetic/history.sql", "db/13_calc_attention.sql"}) {
            SqlGoldenHarness.ScriptResult result = harness.run(db, new ScriptStep(file, Kind.RUN, null, STEP_TIMEOUT));
            assertThat(result.process().exitCode()).as(file).isZero();
        }
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, null);
    }

    @Test
    void 합성_이력에서_SQL_저장값과_Java_계산이_모든_행에서_같다() throws Exception {
        RowSet returns = harness.readRows(db, AttentionParity.RETURNS, 0L);
        assertThat(returns.size()).as("rows loaded").isEqualTo(history.returnRowCount());
        assertThat(AttentionParity.differingAssets(history.returns(), AttentionParity.toReturns(returns)))
                .as("assets whose loaded inputs differ from the fixture (keys only)").isEmpty();

        AttentionParity.Coverage coverage = AttentionParity.compare(AttentionParity.toReturns(returns),
                harness.readRows(db, AttentionParity.STORED, 0L));

        System.out.println("[sql-parity] attention synthetic " + coverage);
        assertThat(coverage.nullByWindow()).as("window under 12").isPositive();
        assertThat(coverage.nullByFloor()).as("deviation under the floor").isPositive();
        assertThat(coverage.clippedPlus()).as("clipped at +3").isPositive();
        assertThat(coverage.clippedMinus()).as("clipped at -3").isPositive();
        assertThat(coverage.segmentStarts()).as("segment starts").isGreaterThan(history.returns().size());
        assertThat(coverage.longestStreak()).as("streak of 6 or more").isGreaterThanOrEqualTo(6);
        assertThat(coverage.window11()).as("11-month window").isTrue();
        assertThat(coverage.window12()).as("12-month window").isTrue();
        assertThat(coverage.rows() - coverage.nullByWindow() - coverage.nullByFloor()).as("computed z rows").isPositive();
    }
}

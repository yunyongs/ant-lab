package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenCleanup;
import antlab.support.GoldenFixtureSource;
import antlab.support.RowSet;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Attention golden on the local sample: schema 01–03, sample load 10, then the real {@code db/13} calculation; every stored
 * {@code asset_month_attention} row must equal {@link AttentionCalculator} fed from the raw return rows. Skipped when the untracked sample
 * CSVs are absent (the synthetic {@link AttentionCalculatorSqlParityTest} runs everywhere). Prints counts only.
 */
@Tag("sql-golden")
class AttentionCalculatorGoldenTest {

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static GoldenFixtureSource.FixtureAvailability availability;

    @BeforeAll
    static void start() {
        GoldenFixtureSource source = GoldenFixtureSource.fromEnvironment();
        availability = source.inspect();
        if (availability.explicit()) {
            availability.requireOrSkip();
        }
        if (!availability.isComplete()) {
            System.out.println("[sql-golden] 건너뜀 — " + availability.skipMessage());
            return;
        }
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, source);
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void 표본_전_행의_관심_지수가_SQL_저장값과_같다() throws Exception {
        availability.requireOrSkip();
        harness.stageInputs();
        DatabaseHandle db = harness.createSqlDatabase("attention_baseline");
        harness.execute(db, SqlGoldenScenario.attentionBaseline());

        RowSet returns = harness.readRows(db, AttentionParity.RETURNS, 0L);
        RowSet stored = harness.readRows(db, AttentionParity.STORED, 0L);
        AttentionParity.Coverage coverage = AttentionParity.compare(AttentionParity.toReturns(returns), stored);

        System.out.println("[sql-golden] 관심 지수 대조 — " + coverage);
        assertThat(coverage.rows()).as("compared rows").isEqualTo(returns.size()).isPositive();
        assertThat(coverage.rows() - coverage.nullByWindow() - coverage.nullByFloor()).as("computed z rows").isPositive();
    }
}

package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenCleanup;
import antlab.support.SharedPostgres;
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
 * Base-date position golden on the local sample: schema 01–03, sample load 10, then the real {@code db/12} calculation. The calculator is fed
 * from the raw tables ({@code asset}, {@code month_block}, {@code asset_month_return}, {@code fx_month}) and must reproduce all eight stored
 * columns of every {@code asset_position} row, and the {@code pos_judge} reason of every unjudgeable row. Unlike
 * {@link PositionMeterBaseDateGoldenTest} it does not read the SQL {@code pos_series} view, so the window, market and FX logic are tested.
 * Skipped when the untracked sample CSVs are absent (the synthetic {@link BaseDatePositionSqlParityTest} runs everywhere). Prints counts only.
 */
@Tag("sql-golden")
class BaseDatePositionGoldenTest {

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
        container = SharedPostgres.container();
        harness = new SqlGoldenHarness(container, source);
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, null);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void 기준일_풀_전_행의_판정이_원본_표에서_다시_만든_Java_값과_같다() throws Exception {
        availability.requireOrSkip();
        harness.stageInputs();
        DatabaseHandle db = harness.createSqlDatabase("position_base_date_raw");
        harness.execute(db, SqlGoldenScenario.positionBaseDate());

        PositionParity.Inputs inputs = PositionParity.inputs(harness.readRows(db, PositionParity.ASSETS, 0L),
                harness.readRows(db, PositionParity.BLOCKS, 0L), harness.readRows(db, PositionParity.RETURNS, 0L),
                harness.readRows(db, PositionParity.FX, 0L));
        RowSet stored = harness.readRows(db, PositionParity.STORED, 0L);
        RowSet reasons = harness.queryWithDefinitions(db, PositionParity.DEFINITION, PositionParity.REASONS, 0L);

        RowSet marketMdd = harness.queryWithDefinitions(db, PositionParity.DEFINITION, PositionParity.MARKET_MDD, 0L);

        PositionParity.Coverage coverage = PositionParity.compare(inputs, stored, reasons, marketMdd);

        System.out.println("[sql-golden] 기준일 판정 원본 대조 — " + coverage);
        assertThat(coverage.judged() + coverage.unjudged()).as("compared rows").isEqualTo(stored.size()).isPositive();
        assertThat(coverage.judged()).as("judged rows").isPositive();
    }
}

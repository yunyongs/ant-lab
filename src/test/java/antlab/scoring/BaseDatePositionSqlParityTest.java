package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.GoldenCleanup;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real {@code db/12_calc_position.sql} runs on a synthetic history (no sample CSV) in a disposable PostgreSQL 17, and every stored
 * {@code asset_position} judgement and every reason must equal {@link BaseDatePositionCalculator}. Always on, so CI proves SQL = Java on
 * purpose-built edge cases. A second database with one missing in-window FX row proves that both sides stop.
 */
class BaseDatePositionSqlParityTest {

    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(2);
    private static final String[] SQL_FILES = {"db/01_schema_history.sql", "db/12_calc_position.sql", "db/12_calc_position_def.sql"};

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;

    @BeforeAll
    static void start() throws Exception {
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
        for (String file : SQL_FILES) {
            harness.runner().putFile(file, Path.of(file));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }

    private static DatabaseHandle load(SyntheticHistory history, String name) throws Exception {
        PsqlScriptRunner runner = harness.runner();
        runner.putFile("synthetic/" + name + "_history.sql", history.insertScript());
        runner.putFile("synthetic/" + name + "_positions.sql", history.positionScript());
        DatabaseHandle db = harness.createSqlDatabase(name);
        for (String file : new String[] {"db/01_schema_history.sql", "synthetic/" + name + "_history.sql",
                "synthetic/" + name + "_positions.sql"}) {
            assertThat(harness.run(db, new ScriptStep(file, Kind.RUN, null, STEP_TIMEOUT)).process().exitCode()).as(file).isZero();
        }
        return db;
    }

    private static PositionParity.Inputs inputs(DatabaseHandle db) throws Exception {
        return PositionParity.inputs(harness.readRows(db, PositionParity.ASSETS, 0L), harness.readRows(db, PositionParity.BLOCKS, 0L),
                harness.readRows(db, PositionParity.RETURNS, 0L), harness.readRows(db, PositionParity.FX, 0L));
    }

    @Test
    void P_S1_합성_이력에서_SQL_저장값과_Java_판정이_모든_행과_사유에서_같다() throws Exception {
        SyntheticHistory history = SyntheticHistory.positions();
        DatabaseHandle db = load(history, "position_parity");
        SqlGoldenHarness.ScriptResult run = harness.run(db, new ScriptStep("db/12_calc_position.sql", Kind.RUN, null, STEP_TIMEOUT));
        assertThat(run.process().exitCode()).as("db/12_calc_position.sql").isZero();

        RowSet stored = harness.readRows(db, PositionParity.STORED, 0L);
        RowSet reasons = harness.queryWithDefinitions(db, PositionParity.DEFINITION, PositionParity.REASONS, 0L);
        int expectedRows = history.pools().values().stream().mapToInt(java.util.List::size).sum();
        assertThat(stored.size()).as("asset_position rows").isEqualTo(expectedRows);

        RowSet marketMdd = harness.queryWithDefinitions(db, PositionParity.DEFINITION, PositionParity.MARKET_MDD, 0L);
        PositionParity.Coverage coverage = PositionParity.compare(inputs(db), stored, reasons, marketMdd);

        System.out.println("[sql-parity] position synthetic " + coverage);
        assertThat(coverage.judgedByDate().keySet()).as("base dates with a judged row")
                .containsExactlyInAnyOrderElementsOf(history.pools().keySet());
        assertThat(coverage.reasons()).as("unjudged reasons").containsKeys(PositionMeter.Reason.MONTHS, PositionMeter.Reason.N_UP,
                PositionMeter.Reason.N_DOWN);
        assertThat(coverage.positions().keySet()).as("positions reached").hasSizeGreaterThanOrEqualTo(2);
        assertThat(coverage.topTie()).as("volatility tie at the top 20 % boundary").isTrue();
        assertThat(coverage.bottomTie()).as("volatility tie at the bottom 20 % boundary").isTrue();
        assertThat(coverage.positions().get(antlab.asset.Position.GOALKEEPER)).as("goalkeepers, the tied pair included").isGreaterThanOrEqualTo(3);
    }

    @Test
    void P_S2_창_안_환율_행이_없으면_SQL은_비정상_종료하고_Java는_예외를_던진다() throws Exception {
        SyntheticHistory history = SyntheticHistory.positions().withoutUsdChange(YearMonth.of(2011, 2));
        DatabaseHandle db = load(history, "position_missing_fx");
        SqlGoldenHarness.ScriptResult run = harness.run(db, new ScriptStep("db/12_calc_position.sql", Kind.RUN, null, STEP_TIMEOUT));
        assertThat(run.process().exitCode()).as("db/12_calc_position.sql exit code").isNotZero();
        assertThat(run.process().stderr()).contains("P0-10");

        PositionParity.Inputs inputs = inputs(db);
        RowSet stored = harness.readRows(db, PositionParity.STORED, 0L);
        Map<String, AssetSeries> pool = new LinkedHashMap<>();
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i, "base_date").equals(LocalDate.of(2013, 1, 15))) {
                String key = PositionParity.key(stored.get(i, "market"), stored.get(i, "ticker"));
                pool.put(key, inputs.assets().get(key));
            }
        }
        assertThatThrownBy(() -> BaseDatePositionCalculator.judge(LocalDate.of(2013, 1, 15), pool, inputs.history()))
                .isInstanceOf(IllegalStateException.class);
    }
}

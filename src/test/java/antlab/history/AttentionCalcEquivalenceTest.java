package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenCleanup;
import antlab.support.GoldenFixtureSource;
import antlab.support.PsqlScriptRunner;
import antlab.support.SharedPostgres;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The rewritten {@code db/13_calc_attention_def.sql} must equal the original correlated-subquery definition (kept only as a test
 * resource) in every column on a medium random history, and the real script must finish on a full-size history (180 assets x 360 months).
 */
class AttentionCalcEquivalenceTest {

    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern EQUIV = Pattern.compile("EQUIV new=(\\d+) ref=(\\d+) new_minus_ref=(\\d+) ref_minus_new=(\\d+) "
            + "text_diff=(\\d+) z_rows=(\\d+) clipped=(\\d+) seg_starts=(\\d+) max_streak=(-?\\d+) min_streak=(-?\\d+) zero_streak=(\\d+)");

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static PsqlScriptRunner runner;

    @BeforeAll
    static void start() throws Exception {
        container = SharedPostgres.container();
        harness = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
        runner = harness.runner();
        runner.putFile("db/01_schema_history.sql", Path.of("db/01_schema_history.sql"));
        runner.putFile("db/13_calc_attention.sql", Path.of("db/13_calc_attention.sql"));
        runner.putFile("db/13_calc_attention_def.sql", Path.of("db/13_calc_attention_def.sql"));
        for (String name : new String[] {"attn_calc_reference.sql", "attn_equivalence.sql", "attn_synthetic_load.sql"}) {
            runner.putFile("sql/" + name, Path.of("src/test/resources/sql/" + name));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, null);
    }

    private static DatabaseHandle load(String scenario, int assets, int months) throws Exception {
        DatabaseHandle db = harness.createSqlDatabase(scenario);
        PsqlScriptRunner.Result schema = runner.runFile(db.name(), "db/01_schema_history.sql", STEP_TIMEOUT);
        assertThat(schema.exitCode()).as(schema.stderr()).isZero();
        PsqlScriptRunner.Result rows = runner.runFile(db.name(), "sql/attn_synthetic_load.sql", STEP_TIMEOUT,
                Map.of("n_assets", String.valueOf(assets), "n_months", String.valueOf(months)));
        assertThat(rows.exitCode()).as(rows.stderr()).isZero();
        return db;
    }

    @Test
    void 새_정의와_원래_정의가_중간_크기_무작위_이력에서_모든_열이_같다() throws Exception {
        DatabaseHandle db = load("attn_equiv", 20, 120);
        PsqlScriptRunner.Result result = runner.runFile(db.name(), "sql/attn_equivalence.sql", STEP_TIMEOUT);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        Matcher m = EQUIV.matcher(result.stdout());
        assertThat(m.find()).as(result.stdout()).isTrue();
        long rows = Long.parseLong(m.group(1));
        assertThat(rows).isPositive();
        assertThat(Long.parseLong(m.group(2))).as("reference rows").isEqualTo(rows);
        assertThat(Long.parseLong(m.group(3))).as("rows only in the new definition").isZero();
        assertThat(Long.parseLong(m.group(4))).as("rows only in the reference").isZero();
        assertThat(Long.parseLong(m.group(5))).as("rows whose text form differs").isZero();
        assertThat(Long.parseLong(m.group(6))).as("z computed").isPositive();
        assertThat(Long.parseLong(m.group(7))).as("clipped at 3").isPositive();
        assertThat(Long.parseLong(m.group(8))).as("segment starts beyond one per asset").isGreaterThan(20);
        assertThat(Long.parseLong(m.group(9))).as("longest rising streak").isGreaterThanOrEqualTo(3);
        assertThat(Long.parseLong(m.group(10))).as("longest falling streak").isLessThanOrEqualTo(-3);
        assertThat(Long.parseLong(m.group(11))).as("zero-return rows").isPositive();
    }

    @Test
    void 전체_크기_이력에서_계산_스크립트가_제한_시간_안에_끝나고_다시_실행하면_새_행이_없다() throws Exception {
        DatabaseHandle db = load("attn_full_size", 180, 360);
        PsqlScriptRunner.Result first = runner.runFile(db.name(), "db/13_calc_attention.sql", Duration.ofSeconds(60));
        assertThat(first.timedOut()).as("first run timed out").isFalse();
        assertThat(first.exitCode()).as(first.stderr()).isZero();
        System.out.println("[attention-perf] 180 assets x 360 months, db/13 elapsed " + first.elapsed());
        assertThat(first.elapsed()).isLessThan(Duration.ofSeconds(30));
        assertThat(Pattern.compile("^(\\d+)\\|\\1\\|0$", Pattern.MULTILINE).matcher(first.stdout()).find())
                .as("first run reports all rows inserted: %s", first.stdout()).isTrue();
        PsqlScriptRunner.Result second = runner.runFile(db.name(), "db/13_calc_attention.sql", Duration.ofSeconds(60));
        assertThat(second.exitCode()).as(second.stderr()).isZero();
        assertThat(Pattern.compile("^(\\d+)\\|0\\|\\1$", Pattern.MULTILINE).matcher(second.stdout()).find())
                .as("re-run reports 0 inserted: %s", second.stdout()).isTrue();
    }
}

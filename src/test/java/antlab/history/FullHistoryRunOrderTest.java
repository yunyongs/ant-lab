package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.CheckVerdictParser;
import antlab.support.FlywayScratchDatabase;
import antlab.support.PsqlScriptRunner;
import antlab.support.SharedPostgres;
import antlab.support.SyntheticFullHistory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole R-L order of MVP-06 on synthetic full-size data (180 assets x 360 months, 1996-01..2025-12) and the real
 * {@code events/*.csv}, in a database migrated by the production Flyway scripts: db/30 (with ON_ERROR_STOP) and its check, db/12,
 * db/13, db/11 with {@code require_all_months=1}, then the db/12, db/13 and db/11 checks. Every check must PASS on this shape, not only
 * on the Phase 0 sample. The values are generated, never market data. Not a golden test: it needs no private sample, so CI runs it.
 */
class FullHistoryRunOrderTest {

    private static final Duration LONG = Duration.ofMinutes(4);
    private static final List<String> SCRIPTS = List.of(
            "db/30_load_full.sql", "db/30_load_full_check.sql",
            "db/11_load_events.sql", "db/11_load_events_check.sql",
            "db/12_calc_position.sql", "db/12_calc_position_def.sql", "db/12_calc_position_check.sql",
            "db/13_calc_attention.sql", "db/13_calc_attention_def.sql", "db/13_calc_attention_rank_def.sql",
            "db/13_calc_attention_check.sql",
            "events/types.csv", "events/tags.csv", "events/reviewed.csv");

    private static String verdict(PsqlScriptRunner.Result r, String column) {
        CheckVerdictParser.Verdict v = CheckVerdictParser.parse(r.stdout(), column);
        return r.exitCode() + " " + v.reason();
    }

    @Test
    void theWholeOrderPassesOnAFullSizeHistory() throws Exception {
        PostgreSQLContainer container = SharedPostgres.container();
        PsqlScriptRunner runner = new PsqlScriptRunner(container);
        for (String f : SCRIPTS) {
            runner.putFile(f, Path.of(f).toAbsolutePath());
        }
        for (Map.Entry<String, String> e : SyntheticFullHistory.files(Path.of("."))
                .entrySet()) {
            runner.putFile("db/full/" + e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        try (FlywayScratchDatabase db = new FlywayScratchDatabase(container, "rl_order")) {
            db.migrateTo("latest");
            String name = db.name();

            PsqlScriptRunner.Result load = runner.runFile(name, "db/30_load_full.sql", LONG);
            assertThat(load.exitCode()).as(load.stderr()).isZero();
            assertThat(load.stdout()).contains("asset|180|180", "month_block|360|360", "asset_month_return|64800|64800", "fx_month|360|360");

            PsqlScriptRunner.Result loadCheck = runner.runFile(name, "db/30_load_full_check.sql", LONG);
            assertThat(loadCheck.exitCode()).as(loadCheck.stdout() + loadCheck.stderr()).isZero();
            assertThat(loadCheck.stdout()).isEqualTo("code|detail\nload_full_check\nPASS\n");

            PsqlScriptRunner.Result position = runner.runFile(name, "db/12_calc_position.sql", LONG);
            assertThat(position.exitCode()).as(position.stderr()).isZero();
            assertThat(position.stdout()).contains("180|180|0|0");

            PsqlScriptRunner.Result attention = runner.runFile(name, "db/13_calc_attention.sql", LONG);
            assertThat(attention.exitCode()).as(attention.stderr()).isZero();

            PsqlScriptRunner.Result events = runner.runFile(name, "db/11_load_events.sql", LONG, Map.of("require_all_months", "1"));
            assertThat(events.exitCode()).as(events.stderr()).isZero();

            PsqlScriptRunner.Result positionCheck = runner.runFile(name, "db/12_calc_position_check.sql", LONG);
            assertThat(verdict(positionCheck, "p0_10_check")).as(positionCheck.stdout() + positionCheck.stderr()).isEqualTo("0 PASS");

            PsqlScriptRunner.Result attentionCheck = runner.runFile(name, "db/13_calc_attention_check.sql", LONG);
            assertThat(verdict(attentionCheck, "p0_11_check")).as(attentionCheck.stdout() + attentionCheck.stderr()).isEqualTo("0 PASS");

            Map<String, String> strict = Map.of("require_all_months", "1");
            PsqlScriptRunner.Result eventsCheck = runner.runFile(name, "db/11_load_events_check.sql", LONG, strict);
            assertThat(verdict(eventsCheck, "p0_09_check")).as(eventsCheck.stdout() + eventsCheck.stderr()).isEqualTo("0 PASS");

            // reviewed.csv starts in 1997-01: that replay start passes, and the last warm-up month 1996-12 fails REVIEWED_ALL
            PsqlScriptRunner.Result atStart = runner.runFile(name, "db/11_load_events_check.sql", LONG,
                    Map.of("require_all_months", "1", "replay_start", "1997-01-01"));
            assertThat(verdict(atStart, "p0_09_check")).as(atStart.stdout() + atStart.stderr()).isEqualTo("0 PASS");

            PsqlScriptRunner.Result early = runner.runFile(name, "db/11_load_events_check.sql", LONG,
                    Map.of("require_all_months", "1", "replay_start", "1996-12-01"));
            assertThat(early.exitCode()).isNotZero();
            assertThat(early.stdout() + early.stderr()).contains("REVIEWED_ALL");
        }
    }
}

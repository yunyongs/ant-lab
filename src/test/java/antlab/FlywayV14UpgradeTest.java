package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Applying V14 to a V13 database keeps every season row, adds PAUSED and VOID to the status CHECK, and ties the three
 * new columns to those states: paused_at exactly while PAUSED, void_reason and void_note exactly while VOID.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV14UpgradeTest {

    private static final String INSERT_SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version,
                data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            SELECT id, ?, 12, 2, ?, ?, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20 FROM member ORDER BY id LIMIT 1
            """;

    private static final String INSERT_WITH_NEW = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version,
                data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward,
                paused_at, void_reason, void_note)
            SELECT id, ?, 12, 2, NULL, ?, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20, CAST(? AS timestamptz), ?, ? FROM member ORDER BY id LIMIT 1
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static void rejected(JdbcTemplate jdbc, String constraint, String status, long seed, String pausedAt,
            String reason, String note) {
        assertThatThrownBy(() -> jdbc.update(INSERT_WITH_NEW, status, seed, pausedAt, reason, note))
                .as("%s %s %s %s", status, pausedAt, reason, note)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @Test
    void upgradeFromV13KeepsSeasonRowsAndTiesTheNewColumnsToTheNewStates() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v14_upgrade")) {
            scratch.migrateTo("13");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO member (email, name, password) VALUES ('player@example.invalid', 'p1', '!')");
            jdbc.update(INSERT_SEASON, "IN_PROGRESS", null, 1L);
            jdbc.update(INSERT_SEASON, "ABANDONING", 1, 2L);
            jdbc.update(INSERT_SEASON, "HALTED", null, 3L);
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM season ORDER BY id");
            assertThat(before).hasSize(3);
            assertThatThrownBy(() -> jdbc.update(INSERT_SEASON, "PAUSED", null, 9L))
                    .as("V13 does not know PAUSED").isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_season_status");

            assertThat(scratch.migrateTo("14").migrationsExecuted).isEqualTo(1);

            List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM season ORDER BY id");
            assertThat(after).hasSameSizeAs(before);
            for (int i = 0; i < before.size(); i++) {
                Map<String, Object> row = after.get(i);
                assertThat(row.remove("paused_at")).isNull();
                assertThat(row.remove("void_reason")).isNull();
                assertThat(row.remove("void_note")).isNull();
                assertThat(row).as("row %d is unchanged", i).isEqualTo(before.get(i));
            }

            assertThat(jdbc.update(INSERT_WITH_NEW, "PAUSED", 4L, "2026-11-20T00:00:00Z", null, null)).isEqualTo(1);
            assertThat(jdbc.update(INSERT_WITH_NEW, "VOID", 5L, null, "MODEL_GONE", "model removed")).isEqualTo(1);
            assertThat(jdbc.update(INSERT_WITH_NEW, "VOID", 6L, null, "ENGINE_DEFECT", "ledger mismatch")).isEqualTo(1);
            assertThat(jdbc.update(INSERT_WITH_NEW, "VOID", 7L, null, "RUNNER_DEFECT", "x")).isEqualTo(1);

            rejected(jdbc, "ck_season_paused_at", "PAUSED", 10L, null, null, null);
            rejected(jdbc, "ck_season_paused_at", "IN_PROGRESS", 11L, "2026-11-20T00:00:00Z", null, null);
            rejected(jdbc, "ck_season_void_status", "VOID", 12L, null, null, null);
            rejected(jdbc, "ck_season_void_status", "IN_PROGRESS", 13L, null, "MODEL_GONE", "x");
            rejected(jdbc, "ck_season_void_note", "VOID", 14L, null, "MODEL_GONE", null);
            rejected(jdbc, "ck_season_void_note", "VOID", 15L, null, "MODEL_GONE", "   ");
            rejected(jdbc, "ck_season_void_reason", "VOID", 16L, null, "OTHER", "x");
            rejected(jdbc, "ck_season_status", "paused", 17L, null, null, null);
            assertThatThrownBy(() -> jdbc.update(INSERT_WITH_NEW, "VOID", 18L, null, "MODEL_GONE", "x".repeat(501)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE success AND checksum IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class)).last().isEqualTo("14");
        }
    }

    @Test
    void migratingV14AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v14_again")) {
            scratch.migrateTo("14");

            assertThat(scratch.migrateTo("14").migrationsExecuted).isZero();
        }
    }
}

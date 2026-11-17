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

/** Applying V7 to a V6 database keeps every season row and lets the status CHECK accept HALTED (and nothing else new). */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV7UpgradeTest {

    private static final String INSERT_SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version,
                data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            SELECT id, ?, 12, 2, ?, ?, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20 FROM member ORDER BY id LIMIT 1
            """;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void upgradeFromV6KeepsSeasonRowsAndAcceptsHalted() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v7_upgrade")) {
            scratch.migrateTo("6");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO member (email, name, password) VALUES ('player@example.invalid', 'p1', '!')");
            jdbc.update(INSERT_SEASON, "IN_PROGRESS", null, 1L);
            jdbc.update(INSERT_SEASON, "ABANDONING", 1, 2L);
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM season ORDER BY id");
            assertThat(before).hasSize(2);

            assertThat(scratch.migrateTo("7").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT * FROM season ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.update(INSERT_SEASON, "HALTED", null, 3L)).isEqualTo(1);
            assertThat(jdbc.update(INSERT_SEASON, "HALTED", 1, 4L)).as("HALTED with an abandon turn").isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update(INSERT_SEASON, "PAUSED", null, 5L))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_season_status");
            assertThatThrownBy(() -> jdbc.update(INSERT_SEASON, "halted", null, 6L))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_season_status");
            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE success AND checksum IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
        }
    }

    @Test
    void migratingV7AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v7_again")) {
            scratch.migrateTo("7");

            assertThat(scratch.migrateTo("7").migrationsExecuted).isZero();
        }
    }
}

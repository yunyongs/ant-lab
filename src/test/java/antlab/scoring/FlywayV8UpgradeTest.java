package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Applying V8 to a V7 database with finished seasons keeps every row and leaves both new tables empty and valid. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV8UpgradeTest {

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void upgradeFromV7LeavesExistingSeasonsAloneAndCreatesEmptyTables() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v8_upgrade")) {
            scratch.migrateTo("7");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO member (email, name, password) VALUES ('player@example.invalid', 'p1', '!')");
            jdbc.update("""
                    INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version,
                        data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
                    SELECT id, 'FINISHED', 12, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004,
                        -0.20, 10, 30, 40, 20, 10, 30, 40, 20 FROM member ORDER BY id LIMIT 1
                    """);
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM season ORDER BY id");
            assertThat(before).hasSize(1);

            assertThat(scratch.migrateTo("8").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT * FROM season ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM season_score", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rating_history", Integer.class)).isZero();
            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE success AND checksum IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        }
    }

    @Test
    void migratingV8AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v8_again")) {
            scratch.migrateTo("8");

            assertThat(scratch.migrateTo("8").migrationsExecuted).isZero();
        }
    }
}

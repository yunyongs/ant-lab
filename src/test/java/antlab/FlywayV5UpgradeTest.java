package antlab;

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

/** Applying V5 to a V4 database keeps members, seasons and refresh tokens and marks every member HUMAN. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV5UpgradeTest {

    private static final String OLD_COLUMNS = "id, email, name, password, role, created_at, updated_at";

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void upgradeFromV4KeepsRowsAndDefaultsToHuman() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v5_upgrade")) {
            scratch.migrateTo("4");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO member (email, name, password) VALUES "
                    + "('player@example.invalid', 'p1', '!'), ('phase0@example.invalid', 'p2', 'x')");
            jdbc.update("""
                    INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date,
                        initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
                    SELECT id, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20 FROM member ORDER BY id LIMIT 1
                    """);
            jdbc.update("INSERT INTO refresh_token (member_id, family_id, token_hash, expires_at) "
                    + "SELECT id, gen_random_uuid(), repeat('a', 64), now() + interval '1 day' FROM member LIMIT 1");
            List<Map<String, Object>> members = jdbc.queryForList("SELECT " + OLD_COLUMNS + " FROM member ORDER BY id");
            List<Map<String, Object>> seasons = jdbc.queryForList("SELECT * FROM season ORDER BY id");
            assertThat(members).hasSize(2);

            assertThat(scratch.migrateTo("5").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT " + OLD_COLUMNS + " FROM member ORDER BY id")).isEqualTo(members);
            assertThat(jdbc.queryForList("SELECT * FROM season ORDER BY id")).isEqualTo(seasons);
            assertThat(jdbc.queryForList("SELECT kind FROM member ORDER BY id", String.class))
                    .containsExactly("HUMAN", "HUMAN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token", Integer.class)).isEqualTo(1);
            for (String table : List.of("agent_profile", "season_agent_config", "agent_call")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
            }
            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE success AND checksum IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class)).containsExactly("1", "2", "3", "4", "5");
        }
    }

    @Test
    void migratingV5AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v5_again")) {
            scratch.migrateTo("5");

            assertThat(scratch.migrateTo("5").migrationsExecuted).isZero();
        }
    }
}

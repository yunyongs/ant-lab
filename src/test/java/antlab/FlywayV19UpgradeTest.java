package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V19 adds the index of the memory pin and makes the database refuse a sibling league whose seed differs from its group
 * root's. Existing leagues (none has a seed_league_id yet) migrate untouched.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV19UpgradeTest {

    private static final String LEAGUE = """
            INSERT INTO league (owner_member_id, name, seed, path_length, base_date, engine_version, data_snapshot_date,
                initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, max_entries, seed_league_id)
            VALUES (?, 'x', ?, 12, '2020-01-31', 'test', '2020-01-31', 10000000, 0.001, 0.001, 0.004, 8, ?) RETURNING id
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static long human(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES ('a@example.invalid', 'h', '!') "
                + "RETURNING id", Long.class);
    }

    @Test
    void upgradeFromV18KeepsTheLeaguesAndAddsTheIndex() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v19_upgrade")) {
            scratch.migrateTo("18");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc);
            long root = jdbc.queryForObject(LEAGUE, Long.class, owner, 7L, null);
            var before = jdbc.queryForList("SELECT * FROM league ORDER BY id");

            assertThat(scratch.migrateTo("19").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT * FROM league ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_season_agent_config_memory' "
                    + "AND indexdef LIKE '%(member_id, memory_version)'", Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM league WHERE id = ?", Long.class, root)).isOne();
        }
    }

    @Test
    void aSiblingMustPlayTheSeedOfItsRoot() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v19_seed")) {
            scratch.migrateTo("19");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc);
            long root = jdbc.queryForObject(LEAGUE, Long.class, owner, 7L, null);

            long sibling = jdbc.queryForObject(LEAGUE, Long.class, owner, 7L, root);

            assertThat(jdbc.queryForObject("SELECT seed_league_id FROM league WHERE id = ?", Long.class, sibling))
                    .isEqualTo(root);
            assertThatThrownBy(() -> jdbc.queryForObject(LEAGUE, Long.class, owner, 8L, root))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_league_seed_group");
            long other = jdbc.queryForObject(LEAGUE, Long.class, owner, 9L, null);
            assertThat(jdbc.queryForObject("SELECT seed_league_id FROM league WHERE id = ?", Long.class, other)).isNull();
        }
    }

    @Test
    void migratingV19AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v19_again")) {
            scratch.migrateTo("19");

            assertThat(scratch.migrateTo("19").migrationsExecuted).isZero();
        }
    }
}

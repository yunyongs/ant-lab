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

/** V16 adds league and league_entry to a V15 database without touching its rows, and every rule of the two tables is enforced by name. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV16UpgradeTest {

    private static final String SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version, data_snapshot_date,
                base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            VALUES (?, 'IN_PROGRESS', 12, 0, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20) RETURNING id
            """;

    private static final String LEAGUE = """
            INSERT INTO league (owner_member_id, name, seed, path_length, base_date, engine_version, data_snapshot_date,
                initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, max_entries)
            VALUES (?, ?, 7, 12, '2020-01-31', 'test', '2020-01-31', ?, 0.001, 0.001, 0.004, ?) RETURNING id
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static long human(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES (?, 'h', '!') RETURNING id",
                Long.class, email);
    }

    private static long league(JdbcTemplate jdbc, long owner) {
        return jdbc.queryForObject(LEAGUE, Long.class, owner, "Spring league", 10000000, 8);
    }

    private static void rejected(JdbcTemplate jdbc, String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @Test
    void upgradeFromV15KeepsTheExistingRowsAndAddsTwoEmptyTables() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v16_upgrade")) {
            scratch.migrateTo("15");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            jdbc.queryForObject(SEASON, Long.class, owner);
            long seasons = jdbc.queryForObject("SELECT count(*) FROM season", Long.class);

            assertThat(scratch.migrateTo("16").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM season", Long.class)).isEqualTo(seasons);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM league", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM league_entry", Long.class)).isZero();
        }
    }

    @Test
    void theLeagueRulesAreEnforcedByName() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v16_league")) {
            scratch.migrateTo("16");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long agent = jdbc.queryForObject("INSERT INTO member (kind, name) VALUES ('AGENT', 'agent') RETURNING id",
                    Long.class);
            assertThat(league(jdbc, owner)).isPositive();

            rejected(jdbc, LEAGUE, "fk_league_owner", 987654321L, "x", 10000000, 8);
            // an agent member cannot own a league: the composite key pins owner_kind to the member's own kind
            rejected(jdbc, LEAGUE, "fk_league_owner", agent, "x", 10000000, 8);
            rejected(jdbc, LEAGUE, "ck_league_name", owner, "", 10000000, 8);
            rejected(jdbc, LEAGUE, "ck_league_name", owner, " padded", 10000000, 8);
            rejected(jdbc, LEAGUE, "ck_league_max_entries", owner, "x", 10000000, 1);
            rejected(jdbc, LEAGUE, "ck_league_max_entries", owner, "x", 10000000, 21);
            rejected(jdbc, LEAGUE, "ck_league_initial_cash_pos", owner, "x", 0, 8);
            rejected(jdbc, LEAGUE, "ck_league_initial_cash_won", owner, "x", new java.math.BigDecimal("10000000.5"), 8);
            rejected(jdbc, "UPDATE league SET status = 'DONE'", "ck_league_status");
            rejected(jdbc, "UPDATE league SET path_length = 0", "ck_league_path_length");
            rejected(jdbc, "UPDATE league SET engine_version = ''", "ck_league_engine_version");
            rejected(jdbc, "UPDATE league SET fee_rate_kr = 1", "ck_league_fee_rate_kr");
            rejected(jdbc, "UPDATE league SET fee_rate_us = 'NaN'::numeric", "ck_league_fee_rate_us");
            rejected(jdbc, "UPDATE league SET fx_spread_rate = -0.1", "ck_league_fx_spread_rate");
            // closed_at is set exactly while the league is closed
            rejected(jdbc, "UPDATE league SET status = 'CLOSED'", "ck_league_closed_at");
            rejected(jdbc, "UPDATE league SET closed_at = now()", "ck_league_closed_at");
            assertThat(jdbc.update("UPDATE league SET status = 'CLOSED', closed_at = now()")).isOne();
        }
    }

    @Test
    void oneSeatPerMemberOneLeaguePerSeasonAndTheSeasonMemberIsPinned() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v16_entry")) {
            scratch.migrateTo("16");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long other = human(jdbc, "b@example.invalid");
            long l1 = league(jdbc, owner);
            long l2 = league(jdbc, owner);
            long l3 = league(jdbc, owner);
            long s1 = jdbc.queryForObject(SEASON, Long.class, owner);
            long s2 = jdbc.queryForObject(SEASON, Long.class, owner);
            long s3 = jdbc.queryForObject(SEASON, Long.class, other);
            String insert = "INSERT INTO league_entry (league_id, season_id, member_id) VALUES (?, ?, ?)";
            assertThat(jdbc.update(insert, l1, s1, owner)).isOne();

            // a second seat of the same member in the same league
            rejected(jdbc, insert, "uq_league_entry_member", l1, s2, owner);
            // the same season in a second league
            rejected(jdbc, insert, "uq_league_entry_season", l2, s1, owner);
            // the same member may sit in another league
            assertThat(jdbc.update(insert, l2, s2, owner)).isOne();
            // member_id must be the season's own member
            rejected(jdbc, insert, "fk_league_entry_season", l3, s3, owner);
            rejected(jdbc, insert, "fk_league_entry_league", 987654321L, s3, other);
            assertThat(jdbc.update(insert, l1, s3, other)).isOne();
            // the same pair twice
            rejected(jdbc, insert, "pk_league_entry", l1, s3, other);
        }
    }
}

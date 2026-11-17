package antlab.dbrole;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.persistence.DbPrivilegeGuard;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** R5: a database migrated to V6 by the superuser, with data, converges with one script run and the app role starts on it. */
class RoleScriptUpgradeTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();

    private static Map<String, Long> counts(JdbcTemplate jdbc) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace "
                + "AND relkind = 'r' ORDER BY relname", String.class)) {
            out.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return out;
    }

    @Test
    void r5_oneScriptRunMovesOwnershipAndKeepsEveryRow() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r5")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            JdbcTemplate superuser = db.superuser();
            new ResourceDatabasePopulator(new ClassPathResource("fixtures/synthetic/season_fake.sql"))
                    .execute(superuser.getDataSource());
            superuser.update("INSERT INTO member (email, name, password) VALUES ('upgrade@example.invalid', 'upgrade', '!')");
            superuser.update("""
                    INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date,
                        initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
                    SELECT id, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20 FROM member ORDER BY id LIMIT 1""");
            Map<String, Long> before = counts(superuser);
            List<Map<String, Object>> history = superuser.queryForList(
                    "SELECT version, checksum, installed_by, success FROM flyway_schema_history ORDER BY installed_rank");
            assertThat(before.get("asset")).isPositive();
            assertThat(before.get("season")).isEqualTo(1);

            SERVER.runRoleScript(db.name);
            SERVER.setPasswords(db.name);

            assertThat(counts(superuser)).as("row counts per table").isEqualTo(before);
            assertThat(superuser.queryForList("SELECT version, checksum, installed_by, success FROM flyway_schema_history "
                    + "ORDER BY installed_rank")).as("history untouched").isEqualTo(history);
            assertThat(superuser.queryForList("""
                    SELECT c.relname || ' ' || c.relkind::text || ' ' || c.relowner::regrole::text FROM pg_class c
                    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'S', 'i')
                      AND c.relowner <> 'antlab_owner'::regrole::oid""", String.class))
                    .as("tables, indexes and identity sequences all moved").isEmpty();
            assertThat(superuser.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace "
                    + "AND relkind = 'S'", Integer.class)).as("the sequences exist").isPositive();

            var flyway = SERVER.flyway(db.name, "antlab_owner", true);
            assertThat(flyway.info().pending()).as("nothing pending for the new owner").isEmpty();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            JdbcTemplate app = db.as("antlab_app");
            assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("antlab_app");
            assertThat(app.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(1);
            assertThat(app.update("INSERT INTO member (email, name, password) VALUES ('after@example.invalid', 'after', '!')"))
                    .isEqualTo(1);
            assertThat(DbPrivilegeGuard.violations(app, "antlab_owner")).isEmpty();
        }
    }

    @Test
    void theScriptConvergesAnAlreadyUpgradedDatabaseToTheSameState() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r5b")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runRoleScript(db.name);
            SERVER.setPasswords(db.name);
            SERVER.migrate(db.name, "antlab_owner", true);
            String before = RoleScriptTest.snapshot(db.superuser());

            SERVER.runRoleScript(db.name);

            assertThat(RoleScriptTest.snapshot(db.superuser())).as("rerun after the app started keeps the grants").isEqualTo(before);
        }
    }
}

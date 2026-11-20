package antlab.dbrole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** R9: db/roles/antlab_engine.sql (M2-03) and this script in both orders; the engine's effective rights stay the seven SELECTs. */
class RoleScriptEngineCoexistenceTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();

    private static final List<String> SEVEN_SELECTS = List.of("asset:SELECT", "asset_position:SELECT", "month_block:SELECT",
            "asset_month_return:SELECT", "fx_month:SELECT", "series:SELECT", "series_observation:SELECT");

    private static List<String> engineRights(JdbcTemplate jdbc) {
        List<String> rights = new ArrayList<>();
        for (String table : jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace "
                + "AND relkind IN ('r', 'p') ORDER BY relname", String.class)) {
            for (String privilege : AppPrivilegeMatrixTest.PRIVILEGES) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege('antlab_engine', ?::regclass, ?)",
                        Boolean.class, "public." + table, privilege))) {
                    rights.add(table + ":" + privilege);
                }
            }
        }
        return rights;
    }

    private static void assertEngineUnchanged(JdbcTemplate jdbc) {
        assertThat(engineRights(jdbc)).containsExactlyInAnyOrderElementsOf(SEVEN_SELECTS);
        assertThat(jdbc.queryForList("SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting "
                + "WHERE setrole = 'antlab_engine'::regrole", String.class))
                .singleElement().asString().contains("default_transaction_read_only=on");
        assertThat(jdbc.queryForObject("SELECT has_schema_privilege('antlab_engine', 'public', 'CREATE')", Boolean.class)).isFalse();
    }

    @Test
    void r9_engineScriptFirstThenOwnerAppScript() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r9a")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runEngineScript(db.name);
            assertEngineUnchanged(db.superuser());

            SERVER.runRoleScript(db.name);
            SERVER.setPasswords(db.name);
            SERVER.migrate(db.name, "antlab_owner", true);

            assertEngineUnchanged(db.superuser());
            SERVER.runEngineScript(db.name);
            assertEngineUnchanged(db.superuser());
        }
    }

    @Test
    void r9_ownerAppScriptFirstThenEngineScript() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r9b")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runRoleScript(db.name);
            SERVER.setPasswords(db.name);
            SERVER.migrate(db.name, "antlab_owner", true);

            SERVER.runEngineScript(db.name);

            assertEngineUnchanged(db.superuser());
            SERVER.runRoleScript(db.name);
            assertEngineUnchanged(db.superuser());
            assertThat(db.superuser().queryForObject("SELECT has_table_privilege('antlab_app', 'asset'::regclass, 'SELECT')",
                    Boolean.class)).as("the app role keeps its grants").isTrue();
        }
    }

    @Test
    void aDatabaseCreateGrantToPublicIsRefusedWithTheIntendedMessageAndChangesNothing() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r9c")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            db.superuserExecute("DO $f$ BEGIN EXECUTE format('GRANT CREATE ON DATABASE %I TO PUBLIC', current_database()); END $f$");

            assertThatThrownBy(() -> SERVER.runEngineScript(db.name))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("antlab_engine can create schemas or temporary tables in this database")
                    .hasMessageContaining("rolled back")
                    .hasMessageNotContaining("malformed array literal");
            assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'antlab_engine'", Integer.class)).isZero();
        }
    }
}

package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Contract of the JVM-wide container: one instance, and databases that never see each other's rows. */
class SharedPostgresTest {

    private static String scalar(String database, String sql) throws Exception {
        PostgreSQLContainer c = SharedPostgres.container();
        try (Connection conn = DriverManager.getConnection(SharedPostgres.jdbcUrl(database), c.getUsername(), c.getPassword());
                Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void execute(String database, String sql) throws Exception {
        PostgreSQLContainer c = SharedPostgres.container();
        try (Connection conn = DriverManager.getConnection(SharedPostgres.jdbcUrl(database), c.getUsername(), c.getPassword());
                Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void every_call_returns_the_same_running_container() {
        PostgreSQLContainer first = SharedPostgres.container();
        assertThat(SharedPostgres.container()).isSameAs(first);
        assertThat(first.isRunning()).isTrue();
    }

    @Test
    void databases_are_distinct_and_do_not_share_rows() throws Exception {
        String a = SharedPostgres.createDatabase("shared_a");
        String b = SharedPostgres.createDatabase("shared_a");
        assertThat(a).isNotEqualTo(b).isNotEqualTo(SharedPostgres.container().getDatabaseName());

        execute(a, "CREATE TABLE only_in_a (n int)");
        execute(a, "INSERT INTO only_in_a VALUES (1)");

        assertThat(scalar(a, "SELECT count(*) FROM only_in_a")).isEqualTo("1");
        assertThat(scalar(b, "SELECT count(*) FROM pg_tables WHERE tablename = 'only_in_a'")).isEqualTo("0");
    }

    @Test
    void new_databases_use_the_compose_time_zone_and_utf8() throws Exception {
        String db = SharedPostgres.createDatabase("shared_tz");
        // the JDBC driver overrides the session zone with the JVM zone, so read the stored database setting instead
        assertThat(scalar(db, "SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting s JOIN pg_database d ON d.oid = s.setdatabase"
                + " WHERE d.datname = current_database()")).isEqualToIgnoringCase("timezone=Asia/Seoul");
        assertThat(scalar(db, "SHOW server_encoding")).isEqualTo("UTF8");
    }
}

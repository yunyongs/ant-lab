package antlab.persistence;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks that the connection's user is the least-privilege application role and not the migration owner (MVP-20).
 * Every check reads the catalog as {@code current_user}; a violation names the check, never a URL or a password.
 */
public final class DbPrivilegeGuard {

    private static final String ROLE_ATTRIBUTES = """
            SELECT rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolreplication
            FROM pg_roles WHERE rolname = current_user""";

    private static final String OWNS_RELATION = """
            SELECT EXISTS (
                SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
                WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
                  AND r.rolname = current_user)""";

    /** Any right beyond SELECT/INSERT/UPDATE on a table, whatever its source (direct, PUBLIC, role membership). */
    private static final String FORBIDDEN_TABLE_RIGHT = """
            SELECT EXISTS (
                SELECT 1 FROM pg_class c, unnest(ARRAY['DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER', 'MAINTAIN']) p
                WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                  AND has_table_privilege(current_user, c.oid, p))""";

    private static final String MIGRATION_HISTORY_RIGHT = """
            SELECT EXISTS (
                SELECT 1 FROM unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER',
                    'MAINTAIN']) p
                WHERE has_table_privilege(current_user, to_regclass('public.flyway_schema_history'), p))""";

    private static final String OWNS_SCHEMA = """
            SELECT EXISTS (
                SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
                WHERE n.nspname = 'public' AND r.rolname = current_user)""";

    private static final String MEMBER_OF = """
            SELECT EXISTS (
                SELECT 1 FROM pg_roles r WHERE r.rolname = ? AND pg_has_role(current_user, r.oid, 'MEMBER'))""";

    private DbPrivilegeGuard() {
    }

    /** The failed checks, in a fixed order; empty when the connection user is acceptable. */
    public static List<String> violations(JdbcTemplate jdbc, String flywayUser) {
        List<String> found = new ArrayList<>();
        jdbc.query(ROLE_ATTRIBUTES, rs -> {
            addIf(found, rs.getBoolean("rolsuper"), "the DB user is a superuser");
            addIf(found, rs.getBoolean("rolcreatedb"), "the DB user can create databases");
            addIf(found, rs.getBoolean("rolcreaterole"), "the DB user can create roles");
            addIf(found, rs.getBoolean("rolbypassrls"), "the DB user bypasses row-level security");
            addIf(found, rs.getBoolean("rolreplication"), "the DB user can replicate");
        });
        addIf(found, flag(jdbc, "SELECT has_database_privilege(current_user, current_database(), 'CREATE')"),
                "the DB user can create schemas in this database");
        addIf(found, flag(jdbc, "SELECT has_database_privilege(current_user, current_database(), 'TEMPORARY')"),
                "the DB user can create temporary tables");
        addIf(found, flag(jdbc, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')"),
                "the DB user can create objects in schema public");
        addIf(found, flag(jdbc, OWNS_RELATION), "the DB user owns relations in schema public");
        addIf(found, flag(jdbc, OWNS_SCHEMA), "the DB user owns schema public");
        addIf(found, flag(jdbc, FORBIDDEN_TABLE_RIGHT),
                "the DB user holds DELETE, TRUNCATE, REFERENCES, TRIGGER or MAINTAIN on a table in schema public");
        addIf(found, flag(jdbc, MIGRATION_HISTORY_RIGHT), "the DB user has rights on flyway_schema_history");
        if (flywayUser == null || flywayUser.isBlank()) {
            found.add("spring.flyway.user is not set, so migrations would run as the DB user");
        } else {
            Boolean member = jdbc.queryForObject(MEMBER_OF, Boolean.class, flywayUser);
            addIf(found, Boolean.TRUE.equals(member),
                    "the DB user is, or is a member of, the Flyway user (" + flywayUser + ")");
        }
        return List.copyOf(found);
    }

    private static boolean flag(JdbcTemplate jdbc, String sql) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class));
    }

    private static void addIf(List<String> found, boolean condition, String message) {
        if (condition) {
            found.add(message);
        }
    }
}

package antlab.dbrole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.AntlabApplication;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application context on a database created by the role script, started the way prod starts: Flyway as antlab_owner,
 * the app as antlab_app, the least-privilege guard on (M2, M3). Every row of the history is the owner's.
 */
@SpringBootTest
@ActiveProfiles("test")
class AppRoleStartupTest {

    private static AppRoleDatabase database;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database = AppRoleDatabase.register(registry, "startup");
    }

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Flyway flyway;
    @Autowired
    ApplicationContext context;

    private JdbcTemplate superuser() {
        return new JdbcTemplate(database.superuser());
    }

    @Test
    void theAppRunsAsTheAppRoleAndFlywayAsTheOwner() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("antlab_app");
        assertThat(superuser().queryForList("SELECT DISTINCT installed_by FROM flyway_schema_history", String.class))
                .containsExactly("antlab_owner");
        assertThat(superuser().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(6);
        assertThat(context.containsBean("dbPrivilegeStartupCheck")).as("the guard is wired when the property is on").isTrue();
    }

    @Test
    void everyTableAndSequenceInPublicBelongsToTheOwner() {
        assertThat(superuser().queryForList("""
                SELECT c.relname FROM pg_class c
                WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'S', 'i')
                  AND c.relowner <> 'antlab_owner'::regrole::oid""", String.class)).isEmpty();
        assertThat(superuser().queryForObject("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'public'",
                String.class)).isEqualTo("antlab_owner");
    }

    @Test
    void theCallbackRunsOnEveryMigrateEvenWhenNothingIsPending() {
        JdbcTemplate superuser = superuser();
        superuser.execute("GRANT DELETE ON holding TO antlab_app");
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('antlab_app', 'holding', 'DELETE')", Boolean.class)).isTrue();

        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('antlab_app', 'holding', 'DELETE')", Boolean.class)).isFalse();
    }

    @Test
    void theAppRoleCannotCreateObjects() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE should_not_exist (i int)"))
                .rootCause().hasMessageContaining("permission denied for schema public");
    }

    @Test
    void startingAsASuperuserFailsBeforeTheApplicationIsReady() {
        RoleTestServer server = database.server();
        String other = server.createDatabase("startup_super");

        List<String> args = List.of(
                "--spring.datasource.url=" + server.url(other),
                "--spring.datasource.username=" + server.container().getUsername(),
                "--spring.datasource.password=" + server.container().getPassword(),
                "--spring.flyway.user=" + server.container().getUsername(),
                "--spring.flyway.password=" + server.container().getPassword(),
                "--antlab.db.least-privilege-check=true",
                "--server.port=0", "--management.server.port=0");

        assertThatThrownBy(() -> new SpringApplicationBuilder(AntlabApplication.class)
                .web(WebApplicationType.SERVLET).profiles("test").run(args.toArray(String[]::new)))
                .hasStackTraceContaining("antlab.db.least-privilege-check failed")
                .hasStackTraceContaining("the DB user is a superuser")
                .hasStackTraceContaining("the DB user is, or is a member of, the Flyway user");
    }
}

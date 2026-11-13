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

/** Applying V4 to a V3 database with existing members leaves the rows unchanged and only fills role with USER. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV4UpgradeTest {

    private static final String OLD_COLUMNS = "id, email, name, password, created_at, updated_at";

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void V3에서_V4로_올리면_기존_회원은_그대로이고_역할은_USER다() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v4_upgrade")) {
            scratch.migrateTo("3");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO member (email, name, password) VALUES "
                    + "('player@example.invalid', '감독', '!'), ('phase0@example.invalid', '연습', 'x')");
            List<Map<String, Object>> before = jdbc.queryForList(
                    "SELECT " + OLD_COLUMNS + " FROM member ORDER BY id");
            assertThat(before).hasSize(2);

            assertThat(scratch.migrateTo("4").migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT " + OLD_COLUMNS + " FROM member ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT role FROM member ORDER BY id", String.class))
                    .containsExactly("USER", "USER");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token", Integer.class)).isZero();
            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE success AND checksum IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class)).containsExactly("1", "2", "3", "4");
        }
    }

    @Test
    void V4를_다시_migrate해도_적용_건수가_0이다() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v4_again")) {
            scratch.migrateTo("4");

            assertThat(scratch.migrateTo("4").migrationsExecuted).isZero();
        }
    }
}

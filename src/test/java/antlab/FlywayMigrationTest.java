package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** 빈 PostgreSQL 17 시험 DB에 기준선만 적용되는지 검사한다. */
// 빈 DB가 전제인 시험이라, 표지 속성으로 시험 문맥(컨테이너)을 다른 시험과 나눈다.
@TestPropertySource(properties = "antlab.test.context=empty-schema")
@IntegrationTest
class FlywayMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Autowired
    TestMembers testMembers;

    @Test
    void V1부터_V4까지_업무_테이블과_성공_이력이_생긴다() {
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, Integer.class);

        assertThat(tableCount).isEqualTo(23);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE success AND version IN ('1', '2', '3', '4')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE version IN ('1', '2', '3', '4') AND checksum IS NOT NULL
                """, Integer.class)).isEqualTo(4);
    }

    @Test
    void 다시_migrate해도_적용_건수가_0이고_이력이_그대로다() {
        String history = "SELECT installed_rank, version, script, checksum, success "
                + "FROM public.flyway_schema_history ORDER BY installed_rank";
        var before = jdbc.queryForList(history);

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(history)).isEqualTo(before);
    }

    @Test
    void 업무_테이블은_비어_있고_이력은_네_건이다() {
        // the committed default test member (TestMembers) is the only business row
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.member WHERE id <> ?", Integer.class, testMembers.defaultId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.cash_account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE installed_by IS NOT NULL", Integer.class))
                .isEqualTo(4);
    }

    @Test
    void 적용한_마이그레이션을_고치면_검증이_실패한다(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("V1__probe.sql");
        Files.writeString(script, "CREATE TABLE probe (id integer);\n");
        Flyway probe = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .schemas("flyway_tamper_probe").defaultSchema("flyway_tamper_probe")
                .locations("filesystem:" + dir.toAbsolutePath())
                .load();
        try {
            probe.migrate();
            Files.writeString(script, "CREATE TABLE probe (id bigint);\n");
            assertThat(probe.validateWithResult().validationSuccessful).isFalse();
            assertThatThrownBy(probe::migrate).isInstanceOf(FlywayException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS flyway_tamper_probe CASCADE");
        }
    }

    @Test
    void 이력이_없는_비어_있지_않은_스키마는_자동_baseline하지_않는다() {
        jdbc.execute("CREATE SCHEMA flyway_non_empty_probe");
        jdbc.execute("CREATE TABLE flyway_non_empty_probe.existing_table (id integer)");

        try {
            Flyway probe = Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .schemas("flyway_non_empty_probe").defaultSchema("flyway_non_empty_probe")
                    .load();

            assertThatThrownBy(probe::migrate)
                    .isInstanceOf(FlywayException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'flyway_non_empty_probe'
                      AND table_name = 'flyway_schema_history'
                    """, Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA flyway_non_empty_probe CASCADE");
        }
    }
}

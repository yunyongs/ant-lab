package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenFixtureSource;
import antlab.support.IntegrationTest;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScenarioPlan;
import antlab.support.SqlGoldenScenario.ScriptStep;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 컨테이너 안에서 Flyway DB(API 시험 DB)와 SQL 기준 DB 가 서로 영향을 주지 않는지 확인한다.
 * 표본 CSV 없이도 돌아가는 01–03 스키마 파일과 검증만 쓴다.
 */
@TestPropertySource(properties = "antlab.test.context=empty-schema")
@IntegrationTest
class SqlGoldenIsolationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    private Map<String, Object> flywayState() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM pg_tables WHERE schemaname = 'public') AS tables,
                       (SELECT count(*) FROM public.flyway_schema_history WHERE success) AS migrations,
                       current_database() AS db""");
    }

    @Test
    void SQL_기준_DB에_01_03을_실행해도_Flyway_DB는_그대로다() throws Exception {
        Map<String, Object> before = flywayState();
        try (SqlGoldenHarness harness = new SqlGoldenHarness(postgres, GoldenFixtureSource.fromEnvironment())) {
            DatabaseHandle sqlDb = harness.createSqlDatabase("isolation");
            // CSV 없이도 되는 단계만: 입력 묶음 대신 필요한 SQL 파일만 직접 놓는다
            List<String> files = List.of("01_schema_history", "02_schema_season", "03_schema_account");
            for (String f : files) {
                for (String suffix : List.of("", "_check")) {
                    String rel = "db/" + f + suffix + ".sql";
                    harness.runner().putFile(rel, Files.readAllBytes(Path.of(rel)));
                }
            }
            Duration t = Duration.ofMinutes(2);
            ScenarioPlan plan = new ScenarioPlan("ISOLATION", List.of(
                    new ScriptStep("db/01_schema_history.sql", Kind.RUN, null, t),
                    new ScriptStep("db/01_schema_history_check.sql", Kind.CHECK, "p0_05_check", t),
                    new ScriptStep("db/02_schema_season.sql", Kind.RUN, null, t),
                    new ScriptStep("db/02_schema_season_check.sql", Kind.CHECK, "p0_06_check", t),
                    new ScriptStep("db/03_schema_account.sql", Kind.RUN, null, t),
                    new ScriptStep("db/03_schema_account_check.sql", Kind.CHECK, "p0_07_check", t)));

            harness.execute(sqlDb, plan);

            assertThat(sqlDb.name()).isNotEqualTo(postgres.getDatabaseName());
            assertThat(flywayState()).isEqualTo(before);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_database WHERE datname = ?", Integer.class, sqlDb.name())).isEqualTo(1);
        }
        // 하네스가 닫히면 만든 DB 만 지워지고 Flyway DB 는 남는다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_database WHERE datname LIKE 'golden\\_isolation\\_%'", Integer.class)).isZero();
        assertThat(flywayState()).isEqualTo(before);
    }
}

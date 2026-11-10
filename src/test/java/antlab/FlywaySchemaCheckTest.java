package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.CheckVerdictParser;
import antlab.support.IntegrationTest;
import antlab.support.PsqlScriptRunner;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Flyway가 만든 빈 DB에서 기존 01–03 스키마 검증 SQL을 그대로 실행한다. 실행·판정은 골든 하네스의 공통 기반을 쓴다. */
// 빈 DB가 전제인 시험이라, 표지 속성으로 시험 문맥(컨테이너)을 다른 시험과 나눈다.
@TestPropertySource(properties = "antlab.test.context=empty-schema")
@IntegrationTest
class FlywaySchemaCheckTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "01, schema_history, p0_05_check",
            "02, schema_season, p0_06_check",
            "03, schema_account, p0_07_check"
    })
    void 기존_스키마_검증이_PASS다(String number, String name, String checkName) throws Exception {
        String fileName = "db/" + number + "_" + name + "_check.sql";
        PsqlScriptRunner runner = new PsqlScriptRunner(postgres);
        runner.putFile(fileName, Path.of(fileName).toAbsolutePath());

        var result = runner.runFile(postgres.getDatabaseName(), fileName, Duration.ofMinutes(2));

        assertThat(result.timedOut()).as(fileName).isFalse();
        assertThat(result.exitCode()).as(fileName).isZero();
        // 마지막 판정 행(등록한 열 이름과 정확히 PASS)만 인정한다
        assertThat(CheckVerdictParser.parse(result.stdout(), checkName).passed()).as(checkName).isTrue();
        // 검증은 ROLLBACK으로 끝나므로 Flyway 이력 3건은 그대로다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class)).isEqualTo(3);
    }
}

package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Flyway가 만든 빈 DB에서 기존 01–03 스키마 검증 SQL을 그대로 실행한다. */
// 빈 DB가 전제인 시험이라, 표지 속성으로 시험 문맥(컨테이너)을 다른 시험과 나눈다.
@TestPropertySource(properties = "antlab.test.context=empty-schema")
@IntegrationTest
class FlywaySchemaCheckTest {

    /** 파일 판정 계약: 출력의 끝이 판정 열 머리글, 구분선, PASS 한 줄이다. */
    static Pattern finalVerdict(String checkName) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(checkName) + "\\s*\\R-+\\R\\s*PASS\\s*\\z");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "01, p0_05_check",
            "02, p0_06_check",
            "03, p0_07_check"
    })
    void 기존_스키마_검증이_PASS다(String number, String checkName) throws Exception {
        String fileName = "db/" + number + "_schema_"
                + switch (number) {
                    case "01" -> "history";
                    case "02" -> "season";
                    case "03" -> "account";
                    default -> throw new IllegalArgumentException(number);
                }
                + "_check.sql";
        String inContainer = "/tmp/" + Path.of(fileName).getFileName();
        postgres.copyFileToContainer(
                MountableFile.forHostPath(Path.of(fileName).toAbsolutePath()), inContainer);

        var result = postgres.execInContainer(
                "psql", "-X", "-v", "ON_ERROR_STOP=1", "-P", "footer=off",
                "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-f", inContainer);

        assertThat(result.getExitCode()).as(fileName).isZero();
        assertThat(result.getStdout()).as(checkName).containsPattern(finalVerdict(checkName));
        // 검증은 ROLLBACK으로 끝나므로 Flyway 이력 3건은 그대로다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class)).isEqualTo(3);
    }

    @Test
    void 중간에_PASS가_있어도_마지막_판정이_FAIL이면_일치하지_않는다() {
        String failed = " p0_05_check \n-------------\n FAIL\n";
        String passed = " p0_05_check \n-------------\n PASS\n";
        String midPassFinalFail = "PASS\n" + failed;

        assertThat(finalVerdict("p0_05_check").matcher(failed).find()).isFalse();
        assertThat(finalVerdict("p0_05_check").matcher(midPassFinalFail).find()).isFalse();
        assertThat(finalVerdict("p0_05_check").matcher(passed).find()).isTrue();
    }
}

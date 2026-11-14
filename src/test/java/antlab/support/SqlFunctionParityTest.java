package antlab.support;

import static antlab.support.SqlFunctionParity.exactCase;
import static antlab.support.SqlFunctionParity.lit;
import static antlab.support.SqlFunctionParity.valueCase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Contract of the SQL function parity base: report contents, three-way failure, input guards. */
class SqlFunctionParityTest {

    private static final String DOUBLE_FN = """
            CREATE OR REPLACE FUNCTION pg_temp.twice(p numeric) RETURNS numeric(12,4)
            LANGUAGE sql IMMUTABLE AS $$
                SELECT (p * 2)::numeric(12,4)
            $$;
            """;

    static PostgreSQLContainer container;

    @TempDir
    Path dir;

    @BeforeAll
    static void start() {
        container = SqlGoldenHarness.newContainer();
        container.start();
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(null, container);
    }

    private SqlFunctionParity parity(String source) throws Exception {
        Path file = dir.resolve("defs.sql");
        Files.writeString(file, source);
        return new SqlFunctionParity(container).install(file, "twice");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 세_값이_같으면_보고서에_버전_체크섬_건수가_남는다() throws Exception {
        SqlFunctionParity.Report report = parity(DOUBLE_FN).verify(List.of(
                valueCase("twice.a", "pg_temp.twice(" + lit(bd("1.5")) + ")", bd("3.0"), "3"),
                exactCase("twice.b", "pg_temp.twice(" + lit(bd("2")) + ")", bd("4.0000"), "4.0000")));
        assertThat(report.serverVersion()).startsWith("PostgreSQL 17");
        assertThat(report.cases()).isEqualTo(2);
        assertThat(report.scriptSha256()).hasSize(64);
        assertThat(report.sourceSha256()).hasSize(1);
    }

    @Test
    void 원문이_바뀌면_체크섬이_바뀐다() throws Exception {
        List<SqlFunctionParity.Case> cases = List.of(
                valueCase("twice.a", "pg_temp.twice(" + lit(bd("1")) + ")", bd("2"), "2"));
        SqlFunctionParity.Report first = parity(DOUBLE_FN).verify(cases);
        SqlFunctionParity.Report same = parity(DOUBLE_FN).verify(cases);
        SqlFunctionParity.Report changed = parity(DOUBLE_FN.replace("p * 2", "p + p")).verify(cases);
        assertThat(same.scriptSha256()).isEqualTo(first.scriptSha256());
        assertThat(changed.scriptSha256()).isNotEqualTo(first.scriptSha256());
        assertThat(changed.sourceSha256()).isNotEqualTo(first.sourceSha256());
    }

    @Test
    void Java_값이_다르면_케이스_ID와_세_값을_보고한다() throws Exception {
        assertThatThrownBy(() -> parity(DOUBLE_FN).verify(List.of(
                valueCase("twice.ok", "pg_temp.twice(" + lit(bd("1")) + ")", bd("2"), "2"),
                valueCase("twice.javaOff", "pg_temp.twice(" + lit(bd("1")) + ")", bd("3"), "2"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("twice.javaOff: java=3 sql=2.0000 expected=2")
                .hasMessageNotContaining("twice.ok:");
    }

    @Test
    void 기대값이_다르면_SQL과_Java가_같아도_실패한다() throws Exception {
        assertThatThrownBy(() -> parity(DOUBLE_FN).verify(List.of(
                valueCase("twice.expectedOff", "pg_temp.twice(" + lit(bd("1")) + ")", bd("2"), "5"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("twice.expectedOff: java=2 sql=2.0000 expected=5");
    }

    @Test
    void SQL_값이_다르면_실패한다() throws Exception {
        assertThatThrownBy(() -> parity(DOUBLE_FN.replace("p * 2", "p * 3")).verify(List.of(
                valueCase("twice.sqlOff", "pg_temp.twice(" + lit(bd("1")) + ")", bd("2"), "2"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("twice.sqlOff: java=2 sql=3.0000 expected=2");
    }

    @Test
    void 자릿수_대조는_값이_같아도_자릿수가_다르면_실패한다() throws Exception {
        assertThatThrownBy(() -> parity(DOUBLE_FN).verify(List.of(
                exactCase("twice.scale", "pg_temp.twice(" + lit(bd("1")) + ")", bd("2.00"), "2.00"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("twice.scale");
    }

    @Test
    void 안전하지_않은_입력은_거부한다() throws Exception {
        assertThatThrownBy(() -> valueCase("bad id", "1", BigDecimal.ONE, "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valueCase("ok.id", "1; DROP TABLE x", BigDecimal.ONE, "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lit("KR'); --")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parity(DOUBLE_FN).verify(List.of(
                valueCase("dup", "1", BigDecimal.ONE, "1"), valueCase("dup", "1", BigDecimal.ONE, "1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> parity(DOUBLE_FN).verify(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정의에_없는_함수_이름은_설치_단계에서_실패한다() throws Exception {
        Path file = dir.resolve("defs.sql");
        Files.writeString(file, DOUBLE_FN);
        assertThatThrownBy(() -> new SqlFunctionParity(container).install(file, "missing_fn"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing_fn");
        assertThatThrownBy(() -> new SqlFunctionParity(container).install(file, "bad name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이름_접두사가_같은_다른_함수를_집어오지_않는다() {
        String source = DOUBLE_FN.replace("twice", "twice_long") + DOUBLE_FN.replace("p * 2", "p * 7");
        assertThat(SqlFunctionParity.extract(source, "twice")).contains("p * 7");
    }
}

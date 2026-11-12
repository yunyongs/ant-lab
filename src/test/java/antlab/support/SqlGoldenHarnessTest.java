package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScenarioPlan;
import antlab.support.SqlGoldenScenario.ScriptStep;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 하네스의 실행·판정·격리 계약을 일회용 PostgreSQL 17 컨테이너에서 확인한다. 실제 표본이 필요한 시험은 표본이 없으면 건너뛴다. */
class SqlGoldenHarnessTest {

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;

    @BeforeAll
    static void start() {
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
    }

    @AfterAll
    static void stop() throws Exception {
        harness.close();
        container.stop();
    }

    private static void put(String path, String content) {
        harness.runner().putFile(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static ScriptStep run(String path) {
        return new ScriptStep(path, Kind.RUN, null, Duration.ofSeconds(60));
    }

    private static ScriptStep check(String path, String column) {
        return new ScriptStep(path, Kind.CHECK, column, Duration.ofSeconds(60));
    }

    private static long scalar(DatabaseHandle db, String sql) throws Exception {
        String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + db.name();
        try (Connection c = DriverManager.getConnection(url, container.getUsername(), container.getPassword());
                ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---- 메타 명령이 원본 의미대로 동작한다 ----

    @Test
    void copy_ir_gset_if가_작업_루트_기준으로_동작한다() throws Exception {
        put("meta/data.csv", "a,b\n1,x\n2,y\n3,z\n");
        put("meta/inc.sql", "CREATE TEMP TABLE inc_loaded AS SELECT 7 AS n;\n");
        put("meta/main.sql", """
                CREATE TEMP TABLE staged (a int, b text);
                \\copy staged FROM 'meta/data.csv' WITH (FORMAT csv, HEADER MATCH)
                \\ir inc.sql
                SELECT count(*) AS cnt, (SELECT n FROM inc_loaded) AS n FROM staged
                \\gset
                \\if :{?cnt}
                \\else
                  SELECT 1/0;
                \\endif
                SELECT CASE WHEN :cnt = 3 AND :n = 7 THEN 'PASS' ELSE 'FAIL' END AS meta_check;
                """);
        DatabaseHandle db = harness.createSqlDatabase("meta");

        var result = harness.run(db, check("meta/main.sql", "meta_check"));

        harness.assertCheckPassed(result, "meta_check");
    }

    @Test
    void 파일_존재_확인_shell_명령이_작업_루트에서_실행된다() throws Exception {
        put("meta/present.txt", "x");
        put("meta/shell.sql", """
                \\! test -f meta/present.txt -a ! -f meta/absent.txt
                \\if :SHELL_ERROR
                  SELECT 'FAIL' AS shell_check;
                \\else
                  SELECT 'PASS' AS shell_check;
                \\endif
                """);
        DatabaseHandle db = harness.createSqlDatabase("shell");

        harness.assertCheckPassed(harness.run(db, check("meta/shell.sql", "shell_check")), "shell_check");
    }

    // ---- 판정 계약 ----

    @Test
    void 앞쪽_PASS_뒤_마지막_FAIL은_실패다() throws Exception {
        put("verdict/midpass.sql", "SELECT 'PASS' AS v_check;\nSELECT 'FAIL' AS v_check;\n");
        DatabaseHandle db = harness.createSqlDatabase("verdict");
        var result = harness.run(db, check("verdict/midpass.sql", "v_check"));
        assertThat(result.process().exitCode()).isZero();
        assertThatThrownBy(() -> harness.assertCheckPassed(result, "v_check"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("FAIL");
    }

    @Test
    void 다른_판정_이름과_PASS_뒤_추가_결과는_실패다() throws Exception {
        put("verdict/wrongname.sql", "SELECT 'PASS' AS other_check;\n");
        put("verdict/trailing.sql", "SELECT 'PASS' AS v_check;\nSELECT 1 AS extra;\n");
        DatabaseHandle db = harness.createSqlDatabase("verdict2");
        assertThatThrownBy(() -> harness.assertCheckPassed(harness.run(db, check("verdict/wrongname.sql", "v_check")), "v_check"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> harness.assertCheckPassed(harness.run(db, check("verdict/trailing.sql", "v_check")), "v_check"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void 성공_출력_뒤에_비정상_종료하면_실패다() throws Exception {
        put("verdict/exit.sql", "SELECT 'PASS' AS v_check;\nSELECT 1/0;\n");
        DatabaseHandle db = harness.createSqlDatabase("verdict3");
        var result = harness.run(db, check("verdict/exit.sql", "v_check"));
        assertThat(result.process().exitCode()).isNotZero();
        assertThatThrownBy(() -> harness.assertCheckPassed(result, "v_check"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("종료 코드");
    }

    // ---- 오류·제한 시간 ----

    @Test
    void 오류가_나면_뒤_단계를_실행하지_않는다() throws Exception {
        put("stop/a.sql", "CREATE TABLE ran_a (n int);\n");
        put("stop/b.sql", "SELECT 1/0;\n");
        put("stop/c.sql", "CREATE TABLE ran_c (n int);\n");
        DatabaseHandle db = harness.createSqlDatabase("stop");
        ScenarioPlan plan = new ScenarioPlan("STOP", List.of(run("stop/a.sql"), run("stop/b.sql"), run("stop/c.sql")));

        assertThatThrownBy(() -> harness.execute(db, plan)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("stop/b.sql").hasMessageContaining("종료 코드");

        assertThat(scalar(db, "SELECT count(*) FROM pg_tables WHERE tablename = 'ran_a'")).isEqualTo(1);
        assertThat(scalar(db, "SELECT count(*) FROM pg_tables WHERE tablename = 'ran_c'")).isZero();
    }

    @Test
    void 오류_메시지가_담고_있는_값은_실패_메시지에_나오지_않는다() throws Exception {
        put("leak/dup.sql", """
                CREATE TABLE leak (price numeric UNIQUE);
                INSERT INTO leak VALUES (12345.6789);
                INSERT INTO leak VALUES (12345.6789);
                """);
        DatabaseHandle db = harness.createSqlDatabase("leak");
        assertThatThrownBy(() -> harness.execute(db, new ScenarioPlan("LEAK", List.of(run("leak/dup.sql")))))
                .isInstanceOf(AssertionError.class).hasMessageNotContaining("12345");
    }

    @Test
    void 제한_시간을_넘기면_실패로_처리하고_다시_실행하지_않는다() throws Exception {
        put("slow/sleep.sql", "SELECT pg_sleep(60);\n");
        DatabaseHandle db = harness.createSqlDatabase("slow");
        ScriptStep step = new ScriptStep("slow/sleep.sql", Kind.RUN, null, Duration.ofSeconds(2));
        long start = System.nanoTime();

        assertThatThrownBy(() -> harness.execute(db, new ScenarioPlan("SLOW", List.of(step))))
                .isInstanceOf(AssertionError.class).hasMessageContaining("제한 시간");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(40));
    }

    // ---- 같은 세션에서 정의 로딩과 질의 ----

    @Test
    void 정의_로딩과_질의는_같은_세션이라_임시_함수를_부를_수_있다() throws Exception {
        put("def/f_def.sql", "CREATE FUNCTION pg_temp.answer() RETURNS integer LANGUAGE sql AS 'SELECT 42';\n");
        DatabaseHandle db = harness.createSqlDatabase("def");
        RowQuery query = new RowQuery("answer", "SELECT pg_temp.answer() AS answer, 'a,\"b' AS txt, NULL::numeric AS nothing",
                List.of(new RowColumn("answer", ColumnType.LONG), new RowColumn("txt", ColumnType.TEXT),
                        new RowColumn("nothing", ColumnType.DECIMAL)));

        RowSet rows = harness.queryWithDefinitions(db, "def/f_def.sql", query, 0);

        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0, "answer")).isEqualTo(42L);
        assertThat(rows.get(0, "txt")).isEqualTo("a,\"b");
        assertThat(rows.get(0, "nothing")).isNull();
        // 별도 세션에서는 임시 함수가 없다
        var separate = harness.runner().runSql(db.name(), "SELECT pg_temp.answer()", Duration.ofSeconds(30));
        assertThat(separate.exitCode()).isNotZero();
    }

    @Test
    void 정의_질의는_NULL_빈_문자열_글자_그대로의_역슬래시N을_구분하고_한_열_NULL_행을_잃지_않는다() throws Exception {
        put("def/empty_def.sql", "SET search_path = public;\n");
        DatabaseHandle db = harness.createSqlDatabase("nulls");
        RowQuery several = new RowQuery("several", """
                SELECT * FROM (VALUES (1, NULL::text), (2, ''), (3, '\\N'), (4, 'a'), (5, E'x\ny,"z')) AS v(k, txt) ORDER BY k""",
                List.of(new RowColumn("k", ColumnType.LONG), new RowColumn("txt", ColumnType.TEXT)));

        RowSet rows = harness.queryWithDefinitions(db, "def/empty_def.sql", several, 0);

        assertThat(rows.size()).isEqualTo(5);
        assertThat(rows.get(0, "txt")).isNull();
        assertThat(rows.get(1, "txt")).isEqualTo("");
        assertThat(rows.get(2, "txt")).isEqualTo("\\N");
        assertThat(rows.get(3, "txt")).isEqualTo("a");
        assertThat(rows.get(4, "txt")).isEqualTo("x\ny,\"z");

        RowQuery oneColumn = new RowQuery("one_column",
                "SELECT only_col FROM (VALUES (1, NULL::text), (2, ''), (3, 'b')) AS v(k, only_col) ORDER BY k",
                List.of(new RowColumn("only_col", ColumnType.TEXT)));
        RowSet one = harness.queryWithDefinitions(db, "def/empty_def.sql", oneColumn, 0);
        assertThat(one.size()).as("NULL·빈 문자열 행이 사라지지 않는다").isEqualTo(3);
        assertThat(one.get(0, "only_col")).isNull();
        assertThat(one.get(1, "only_col")).isEqualTo("");
        assertThat(one.get(2, "only_col")).isEqualTo("b");
    }

    @Test
    void 정의_질의에서_숫자_열의_빈_값은_정상값으로_바꾸지_않고_실패한다() throws Exception {
        put("def/empty_def2.sql", "SET search_path = public;\n");
        DatabaseHandle db = harness.createSqlDatabase("badnum");
        RowQuery query = new RowQuery("badnum", "SELECT ''::text AS n",
                List.of(new RowColumn("n", ColumnType.LONG)));

        assertThatThrownBy(() -> harness.queryWithDefinitions(db, "def/empty_def2.sql", query, 0))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void COPY_CSV_해석기는_따옴표_없는_역슬래시N만_NULL로_읽는다() {
        var table = SqlGoldenHarness.parseCopyCsv("a,b\n\\N,\"\\N\"\n,\"\"\n\"x\"\"y\",\"l1\nl2\"\n");

        assertThat(table).hasSize(4);
        assertThat(table.get(1)).containsExactly(null, "\\N");
        assertThat(table.get(2)).containsExactly("", "");
        assertThat(table.get(3)).containsExactly("x\"y", "l1\nl2");
        assertThat(SqlGoldenHarness.parseCopyCsv("v\n\\N\n\"\"\n")).hasSize(3)
                .element(1).isEqualTo(java.util.Arrays.asList((String) null));
        assertThatThrownBy(() -> SqlGoldenHarness.parseCopyCsv("v\n\"x\n")).isInstanceOf(IllegalStateException.class);
    }

    // ---- 조회 열 타입 검증 ----

    @Test
    void 선언한_열_타입과_실제_타입이_다르면_조회가_실패한다() throws Exception {
        DatabaseHandle db = harness.createSqlDatabase("types");
        RowQuery wrong = new RowQuery("wrong", "SELECT 1.5::numeric AS n",
                List.of(new RowColumn("n", ColumnType.LONG)));
        RowQuery right = new RowQuery("right", "SELECT 1.5::numeric AS n, 2::int AS i, 'x'::text AS t, DATE '2020-01-01' AS d, true AS b",
                List.of(new RowColumn("n", ColumnType.DECIMAL), new RowColumn("i", ColumnType.LONG),
                        new RowColumn("t", ColumnType.TEXT), new RowColumn("d", ColumnType.DATE),
                        new RowColumn("b", ColumnType.BOOLEAN)));

        assertThatThrownBy(() -> harness.readRows(db, wrong, 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("실제 타입");
        assertThat(harness.readRows(db, right, 0).size()).isEqualTo(1);
    }

    @Test
    void 하네스가_만들지_않은_DB는_조회하지_않는다() {
        DatabaseHandle shared = new DatabaseHandle(container.getDatabaseName());
        RowQuery q = new RowQuery("x", "SELECT 1 AS n", List.of(new RowColumn("n", ColumnType.LONG)));

        assertThatThrownBy(() -> harness.readRows(shared, q, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.practiceSeasonId(shared)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 원문_진단_경로가_저장소_아래면_거부한다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path outside) throws Exception {
        java.nio.file.Path repo = java.nio.file.Path.of("").toAbsolutePath();
        assertThatThrownBy(() -> SqlGoldenHarness.requireOutsideRepository(repo.resolve("build/raw-dump")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlGoldenHarness.requireOutsideRepository(repo))
                .isInstanceOf(IllegalArgumentException.class);
        SqlGoldenHarness.requireOutsideRepository(outside.resolve("not-yet/created"));
    }

    @Test
    void 제한_시간_종료_코드_137도_시간_초과로_본다() throws Exception {
        put("slow/sleep2.sql", "SELECT pg_sleep(60);\n");
        DatabaseHandle db = harness.createSqlDatabase("slow137");
        var result = harness.runner().runFile(db.name(), "slow/sleep2.sql", Duration.ofSeconds(1));
        assertThat(result.timedOut()).isTrue();
    }

    // ---- DB 격리와 정리 ----

    @Test
    void 정리에_실패하면_나머지를_지운_뒤_실패한_DB를_보고한다() throws Exception {
        SqlGoldenHarness local = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
        DatabaseHandle ok = local.createSqlDatabase("cleanfail");
        // The connection database cannot be dropped from itself, so this drop must fail.
        local.trackForCleanup("postgres");

        assertThatThrownBy(local::close)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("postgres")
                .hasMessageNotContaining(ok.name());

        DatabaseHandle admin = new DatabaseHandle(container.getDatabaseName());
        assertThat(scalar(admin, "SELECT count(*) FROM pg_database WHERE datname = '" + ok.name() + "'")).isZero();
    }

    @Test
    void SQL_DB는_서로_다른_빈_DB이고_정리는_만든_DB만_지운다() throws Exception {
        SqlGoldenHarness local = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
        DatabaseHandle first = local.createSqlDatabase("iso");
        DatabaseHandle second = local.createSqlDatabase("iso");
        put("iso/t.sql", "CREATE TABLE only_here (n int);\n");
        local.run(first, run("iso/t.sql"));

        assertThat(first.name()).isNotEqualTo(second.name());
        assertThat(scalar(first, "SELECT count(*) FROM pg_tables WHERE tablename = 'only_here'")).isEqualTo(1);
        assertThat(scalar(second, "SELECT count(*) FROM pg_tables WHERE tablename = 'only_here'")).isZero();
        assertThat(scalar(second, "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")).isZero();
        assertThatThrownBy(() -> local.run(new DatabaseHandle("postgres"), run("iso/t.sql")))
                .isInstanceOf(IllegalArgumentException.class);

        local.close();

        DatabaseHandle admin = new DatabaseHandle(container.getDatabaseName());
        assertThat(scalar(admin, "SELECT count(*) FROM pg_database WHERE datname IN ('" + first.name() + "', '" + second.name() + "')"))
                .isZero();
        assertThat(scalar(admin, "SELECT count(*) FROM pg_database WHERE datname = '" + container.getDatabaseName() + "'"))
                .isEqualTo(1);
    }

    @Test
    void 안전하지_않은_경로와_DB_이름은_거부한다() {
        assertThatThrownBy(() -> harness.runner().runFile("test", "../etc/passwd", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.runner().runFile("test", "/abs.sql", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.runner().runFile("a; drop", "db/x.sql", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.runner().runFile("test", "db/x.sql; rm -rf /", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 실제 표본 골든 ----

    @Test
    @Tag("sql-golden")
    @Timeout(value = 25, unit = java.util.concurrent.TimeUnit.MINUTES)
    void README_회귀_모든_등록_검사가_PASS다() throws Exception {
        harness.inspectFixtures().requireOrSkip();
        var bundle = harness.stageInputs();
        DatabaseHandle db = harness.createSqlDatabase("readme");

        var result = harness.execute(db, SqlGoldenScenario.readmeRegression());

        assertThat(result.steps()).hasSize(SqlGoldenScenario.readmeRegression().steps().size());
        assertThat(bundle.files()).isNotEmpty();
        long checks = SqlGoldenScenario.readmeRegression().steps().stream().filter(s -> s.kind() == Kind.CHECK).count();
        assertThat(checks).isEqualTo(27);
    }
}

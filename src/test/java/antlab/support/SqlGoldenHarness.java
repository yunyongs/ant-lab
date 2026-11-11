package antlab.support;

import antlab.support.GoldenFixtureSource.InputBundle;
import antlab.support.GoldenFixtureSource.StagedFile;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowQuery;
import antlab.support.SeasonReplayInput.ReplayAction;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScenarioPlan;
import antlab.support.SqlGoldenScenario.ScriptStep;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * SQL 골든 하네스(시험 전용). 실행 중인 시험 컨테이너 하나 안에서 역할별로 서로 다른 DB 를 만들고,
 * 저장소의 SQL 원본을 psql 로 그대로 실행한 뒤 결과 행을 JDBC 로 읽어 비교하게 한다.
 *
 * <p>임의 JDBC URL·호스트는 받지 않는다. 하네스가 만들어 등록한 DB 만 지운다. 스크립트 실행 결과의 원문 출력은
 * assertion 메시지와 로그에 싣지 않고, 종료 코드·판정 분류·시간만 남긴다. 컨테이너의 수명은 호출한 쪽이 정한다.
 */
public final class SqlGoldenHarness implements AutoCloseable {

    /** 하네스가 다루는 DB 의 이름 표지 */
    public record DatabaseHandle(String name) {
    }

    /** 단계 하나의 결과. 원문 출력은 필드로만 들고 있다. */
    public record ScriptResult(ScriptStep step, PsqlScriptRunner.Result process, String verdictReason) {
        @Override
        public String toString() {
            return "ScriptResult[" + step.path() + ", " + process + "]";
        }
    }

    public record ScenarioResult(String name, List<ScriptResult> steps, Duration elapsed) {
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Duration ADMIN_TIMEOUT = Duration.ofMinutes(1);

    private final PostgreSQLContainer container;
    private final PsqlScriptRunner runner;
    private final GoldenFixtureSource source;
    private final Set<String> created = new LinkedHashSet<>();
    private InputBundle staged;

    public SqlGoldenHarness(PostgreSQLContainer container, GoldenFixtureSource source) {
        this.container = container;
        this.runner = new PsqlScriptRunner(container);
        this.source = source;
    }

    /** 하네스 전용 일회용 컨테이너 (일반 통합 시험의 공유 컨테이너와 따로 쓴다) */
    public static PostgreSQLContainer newContainer() {
        return new PostgreSQLContainer(PostgresTestcontainersConfig.IMAGE)
                .withEnv("TZ", "Asia/Seoul")
                .withEnv("PGTZ", "Asia/Seoul");
    }

    public PsqlScriptRunner runner() {
        return runner;
    }

    public GoldenFixtureSource.FixtureAvailability inspectFixtures() {
        return source.inspect();
    }

    /** 입력 묶음을 한 번만 읽어 컨테이너 작업 루트에 놓는다. 이후 같은 묶음을 쓴다. */
    public InputBundle stageInputs() throws Exception {
        if (staged == null) {
            InputBundle bundle = source.collect();
            for (StagedFile file : bundle.files()) {
                runner.putFile(file.relativePath(), file.content());
            }
            staged = bundle;
            System.out.println("[sql-golden] 입력 " + bundle);
        }
        return staged;
    }

    /** 빈 DB 를 새로 만든다. SQL 기준 DB 에는 Flyway 를 실행하지 않는다. */
    public DatabaseHandle createSqlDatabase(String scenario) throws Exception {
        String name = "golden_" + scenario.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + SEQ.incrementAndGet();
        PsqlScriptRunner.requireSafeDatabase(name);
        mustSucceed(runner.runSql("postgres",
                "CREATE DATABASE " + name + " TEMPLATE template0 ENCODING 'UTF8'", ADMIN_TIMEOUT), "DB 생성");
        created.add(name);
        mustSucceed(runner.runSql("postgres",
                "ALTER DATABASE " + name + " SET timezone TO 'Asia/Seoul'", ADMIN_TIMEOUT), "DB 시간대");
        return new DatabaseHandle(name);
    }

    public ScriptResult run(DatabaseHandle database, ScriptStep step) throws Exception {
        requireOwned(database);
        PsqlScriptRunner.Result result = runner.runFile(database.name(), step.path(), step.timeout(), step.variables());
        dumpRawOutputIfRequested(database, step, result);
        String reason = null;
        if (step.kind() == Kind.CHECK && result.exitCode() == 0) {
            reason = CheckVerdictParser.parse(result.stdout(), step.verdictColumn()).reason();
        }
        return new ScriptResult(step, result, reason);
    }

    /** 종료 코드가 0이고 마지막 판정이 등록한 열의 PASS 일 때만 통과다. */
    public void assertCheckPassed(ScriptResult result, String verdictColumn) {
        String path = result.step().path();
        if (result.process().timedOut()) {
            throw new AssertionError(path + ": 제한 시간 초과");
        }
        if (result.process().exitCode() != 0) {
            throw new AssertionError(path + ": 종료 코드 " + result.process().exitCode());
        }
        CheckVerdictParser.Verdict verdict = CheckVerdictParser.parse(result.process().stdout(), verdictColumn);
        if (!verdict.passed()) {
            throw new AssertionError(path + " (" + verdictColumn + "): " + verdict.reason());
        }
    }

    /** 계획을 순서대로 실행한다. 첫 실패에서 멈춘다(재실행하지 않는다). */
    public ScenarioResult execute(DatabaseHandle database, ScenarioPlan plan) throws Exception {
        long start = System.nanoTime();
        List<ScriptResult> results = new ArrayList<>();
        for (ScriptStep step : plan.steps()) {
            ScriptResult result = run(database, step);
            results.add(result);
            System.out.println("[sql-golden] " + plan.name() + " " + step.path() + " " + result.process().elapsed().toMillis()
                    + "ms");
            if (step.kind() == Kind.CHECK) {
                assertCheckPassed(result, step.verdictColumn());
            } else {
                if (result.process().timedOut()) {
                    throw new AssertionError(step.path() + ": 제한 시간 초과");
                }
                if (result.process().exitCode() != 0) {
                    throw new AssertionError(step.path() + ": 종료 코드 " + result.process().exitCode());
                }
            }
        }
        Duration total = Duration.ofNanos(System.nanoTime() - start);
        System.out.println("[sql-golden] " + plan.name() + " 전체 " + total.toSeconds() + "s");
        return new ScenarioResult(plan.name(), List.copyOf(results), total);
    }

    // ---- 행 조회 ----

    public long practiceSeasonId(DatabaseHandle database) throws Exception {
        requireOwned(database);
        try (Connection c = connect(database); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(GoldenRowQueries.PRACTICE_SEASON_SQL)) {
            if (!rs.next()) {
                throw new IllegalStateException("연습 시즌이 없다");
            }
            long id = rs.getLong(1);
            if (rs.next()) {
                throw new IllegalStateException("연습 시즌이 둘 이상이다");
            }
            return id;
        }
    }

    /** 타입을 보존한 읽기 전용 조회. 결과 열 이름·순서가 선언과 다르면 실패한다. */
    public RowSet readRows(DatabaseHandle database, RowQuery query, long seasonId) throws Exception {
        requireOwned(database);
        String sql = query.sql().replace(":sid", Long.toString(seasonId));
        try (Connection c = connect(database)) {
            c.setReadOnly(true);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                if (meta.getColumnCount() != query.columns().size()) {
                    throw new IllegalStateException(query.name() + ": 결과 열 수가 선언과 다르다");
                }
                for (int i = 0; i < query.columns().size(); i++) {
                    if (!meta.getColumnLabel(i + 1).equals(query.columns().get(i).name())) {
                        throw new IllegalStateException(query.name() + ": " + (i + 1) + "번째 열 이름이 선언과 다르다");
                    }
                    requireColumnType(query, i, meta.getColumnTypeName(i + 1));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 0; i < query.columns().size(); i++) {
                        row.add(readCell(rs, i + 1, query.columns().get(i).type()));
                    }
                    rows.add(row);
                }
                return new RowSet(query.columns(), rows);
            }
        }
    }

    /**
     * 선언한 열 타입과 실제 PostgreSQL 타입이 맞는지 확인한다. 어긋나면 조용히 값을 바꾸지 않고 실패한다
     * (예: LONG 으로 선언했는데 NUMERIC 이면 getLong 이 소수부를 버린다).
     */
    static void requireColumnType(RowQuery query, int index, String pgTypeName) {
        ColumnType declared = query.columns().get(index).type();
        Set<String> allowed = switch (declared) {
            case TEXT -> Set.of("text", "varchar", "bpchar");
            case LONG -> Set.of("int2", "int4", "int8");
            case DECIMAL -> Set.of("numeric");
            case DATE -> Set.of("date");
            case BOOLEAN -> Set.of("bool");
            case JSON -> Set.of("jsonb", "json", "text", "varchar");
        };
        if (!allowed.contains(pgTypeName.toLowerCase())) {
            throw new IllegalStateException(query.name() + ": " + (index + 1) + "번째 열(" + query.columns().get(index).name()
                    + ")의 실제 타입이 선언(" + declared + ")과 맞지 않는다");
        }
    }

    private static Object readCell(ResultSet rs, int index, ColumnType type) throws SQLException {
        return switch (type) {
            case TEXT -> rs.getString(index);
            case LONG -> {
                long v = rs.getLong(index);
                yield rs.wasNull() ? null : Long.valueOf(v);
            }
            case DECIMAL -> rs.getBigDecimal(index);
            case DATE -> rs.getObject(index, LocalDate.class);
            case BOOLEAN -> {
                boolean v = rs.getBoolean(index);
                yield rs.wasNull() ? null : Boolean.valueOf(v);
            }
            case JSON -> RowSet.parseCell(ColumnType.JSON, rs.getString(index));
        };
    }

    /**
     * 정의 파일을 읽은 같은 psql 세션에서 질의한다 (임시 함수·뷰는 세션이 끝나면 사라진다).
     * 결과는 CSV 로 받아 선언한 열 타입으로 해석한다.
     */
    public RowSet queryWithDefinitions(DatabaseHandle database, String definitionPath, RowQuery query, long seasonId)
            throws Exception {
        requireOwned(database);
        String sql = query.sql().replace(":sid", Long.toString(seasonId));
        PsqlScriptRunner.Result result = runner.runDefinitionQuery(database.name(), definitionPath, sql,
                Duration.ofMinutes(5));
        if (result.timedOut() || result.exitCode() != 0) {
            throw new AssertionError(query.name() + ": 정의 질의 실패 (종료 코드 " + result.exitCode() + ")");
        }
        List<List<String>> table = parseCopyCsv(result.stdout());
        if (table.isEmpty() || table.get(0).size() != query.columns().size()) {
            throw new IllegalStateException(query.name() + ": 결과 열 수가 선언과 다르다");
        }
        for (int i = 0; i < query.columns().size(); i++) {
            if (!table.get(0).get(i).equals(query.columns().get(i).name())) {
                throw new IllegalStateException(query.name() + ": " + (i + 1) + "번째 열 이름이 선언과 다르다");
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (List<String> csvRow : table.subList(1, table.size())) {
            if (csvRow.size() != query.columns().size()) {
                throw new IllegalStateException(query.name() + ": 행의 칸 수가 선언과 다르다");
            }
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < csvRow.size(); i++) {
                // null = 따옴표 없는 \N (SQL NULL). 빈 문자열은 그대로 빈 문자열이다 (숫자·날짜 열이면 해석에서 실패한다).
                String text = csvRow.get(i);
                row.add(text == null ? null : RowSet.parseCell(query.columns().get(i).type(), text));
            }
            rows.add(row);
        }
        return new RowSet(query.columns(), rows);
    }

    /**
     * {@code COPY … TO STDOUT WITH (FORMAT csv, HEADER true, NULL '\N')} 출력 해석기.
     * 머리글 뒤의 줄은 모두 행이다(끝 개행 하나만 제외). 따옴표 없는 {@code \N} 만 NULL(null)이고,
     * 따옴표로 감싼 {@code "\N"} 이나 빈 칸·{@code ""} 는 문자열이다. 따옴표 안의 쉼표·줄바꿈·"" 는 값으로 읽는다.
     */
    static List<List<String>> parseCopyCsv(String text) {
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellWasQuoted = false;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            pending = true;
            if (ch == '"') {
                quoted = true;
                cellWasQuoted = true;
            } else if (ch == ',') {
                row.add(cellValue(cell, cellWasQuoted));
                cell.setLength(0);
                cellWasQuoted = false;
            } else if (ch == '\n') {
                row.add(cellValue(cell, cellWasQuoted));
                table.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
                cellWasQuoted = false;
                pending = false;
            } else {
                cell.append(ch);
            }
        }
        if (quoted) {
            throw new IllegalStateException("닫히지 않은 따옴표");
        }
        if (pending) {
            row.add(cellValue(cell, cellWasQuoted));
            table.add(row);
        }
        return table;
    }

    private static String cellValue(StringBuilder cell, boolean wasQuoted) {
        String value = cell.toString();
        return !wasQuoted && value.equals("\\N") ? null : value;
    }

    /**
     * 시즌의 경로·설정·행동 순서를 재생 입력으로 꺼낸다. 20·21·23·25 가 만든 행동(주문·전술 변경·라인업 변경과 턴 전환)을 지원한다.
     * 행동은 턴별로 교차한다: 턴 k 의 행동 → 새 턴 k+1 로의 전환 → … 현재 턴의 행동은 맨 끝이다.
     *
     * <p>같은 턴 안의 순서: 주문과 전술 변경은 기록 시각({@code created_at}, 같은 DB 의 시계)으로 병합한다. 서로 다른 표의 번호는
     * 전역 순번이 아니므로 비교하지 않고, 시각이 같으면 순서를 정할 수 없어 입력 오류로 실패한다.
     * 라인업 변경은 시각이 없어(저장 결과가 구간 경계뿐) 그 턴의 주문·전술 변경 <b>뒤</b>에 둔다 — 기본 25 시나리오의 실제 순서(⑥)다.
     * 라인업 변경으로 보는 조건: 이전 구간이 그 턴에 닫히며 새 구간이 열렸는데 그 종목의 그 턴 매수 주문이 없는 경우. 매수 주문이 있으면
     * 매수로 연 구간과 구분할 수 없어 이 입력으로는 복원하지 않는다(그런 확장 드릴은 행동 목록을 따로 만들고 저장 결과와 비교한다).
     * 0턴 주문만 있는 시즌은 예전과 같이 "주문 전부 → 전환 1..현재" 순서가 된다.
     */
    public SeasonReplayInput captureReplayInput(DatabaseHandle database, long seasonId) throws Exception {
        RowSet settings = readRows(database, GoldenRowQueries.season().query(), seasonId);
        RowSet path = readRows(database, GoldenRowQueries.seasonPath().query(), seasonId);
        Map<Long, String> orderKeys = new LinkedHashMap<>();
        long currentTurn = ((Long) settings.get(0, "current_turn_no"));

        /** 한 턴의 시각 있는 행동(주문·전술 변경). */
        record Timed(long turn, java.time.Instant at, ReplayAction action) {
        }
        List<Timed> timed = new ArrayList<>();
        List<SeasonReplayInput.LineupChange> lineupChanges = new ArrayList<>();
        try (Connection c = connect(database); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(GoldenRowQueries.orderCaptureSql(seasonId))) {
                while (rs.next()) {
                    String key = GoldenRowQueries.orderKey(rs.getLong("turn_no"), rs.getString("market"),
                            rs.getString("ticker"), rs.getString("side"), rs.getLong("nth"));
                    orderKeys.put(rs.getLong("id"), key);
                    timed.add(new Timed(rs.getLong("turn_no"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                            new SeasonReplayInput.PlaceOrder(key, rs.getLong("quantity"))));
                }
            }
            try (ResultSet rs = st.executeQuery(GoldenRowQueries.formationChangeCaptureSql(seasonId))) {
                while (rs.next()) {
                    timed.add(new Timed(rs.getLong("turn_no"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                            new SeasonReplayInput.FormationChange(rs.getLong("turn_no"), rs.getLong("nth"),
                                    new SeasonReplayInput.Shape(rs.getInt("after_goalkeeper"), rs.getInt("after_defender"),
                                            rs.getInt("after_midfielder"), rs.getInt("after_forward")),
                                    rs.getString("reason"))));
                }
            }
            try (ResultSet rs = st.executeQuery(GoldenRowQueries.lineupChangeCaptureSql(seasonId))) {
                while (rs.next()) {
                    lineupChanges.add(new SeasonReplayInput.LineupChange(rs.getLong("start_turn"),
                            rs.getString("market") + "/" + rs.getString("ticker"), rs.getString("declared_position")));
                }
            }
        }
        // 턴 → 같은 시각의 동률 검사 → 시각 순
        timed.sort(java.util.Comparator.comparingLong(Timed::turn).thenComparing(Timed::at));
        for (int i = 1; i < timed.size(); i++) {
            Timed prev = timed.get(i - 1);
            Timed cur = timed.get(i);
            if (prev.turn() == cur.turn() && prev.at().equals(cur.at())) {
                throw new IllegalStateException("같은 턴에 기록 시각이 같은 행동이 있어 순서를 정할 수 없다: 턴 " + cur.turn());
            }
        }

        List<ReplayAction> actions = new ArrayList<>();
        for (long turn = 0; turn <= currentTurn; turn++) {
            if (turn > 0) {
                actions.add(new SeasonReplayInput.AdvanceTurn(turn));
            }
            for (Timed t : timed) {
                if (t.turn() == turn) {
                    actions.add(t.action());
                }
            }
            for (SeasonReplayInput.LineupChange change : lineupChanges) {
                if (change.turn() == turn) {
                    actions.add(change);
                }
            }
        }
        for (Timed t : timed) {
            if (t.turn() > currentTurn) {
                throw new IllegalStateException("현재 턴보다 늦은 턴의 행동이 있다");
            }
        }
        return new SeasonReplayInput(settings, path, List.copyOf(actions), orderKeys);
    }

    // ---- 내부 ----

    private Connection connect(DatabaseHandle database) throws SQLException {
        String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/"
                + database.name();
        return DriverManager.getConnection(url, container.getUsername(), container.getPassword());
    }

    private void requireOwned(DatabaseHandle database) {
        if (!created.contains(database.name())) {
            throw new IllegalArgumentException("하네스가 다루는 DB 가 아니다");
        }
    }

    private static void mustSucceed(PsqlScriptRunner.Result result, String what) {
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException(what + " 실패 (종료 코드 " + result.exitCode() + ")");
        }
    }

    /**
     * 로컬 진단용. ANTLAB_GOLDEN_RAW_DIR 이 있을 때만 원문 출력을 그 디렉터리의 파일로 쓴다(시험 보고서·assertion 에는 싣지 않는다).
     * 가격 유래 값이 들어 있을 수 있으니 저장소 밖 경로를 지정하고 커밋하지 않는다.
     */
    private static void dumpRawOutputIfRequested(DatabaseHandle database, ScriptStep step, PsqlScriptRunner.Result result)
            throws java.io.IOException {
        String dir = System.getenv("ANTLAB_GOLDEN_RAW_DIR");
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path target = Path.of(dir);
        requireOutsideRepository(target);
        Files.createDirectories(target);
        String base = database.name() + "_" + step.path().replace('/', '_') + "_" + System.nanoTime();
        Files.writeString(target.resolve(base + ".out"), result.stdout(), StandardCharsets.UTF_8);
        Files.writeString(target.resolve(base + ".err"), result.stderr(), StandardCharsets.UTF_8);
    }

    /** 원문 진단 출력은 가격 유래 값을 담을 수 있으므로 저장소 아래(커밋될 수 있는 곳)에는 쓰지 않는다. */
    static void requireOutsideRepository(Path target) throws java.io.IOException {
        Path repo = Path.of("").toAbsolutePath().toRealPath();
        Path existing = target.toAbsolutePath().normalize();
        Path rest = Path.of("");
        while (existing != null && !Files.exists(existing)) {
            rest = existing.getFileName().resolve(rest);
            existing = existing.getParent();
        }
        Path resolved = (existing == null ? target.toAbsolutePath() : existing.toRealPath()).resolve(rest).normalize();
        if (resolved.startsWith(repo)) {
            throw new IllegalArgumentException("ANTLAB_GOLDEN_RAW_DIR 는 저장소 밖 경로여야 한다");
        }
    }

    /** 하네스가 만든 DB 만 지운다. */
    @Override
    public void close() throws Exception {
        for (String name : created) {
            runner.runSql("postgres", "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)", ADMIN_TIMEOUT);
        }
        created.clear();
    }
}

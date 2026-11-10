package antlab.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 시험 컨테이너 안에서 psql 로 저장소의 SQL 원본을 그대로 실행한다.
 *
 * <p>고정 작업 루트({@value #WORK_ROOT}) 아래에 {@code db/}·{@code events/} 구조를 두고 그 디렉터리에서 psql 을 시작한다.
 * {@code \copy} 는 작업 디렉터리, {@code \ir} 은 포함하는 파일의 위치를 기준으로 풀리므로 원본 구조를 보존해야 한다.
 * 입력값은 셸 명령 문자열에 이어 붙이지 않고 인자로만 넘긴다. 컨테이너가 아닌 DB 에는 접속하지 않는다.
 */
public final class PsqlScriptRunner {

    public static final String WORK_ROOT = "/work";

    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]*");
    private static final Pattern SAFE_DB = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    /** timeout 명령이 시간 초과로 끝날 때의 종료 코드 */
    private static final int TIMEOUT_EXIT = 124;
    /** 유예 시간 뒤에도 끝나지 않아 KILL 로 끝났을 때의 종료 코드 (128 + 9) */
    private static final int KILLED_EXIT = 137;

    /** 실행 결과. 원문 출력은 필드로만 들고 있고 {@code toString} 에는 싣지 않는다. */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, Duration elapsed) {
        @Override
        public String toString() {
            return "Result[exit=" + exitCode + ", timedOut=" + timedOut + ", elapsed=" + elapsed.toSeconds() + "s]";
        }
    }

    private final PostgreSQLContainer container;

    public PsqlScriptRunner(PostgreSQLContainer container) {
        this.container = container;
    }

    /** 작업 루트 아래에 파일 하나를 둔다. */
    public void putFile(String relativePath, byte[] content) {
        requireSafePath(relativePath);
        container.copyFileToContainer(Transferable.of(content), WORK_ROOT + "/" + relativePath);
    }

    public void putFile(String relativePath, Path hostFile) throws java.io.IOException {
        putFile(relativePath, Files.readAllBytes(hostFile));
    }

    /** 작업 루트 기준 상대 경로의 SQL 파일을 {@code -f} 로 실행한다. */
    public Result runFile(String database, String relativePath, Duration timeout) throws Exception {
        requireSafePath(relativePath);
        return exec(database, timeout, List.of("-f", relativePath));
    }

    /** SQL 한 문장을 {@code -c} 로 실행한다. 호출하는 시험 코드의 상수만 넘긴다. */
    public Result runSql(String database, String sql, Duration timeout) throws Exception {
        return exec(database, timeout, List.of("-c", sql));
    }

    /**
     * 정의 파일을 읽은 같은 psql 세션에서 질의한다. 정의가 만드는 임시 함수·뷰는 세션이 끝나면 사라지므로
     * 로딩과 질의를 한 프로세스에서 순서대로 실행한다. 질의는 {@code COPY (…) TO STDOUT} 의 CSV(머리글 포함)로 받으며,
     * NULL 은 따옴표 없는 {@code \N}, 빈 문자열은 빈 칸(한 열 질의에서는 {@code ""})으로 구분되어 나온다.
     * 질의는 세미콜론 없는 SELECT 한 문장이어야 한다.
     */
    public Result runDefinitionQuery(String database, String definitionPath, String sql, Duration timeout)
            throws Exception {
        requireSafePath(definitionPath);
        String copy = "COPY (" + sql + ") TO STDOUT WITH (FORMAT csv, HEADER true, NULL '\\N')";
        return exec(database, timeout, List.of("-f", definitionPath, "-c", copy));
    }

    private Result exec(String database, Duration timeout, List<String> actions) throws Exception {
        requireSafeDatabase(database);
        List<String> cmd = new ArrayList<>(List.of(
                "sh", "-c", "cd \"$1\" && shift && exec \"$@\"", "sh", WORK_ROOT,
                "timeout", "-k", "5", String.valueOf(Math.max(1, timeout.toSeconds())),
                "psql", "-X", "-q", "-A", "-P", "footer=off", "-v", "ON_ERROR_STOP=1",
                "-U", container.getUsername(), "-d", database));
        cmd.addAll(actions);
        long start = System.nanoTime();
        ExecResult result = container.execInContainer(cmd.toArray(new String[0]));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        boolean timedOut = result.getExitCode() == TIMEOUT_EXIT || result.getExitCode() == KILLED_EXIT;
        return new Result(result.getExitCode(), result.getStdout(), result.getStderr(), timedOut, elapsed);
    }

    static void requireSafePath(String relativePath) {
        if (!SAFE_PATH.matcher(relativePath).matches() || relativePath.contains("..")) {
            throw new IllegalArgumentException("허용되지 않는 상대 경로: " + relativePath);
        }
    }

    static void requireSafeDatabase(String database) {
        if (!SAFE_DB.matcher(database).matches()) {
            throw new IllegalArgumentException("허용되지 않는 DB 이름: " + database);
        }
    }
}

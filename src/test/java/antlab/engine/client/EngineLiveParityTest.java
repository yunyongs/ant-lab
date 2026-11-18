package antlab.engine.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.engine.GeneratedPath;
import antlab.engine.HistoryDigest;
import antlab.engine.PathRequest;
import antlab.engine.PathRules;
import antlab.engine.SimpleBootstrap;
import antlab.history.ReplayMonth;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opt-in parity run against a real engine and the database it reads. It is skipped unless all of
 * ANTLAB_ENGINE_LIVE_URL, ANTLAB_ENGINE_LIVE_TOKEN and ANTLAB_ENGINE_LIVE_DB_URL are set (a JDBC URL that carries its own
 * credentials), so the default suite never calls an engine. Both endpoints must be loopback and on an isolated port from
 * 56100 up: the owner's own stack (5432, 8000, 8080) is refused, not skipped. Optional ANTLAB_ENGINE_LIVE_BASE_DATE
 * (default 2026-01-02) is the season base date. Gradle caches a test run by its inputs only, so add --rerun-tasks when
 * the environment changes.
 *
 * <ul>
 *   <li>L1: for 50 seeds and lengths 12, 24 and 60, the engine's {@code p0-boot6-v1} months equal what
 *       {@code SimpleBootstrap} picks from the same database, and the history digests agree.</li>
 *   <li>L2: {@code m2-sb6-v1} answers carry the same digest and obey the path rules the Spring path source checks.</li>
 *   <li>L3: one projection answer is accepted by the strict client records (they mirror {@code docs/api/engine.yaml}).</li>
 * </ul>
 * The won prices are covered by the tracked vectors, which need no engine, so they are not repeated here.
 */
class EngineLiveParityTest {

    private static final int MIN_PORT = 56100;
    private static final List<Integer> LENGTHS = List.of(12, 24, 60);
    private static final int SEEDS = 50;
    private static final Pattern PORT = Pattern.compile("^[a-z]+(?::[a-z]+)?://[^/:?]+:(\\d+)[/?]?");

    private EngineClient client;
    private List<ReplayMonth> replayable;
    private Map<LocalDate, Long> idByMonth;
    private LocalDate baseDate;
    private final List<String> tickers = new ArrayList<>();

    @BeforeEach
    void connect() throws SQLException {
        String url = System.getenv("ANTLAB_ENGINE_LIVE_URL");
        String token = System.getenv("ANTLAB_ENGINE_LIVE_TOKEN");
        String dbUrl = System.getenv("ANTLAB_ENGINE_LIVE_DB_URL");
        assumeTrue(url != null && !url.isBlank() && token != null && !token.isBlank() && dbUrl != null && !dbUrl.isBlank(),
                "live parity is opt-in: set ANTLAB_ENGINE_LIVE_URL, ANTLAB_ENGINE_LIVE_TOKEN and ANTLAB_ENGINE_LIVE_DB_URL");
        requireIsolated("ANTLAB_ENGINE_LIVE_URL", url);
        requireIsolated("ANTLAB_ENGINE_LIVE_DB_URL", dbUrl.replaceFirst("^jdbc:", ""));

        EngineProperties properties = new EngineProperties(url, token, Duration.ofSeconds(2), Duration.ofSeconds(10),
                Duration.ofSeconds(60), Duration.ofSeconds(60), EngineProperties.PathSourceMode.JAVA, "m2-sb6-v1", 2000);
        client = EngineClientConfig.newClient(properties, ObservationRegistry.NOOP);
        String date = System.getenv("ANTLAB_ENGINE_LIVE_BASE_DATE");
        baseDate = date == null || date.isBlank() ? LocalDate.of(2026, 1, 2) : LocalDate.parse(date);

        replayable = new ArrayList<>();
        idByMonth = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(dbUrl); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select id, month_start from month_block order by month_start")) {
                while (rs.next()) {
                    LocalDate start = rs.getDate(2).toLocalDate();
                    if (!start.isBefore(SimpleBootstrap.REPLAY_START) && start.isBefore(SimpleBootstrap.replayUpperBound(baseDate))) {
                        replayable.add(new ReplayMonth(rs.getLong(1), start));
                        idByMonth.put(start, rs.getLong(1));
                    }
                }
            }
            try (ResultSet rs = statement.executeQuery("select market || ':' || ticker from asset order by id limit 3")) {
                while (rs.next()) {
                    tickers.add(rs.getString(1));
                }
            }
        }
        assertThat(replayable).as("the live database has no replayable months").isNotEmpty();
    }

    static void requireIsolated(String name, String url) {
        Matcher matcher = PORT.matcher(url.toLowerCase());
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) < MIN_PORT) {
            throw new IllegalStateException(name + " must name an isolated port from " + MIN_PORT + " up");
        }
        String host = url.contains("://") ? URI.create(url.replaceFirst("\\?.*$", "")).getHost() : "";
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalStateException(name + " must be a loopback address");
        }
    }

    private record Outcome(List<Long> ids, ErrorCode failure) {
    }

    private Outcome javaPath(long seed, int length) {
        try {
            GeneratedPath path = SimpleBootstrap.generate(replayable, new PathRequest(baseDate, length, seed));
            return new Outcome(path.monthBlockIds(), null);
        } catch (DomainException e) {
            return new Outcome(null, e.getCode());
        }
    }

    private Outcome enginePath(String version, long seed, int length) {
        try {
            EnginePathResult result = client.createPath(EnginePathRequest.of(version, seed, length, baseDate));
            assertThat(result.engineVersion()).isEqualTo(version);
            assertThat(result.historyDigest()).as("history digest")
                    .isEqualTo(HistoryDigest.of(version, replayable.stream().map(ReplayMonth::monthStart).toList(), baseDate));
            List<Long> ids = new ArrayList<>();
            List<LocalDate> months = new ArrayList<>();
            for (String text : result.months()) {
                LocalDate month = LocalDate.parse(text + "-01");
                months.add(month);
                ids.add(idByMonth.get(month));
            }
            assertThat(ids).as("every engine month exists in the database").doesNotContainNull();
            List<String> broken = version.equals(SimpleBootstrap.ENGINE_VERSION)
                    ? PathRules.violations(months, baseDate, length, SimpleBootstrap.BLOCK_LENGTH)
                    : PathRules.violations(months, baseDate, length).stream().filter(rule -> !"S4".equals(rule)).toList();
            assertThat(broken).as("path rules of " + version).isEmpty();
            return new Outcome(ids, null);
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (DomainException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG);
            return new Outcome(null, e.getCode());
        }
    }

    @Test
    void L1_엔진의_p0_달이_같은_DB의_Java_부트스트랩과_같다() {
        int compared = 0;
        for (int length : LENGTHS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                Outcome java = javaPath(seed, length);
                Outcome engine = enginePath(SimpleBootstrap.ENGINE_VERSION, seed, length);
                assertThat(engine).as("seed=" + seed + " length=" + length).isEqualTo(java);
                if (java.ids() != null) {
                    compared++;
                }
            }
        }
        assertThat(compared).as("compared paths").isGreaterThanOrEqualTo(SEEDS);
    }

    @Test
    void L2_엔진의_m2_경로가_digest와_경로_규칙을_지킨다() {
        int accepted = 0;
        for (int length : LENGTHS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                if (enginePath("m2-sb6-v1", seed, length).ids() != null) {
                    accepted++;
                }
            }
        }
        assertThat(accepted).as("accepted m2 paths").isGreaterThanOrEqualTo(SEEDS);
    }

    @Test
    void L3_예상_분포_응답이_엄격한_클라이언트_레코드로_읽힌다() {
        assertThat(tickers).as("the live database needs assets").hasSizeGreaterThanOrEqualTo(2);
        EngineProjectionRequest request = new EngineProjectionRequest("14", baseDate, 60, 100, "-0.20000000", List.of(12, 60),
                List.of(new EngineProjectionRequest.Portfolio("TEAM", "0.400000000000", List.of(
                        new EngineProjectionRequest.Holding(tickers.get(0), "0.300000000000"),
                        new EngineProjectionRequest.Holding(tickers.get(1), "0.300000000000")))));
        EngineProjectionResult result = client.createProjection(request);
        assertThat(result.horizonTurns()).isEqualTo(60);
        assertThat(result.n()).isEqualTo(100);
        assertThat(result.portfolios()).hasSize(1);
        assertThat(result.portfolios().get(0).bands()).hasSize(60);
    }
}

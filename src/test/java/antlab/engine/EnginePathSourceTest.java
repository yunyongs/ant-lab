package antlab.engine;

import static antlab.support.EnginePathAnswers.body;
import static antlab.support.EnginePathAnswers.digestOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineProperties;
import antlab.engine.client.EngineUnavailableException;
import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * The engine path source against a fake engine and the synthetic month list of the database (not the real sample).
 * What the engine says is checked before anything is stored: version, history digest, every month known, path rules.
 * The database rolls back after each test.
 */
@IntegrationTest
@Transactional
@Sql("/fixtures/replay_months.sql")
@ExtendWith(OutputCaptureExtension.class)
class EnginePathSourceTest {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final LocalDate BASE = SyntheticMonths.BASE_DATE;
    private static final String P0 = "p0-boot6-v1";
    private static final String M2 = "m2-sb6-v1";
    private static final String PATHS = "/v1/paths";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
    }

    @AfterAll
    static void stop() {
        ENGINE.close();
    }

    @Autowired
    EngineClient client;

    @Autowired
    MonthBlockRepository repository;

    @Autowired
    BootstrapPathSource bootstrap;

    private List<ReplayMonth> replayable;
    private Map<LocalDate, Long> idByMonth;

    @BeforeEach
    void load() {
        ENGINE.reset();
        replayable = repository.findReplayMonths(SimpleBootstrap.REPLAY_START, SimpleBootstrap.replayUpperBound(BASE));
        idByMonth = new HashMap<>();
        replayable.forEach(m -> idByMonth.put(m.monthStart(), m.monthBlockId()));
    }

    private EnginePathSource source(String version) {
        EngineProperties properties = new EngineProperties(ENGINE.url(), TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofSeconds(15), EngineProperties.PathSourceMode.ENGINE, version, 2000);
        return new EnginePathSource(client, properties, repository);
    }

    private void answer(String version, List<LocalDate> months, String digest) {
        ENGINE.respond(PATHS, 200, "application/json", body(version, months, digest));
    }

    private String rightDigest(String version) {
        return digestOf(version, replayable, BASE);
    }

    private static List<LocalDate> run(String from, int count) {
        List<LocalDate> months = new ArrayList<>();
        LocalDate start = LocalDate.parse(from + "-01");
        for (int i = 0; i < count; i++) {
            months.add(start.plusMonths(i));
        }
        return months;
    }

    /** 12 months that are in the database and in range but follow no calendar: allowed for m2, not for p0. */
    private static List<LocalDate> scatteredYear() {
        List<LocalDate> months = new ArrayList<>(run("2008-05", 8));
        months.addAll(run("2010-01", 4));
        return months;
    }

    @Test
    void p0_엔진이_자바와_같은_달을_말하면_자바_경로와_같은_번호를_낸다() {
        for (long seed : new long[] {1L, 14L, -77L}) {
            PathRequest request = new PathRequest(BASE, 24, seed);
            answer(P0, SimpleBootstrap.generateMonths(SyntheticMonths.sample(), request), rightDigest(P0));

            GeneratedPath viaEngine = source(P0).generate(request);

            assertThat(viaEngine.monthBlockIds()).isEqualTo(bootstrap.generate(request).monthBlockIds());
            assertThat(viaEngine.engineVersion()).isEqualTo(P0);
        }
    }

    @Test
    void 엔진에_요청은_토큰_헤더와_시드_길이_기준일_버전을_싣는다() {
        PathRequest request = new PathRequest(BASE, 12, Long.MIN_VALUE);
        answer(M2, run("2007-01", 12), rightDigest(M2));

        source(M2).generate(request);

        assertThat(ENGINE.requests()).hasSize(1);
        FakeEngineServer.Recorded sent = ENGINE.requests().get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.header("X-Antlab-Engine-Token")).containsExactly(TOKEN);
        assertThat(sent.body()).contains("\"engineVersion\":\"m2-sb6-v1\"", "\"seed\":\"-9223372036854775808\"",
                "\"length\":12", "\"baseDate\":\"2026-01-02\"", "\"conditional\":false");
    }

    @Test
    void 허용된_길이_24와_60은_엔진에_그대로_나가고_경로가_돌아온다() {
        for (int length : new int[] {24, 60}) {
            ENGINE.reset();
            answer(M2, run("2007-01", length), rightDigest(M2));

            GeneratedPath path = source(M2).generate(new PathRequest(BASE, length, 7L));

            assertThat(path.monthBlockIds()).hasSize(length);
            assertThat(ENGINE.requests()).hasSize(1);
            assertThat(ENGINE.requests().get(0).body()).contains("\"length\":" + length);
        }
    }

    @Test
    void m2_달은_턴_순서대로_번호가_되고_달력_규칙은_보지_않는다() {
        List<LocalDate> months = scatteredYear();
        answer(M2, months, rightDigest(M2));

        GeneratedPath path = source(M2).generate(new PathRequest(BASE, 12, 5L));

        assertThat(path.engineVersion()).isEqualTo(M2);
        assertThat(path.monthBlockIds()).isEqualTo(months.stream().map(idByMonth::get).toList());
        assertThat(path.monthBlockIds()).doesNotHaveDuplicates();
    }

    @Test
    void p0는_블록_안의_끊긴_달과_이어_붙은_블록을_거절하고_m2는_받는다() {
        List<LocalDate> gaps = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            gaps.add(LocalDate.of(2007 + (i % 5), i + 1, 1));
        }
        List<LocalDate> joined = run("2008-01", 12);

        answer(P0, gaps, rightDigest(P0));
        assertThatThrownBy(() -> source(P0).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BLOCK_GAP");
        answer(P0, joined, rightDigest(P0));
        assertThatThrownBy(() -> source(P0).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BLOCK_JOIN");

        answer(M2, gaps, rightDigest(M2));
        assertThat(source(M2).generate(new PathRequest(BASE, 12, 5L)).monthBlockIds()).hasSize(12);
        answer(M2, joined, rightDigest(M2));
        assertThat(source(M2).generate(new PathRequest(BASE, 12, 5L)).monthBlockIds()).hasSize(12);
    }

    @Test
    void p0는_달력에_맞지_않는_경로를_거절한다() {
        answer(P0, scatteredYear(), rightDigest(P0));

        assertThatThrownBy(() -> source(P0).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("S4");
    }

    @Test
    void 엔진의_달이_자바_월_블록에_없으면_500이고_달은_메시지와_로그에_없다(CapturedOutput output) {
        List<LocalDate> months = new ArrayList<>(run("2007-01", 12));
        months.set(5, LocalDate.of(2015, 6, 1));
        answer(M2, months, rightDigest(M2));

        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("2015");
        assertThat(output.getAll()).contains("engine.month_missing").doesNotContain("2015-06");
    }

    static Stream<Arguments> brokenPaths() {
        List<LocalDate> eleven = run("2007-01", 11);
        List<LocalDate> thirteen = run("2007-01", 13);
        List<LocalDate> baseYear = new ArrayList<>(run("2007-01", 11));
        baseYear.add(LocalDate.of(2026, 1, 1)); // in the database, but the base year's January is the exclusive upper bound
        List<LocalDate> beforeStart = new ArrayList<>(run("2007-01", 11));
        beforeStart.add(LocalDate.of(2006, 12, 1)); // in the database, before the replay start
        List<LocalDate> reused = new ArrayList<>(run("2007-01", 11));
        reused.add(LocalDate.of(2007, 1, 1));
        return Stream.of(
                Arguments.of("너무 짧다", eleven, "S1_COUNT"),
                Arguments.of("너무 길다", thirteen, "S1_COUNT"),
                Arguments.of("기준일 해의 달", baseYear, "S1_RANGE"),
                Arguments.of("재생 시작 전의 달", beforeStart, "S1_RANGE"),
                Arguments.of("같은 달 두 번", reused, "REUSE"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("brokenPaths")
    void m2도_개수_범위_재사용은_거절한다(String name, List<LocalDate> months, String rule) {
        answer(M2, months, rightDigest(M2));

        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(rule);
    }

    @Test
    void 이력_해시가_한_달만_달라도_500이다(CapturedOutput output) {
        String oneMonthShort = digestOf(M2, replayable.subList(1, replayable.size()), BASE);
        answer(M2, run("2007-01", 12), oneMonthShort);

        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output.getAll()).contains("engine.view_mismatch");
    }

    @Test
    void 이력_해시가_맞아도_버전이_요청과_다르면_500이다() {
        answer(P0, run("2007-01", 12), rightDigest(M2));

        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
    }

    @Test
    void 엔진의_422는_PATH_TOO_LONG_그대로_낸다() {
        ENGINE.respondFixture(PATHS, 422, "problem-path-too-long.json");

        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transientProblems")
    void 엔진이_바쁘거나_이력이_없으면_ENGINE_UNAVAILABLE이다(String fixture, int status) {
        ENGINE.respondFixture(PATHS, status, fixture);

        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOf(EngineUnavailableException.class)
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ENGINE_UNAVAILABLE));
    }

    static Stream<Arguments> transientProblems() {
        return Stream.of(Arguments.of("problem-engine-busy.json", 503), Arguments.of("problem-history-unavailable.json", 503));
    }

    @Test
    void 엔진_내부_오류는_500이다() {
        ENGINE.respondFixture(PATHS, 500, "problem-internal-error.json");
        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 12, 5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 길이_정책은_엔진을_부르기_전에_자바가_정한다() {
        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 61, 5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG));
        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 120, 5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG));
        assertThatThrownBy(() -> source(M2).generate(new PathRequest(BASE, 10, 5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        assertThat(ENGINE.requests()).isEmpty();
    }
}

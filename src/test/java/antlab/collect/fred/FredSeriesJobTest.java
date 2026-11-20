package antlab.collect.fred;

import static antlab.collect.fred.FakeFredClient.obs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.CollectTestData;
import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobAlreadyRunningException;
import antlab.collect.job.JobRunLog;
import antlab.collect.job.JobRunSummary;
import antlab.collect.series.MacroRules;
import antlab.collect.series.SeriesCatalog;
import antlab.collect.series.SeriesDef;
import antlab.collect.series.SeriesFrequency;
import antlab.collect.series.SeriesObservationWriter.Incoming;
import antlab.collect.series.SeriesSource;
import antlab.observability.CollectMetrics;
import antlab.support.JpaMappingTest;
import antlab.support.MetricCounts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The job with a scripted client: run outcomes, window choice, series rows and the pre-vintage rule. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FredSeriesJobTest {

    private static final String CATALOG = """
            code,source,source_series_id,unit,frequency,release_lag_days,revisable
            US_DGS2,FRED,DGS2,PERCENT,DAILY,1,false
            US_VIX,FRED,VIXCLS,INDEX,DAILY,1,false
            US_CPILFESL,FRED,CPILFESL,INDEX,MONTHLY,10,true
            """;
    private static final String FAR = "9999-12-31";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CollectTestData data;
    private CollectTestData.TestClock clock;
    private FakeFredClient client;

    @BeforeEach
    void seed() {
        data = new CollectTestData(jdbc);
        data.cleanUp();
        clock = new CollectTestData.TestClock(Instant.parse("2026-11-18T10:00:00Z"));
        client = new FakeFredClient();
    }

    @AfterEach
    void clean() {
        data.cleanUp();
    }

    private FredSeriesJob job(String catalog, FredProperties properties) {
        return FredSeriesJob.create(() -> client, SeriesCatalog.fromCsv(catalog), jdbc, transactions, clock, properties);
    }

    private JobRunSummary run(String... codes) {
        return job(CATALOG, FredHarness.properties(1, 30, 100000)).run(RunTrigger.MANUAL, Set.of(codes));
    }

    private String errorDetail(JobRunSummary summary) {
        return jdbc.queryForObject("SELECT coalesce(error_detail, '') FROM job_run WHERE id = ?", String.class, summary.jobRunId());
    }

    @Test
    void 아무것도_고르지_않으면_카탈로그의_모든_FRED_계열을_받고_계열_행을_만든다() {
        client.answer("DGS2", obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"))
                .answer("VIXCLS", obs("2025-11-17", "17.2", "2026-11-18", "2026-11-18"))
                .answer("CPILFESL", obs("2025-10-01", "330.1", "2025-11-13", FAR));

        JobRunSummary summary = run();

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.requestCount()).isEqualTo(3);
        assertThat(summary.insertedCount()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT code FROM series ORDER BY code", String.class)).containsExactly("US_CPILFESL", "US_DGS2", "US_VIX");
        assertThat(jdbc.queryForObject("SELECT job_name || '/' || status FROM job_run WHERE id = ?", String.class, summary.jobRunId()))
                .isEqualTo("FRED_SERIES/SUCCEEDED");
        for (String rule : MacroRules.ALL) {
            assertThat(jdbc.queryForList(rule)).as(rule).isEmpty();
        }
    }

    @Test
    void 코드로_좁히면_그_계열만_받는다() {
        client.answer("VIXCLS", obs("2025-11-17", "17.2", "2026-11-18", "2026-11-18"));

        JobRunSummary summary = run("US_VIX");

        assertThat(summary.insertedCount()).isEqualTo(1);
        assertThat(client.calls).extracting(FakeFredClient.Call::sourceSeriesId).containsExactly("VIXCLS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series", Integer.class)).isEqualTo(1);
    }

    @Test
    void 수정되는_계열은_빈티지_전체를_기본_시작일부터_읽고_그_밖의_계열은_창을_둔다() {
        client.answer("DGS2", obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"))
                .answer("CPILFESL", obs("2025-10-01", "330.1", "2025-11-13", FAR));

        run("US_DGS2", "US_CPILFESL");
        clock.advance(Duration.ofDays(1));
        run("US_DGS2", "US_CPILFESL");

        assertThat(client.calls).containsExactlyInAnyOrder(
                new FakeFredClient.Call("DGS2", LocalDate.of(1990, 1, 1), false),
                new FakeFredClient.Call("CPILFESL", LocalDate.of(1990, 1, 1), true),
                new FakeFredClient.Call("DGS2", LocalDate.of(2025, 9, 18), false),
                new FakeFredClient.Call("CPILFESL", LocalDate.of(1990, 1, 1), true));
    }

    @Test
    void 조회_창은_기본_시작일_앞으로_내려가지_않는다() {
        client.answer("DGS2", obs("1990-01-10", "8.0", "2026-11-18", "2026-11-18"));

        run("US_DGS2");
        clock.advance(Duration.ofDays(1));
        run("US_DGS2");

        assertThat(client.calls.getLast().start()).isEqualTo(LocalDate.of(1990, 1, 1));
    }

    @Test
    void 알_수_없는_코드는_요청_전에_대상_오류로_끝난다() {
        JobRunSummary summary = run("US_NOPE");

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("TARGETS_INVALID");
        assertThat(summary.requestCount()).isZero();
        assertThat(client.calls).isEmpty();
        assertThat(errorDetail(summary)).contains("US_NOPE");
    }

    @Test
    void 저장된_계열_행의_식별_열이_카탈로그와_다르면_요청_전에_대상_오류로_끝나고_저장_행은_그대로다() {
        jdbc.update("INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable) "
                + "VALUES ('US_DGS2', 'FRED', 'DGS2', 'PERCENT', 'WEEKLY', 1, false)");

        JobRunSummary summary = run("US_DGS2");

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("TARGETS_INVALID");
        assertThat(client.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT frequency FROM series WHERE code = 'US_DGS2'", String.class)).isEqualTo("WEEKLY");
        assertThat(errorDetail(summary)).contains("US_DGS2").contains("differs from the catalog");
    }

    @Test
    void 단위와_발표_시차만_다르면_정보용이라_멈추지_않는다() {
        jdbc.update("INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable) "
                + "VALUES ('US_DGS2', 'FRED', 'DGS2', 'PCT', 'DAILY', 3, false)");
        client.answer("DGS2", obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"));

        assertThat(run("US_DGS2").status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT release_lag_days FROM series WHERE code = 'US_DGS2'", Integer.class)).isEqualTo(3);
    }

    @Test
    void 한_계열이_실패해도_나머지는_받고_부분_성공이다() {
        client.fail("DGS2", "FRED_HTTP", "GET observations HTTP 500")
                .answer("VIXCLS", obs("2025-11-17", "17.2", "2026-11-18", "2026-11-18"));

        JobRunSummary summary = run("US_DGS2", "US_VIX");

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("FRED_HTTP");
        assertThat(summary.insertedCount()).isEqualTo(1);
        assertThat(errorDetail(summary)).isEqualTo("US_DGS2: GET observations HTTP 500");
    }

    @Test
    void 모든_계열이_실패하면_실패다() {
        client.fail("DGS2", "FRED_HTTP", "x").fail("VIXCLS", "FRED_PARSE", "y");

        JobRunSummary summary = run("US_DGS2", "US_VIX");

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).as("the first failure's code").isIn("FRED_HTTP", "FRED_PARSE");
    }

    @Test
    void 요청_상한은_나머지_계열을_시도하지_않고_멈춘다() {
        client.fail("DGS2", "REQUEST_CAP", "cap").answer("VIXCLS", obs("2025-11-17", "17.2", "2026-11-18", "2026-11-18"));

        JobRunSummary summary = run("US_DGS2", "US_VIX");

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("REQUEST_CAP");
        assertThat(summary.failedTargetCount()).isEqualTo(2);
        assertThat(client.calls).extracting(FakeFredClient.Call::sourceSeriesId).containsExactly("DGS2");
    }

    @Test
    void 예상하지_못한_예외는_내부_오류로_기록하고_클래스_이름만_남긴다() {
        client.crash("DGS2", new IllegalStateException("secret-looking detail"));

        JobRunSummary summary = run("US_DGS2");

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(errorDetail(summary)).isEqualTo("IllegalStateException");
    }

    @Test
    void 같은_시각에_다시_돌려_값이_바뀌었으면_시계_오류로_그_계열만_실패한다() {
        client.answer("DGS2", obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"));
        run("US_DGS2");
        client.answer("DGS2", obs("2025-11-17", "3.7", "2026-11-18", "2026-11-18"));

        JobRunSummary again = run("US_DGS2");

        assertThat(again.status()).isEqualTo(JobStatus.FAILED);
        assertThat(again.errorCode()).isEqualTo("CLOCK_SKEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation", Integer.class)).isEqualTo(1);
    }

    @Test
    void 열린_실행이_있으면_요청_없이_거절한다() {
        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.FRED_SERIES, RunTrigger.SCHEDULED, clock.instant());

        assertThatThrownBy(() -> run("US_DGS2")).isInstanceOf(JobAlreadyRunningException.class);
        assertThat(client.calls).isEmpty();
    }

    @Test
    void 끝나지_않은_옛_실행은_닫을_때_FRED_행도_센다() {
        client.answer("CPILFESL", obs("2025-10-01", "330.1", "2025-11-13", FAR), obs("2025-10-01", "330.2", "2026-02-12", FAR));
        JobRunSummary first = run("US_CPILFESL");
        jdbc.update("UPDATE job_run SET status = 'RUNNING', finished_at = NULL, request_count = NULL, inserted_count = NULL, "
                + "revised_count = NULL, unchanged_count = NULL, failed_target_count = NULL, error_code = NULL WHERE id = ?", first.jobRunId());

        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.FRED_SERIES, RunTrigger.MANUAL, clock.instant().plus(Duration.ofHours(3)));

        assertThat(jdbc.queryForMap("SELECT status, error_code, inserted_count, revised_count FROM job_run WHERE id = ?", first.jobRunId()))
                .containsEntry("status", "FAILED").containsEntry("error_code", "ABANDONED")
                .containsEntry("inserted_count", 2).containsEntry("revised_count", 1);
    }

    @Test
    void 빈티지_변환_첫_빈티지만_날짜를_잃고_점은_버린다() {
        SeriesDef cpi = new SeriesDef("US_CPILFESL", SeriesSource.FRED, "CPILFESL", "INDEX", SeriesFrequency.MONTHLY, 10, true);
        List<Incoming> rows = FredSeriesJob.historyRows(cpi, List.of(
                obs("1990-01-01", "132.3", "1996-12-12", "2002-02-19"),
                obs("1990-01-01", "132.1", "2002-02-20", FAR),
                obs("1996-10-01", "158.0", "1996-12-12", FAR),
                obs("1996-11-01", "158.4", "1996-12-12", FAR),
                obs("1996-12-01", "158.9", "1996-12-12", FAR),
                obs("1997-01-01", "159.1", "1997-02-19", FAR)));

        assertThat(rows).extracting(r -> r.refDate() + "/" + r.realtimeStart()).containsExactly(
                "1990-01-01/null", "1990-01-01/2002-02-20", "1996-10-01/null", "1996-11-01/1996-12-12", "1996-12-01/1996-12-12",
                "1997-01-01/1997-02-19");
        assertThat(rows).hasSize(6);
        assertThat(rows.getFirst().value()).isEqualByComparingTo(new BigDecimal("132.3"));
    }

    @Test
    void 빈티지_변환_행이_없으면_빈_목록이고_일간_변환은_빈티지_열을_쓰지_않는다() {
        SeriesDef cpi = new SeriesDef("US_CPILFESL", SeriesSource.FRED, "CPILFESL", "INDEX", SeriesFrequency.MONTHLY, 10, true);

        assertThat(FredSeriesJob.historyRows(cpi, List.of())).isEmpty();
        assertThat(FredSeriesJob.latestRows(List.of(obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"), obs("2025-11-18", ".", "2026-11-18", "2026-11-18"))))
                .containsExactly(new Incoming(LocalDate.of(2025, 11, 17), new BigDecimal("3.6"), null, null));
    }

    @Test
    void 가장_새_빈티지가_값이_없거나_끝났으면_계열을_실패시킨다() {
        SeriesDef icsa = new SeriesDef("US_ICSA", SeriesSource.FRED, "ICSA", "COUNT", SeriesFrequency.WEEKLY, 5, true);

        for (List<FredObservation> bad : List.of(
                List.of(obs("2025-10-04", "200000", "2025-10-09", FAR), obs("2025-10-04", ".", "2025-11-01", FAR)),
                List.of(obs("2025-10-04", "200000", "2025-10-09", "2025-10-31")))) {
            assertThatThrownBy(() -> FredSeriesJob.historyRows(icsa, bad)).isInstanceOfSatisfying(FredException.class,
                    e -> assertThat(e.code()).isEqualTo("FRED_PARSE"));
        }
    }

    @Test
    void 빈티지_변환_한_번도_발표되지_않은_날짜의_점은_실패가_아니라_건너뛴다() {
        SeriesDef cpi = new SeriesDef("US_CPILFESL", SeriesSource.FRED, "CPILFESL", "INDEX", SeriesFrequency.MONTHLY, 10, true);
        // observed live: the 2025-10 value was never published, FRED answers one row "." open to 9999-12-31
        List<Incoming> rows = FredSeriesJob.historyRows(cpi, List.of(
                obs("2025-09-01", "338.896", "2026-10-14", FAR),
                obs("2025-10-01", ".", "2025-12-18", FAR),
                obs("2025-11-01", "339.9", "2026-11-10", FAR)));

        assertThat(rows).extracting(r -> r.refDate().toString()).containsExactly("2025-09-01", "2025-11-01");
    }

    private FredSeriesJob metricJob(MeterRegistry registry) {
        return FredSeriesJob.create(() -> client, SeriesCatalog.fromCsv(CATALOG), jdbc, transactions, clock,
                FredHarness.properties(1, 30, 100000), new CollectMetrics(registry));
    }

    @Test
    void 실행_하나는_끝난_상태_한_계열만_한_번_센다() {
        var registry = new SimpleMeterRegistry();
        client.answer("DGS2", obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"));

        metricJob(registry).run(RunTrigger.MANUAL, Set.of("US_DGS2"));

        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "FRED_SERIES", "status", "SUCCEEDED")).isEqualTo(1);
        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isEqualTo(1);
    }

    @Test
    void 일부_계열이_실패하면_PARTIAL_모두_실패하면_FAILED로_각각_한_번_센다() {
        var registry = new SimpleMeterRegistry();
        client.answer("DGS2", obs("2025-11-17", "3.6", "2026-11-18", "2026-11-18"))
                .fail("VIXCLS", "FRED_HTTP", "HTTP 500");

        metricJob(registry).run(RunTrigger.MANUAL, Set.of("US_DGS2", "US_VIX"));
        metricJob(registry).run(RunTrigger.MANUAL, Set.of("US_VIX"));

        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "FRED_SERIES", "status", "PARTIAL")).isEqualTo(1);
        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "FRED_SERIES", "status", "FAILED")).isEqualTo(1);
        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "FRED_SERIES", "status", "SUCCEEDED")).isZero();
        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isEqualTo(2);
    }

    @Test
    void 거절된_실행과_다른_수집기_이름은_세지_않는다() {
        var registry = new SimpleMeterRegistry();
        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.FRED_SERIES, RunTrigger.SCHEDULED, clock.instant());

        assertThatThrownBy(() -> metricJob(registry).run(RunTrigger.MANUAL, Set.of("US_DGS2"))).isInstanceOf(JobAlreadyRunningException.class);

        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isZero();
    }
}

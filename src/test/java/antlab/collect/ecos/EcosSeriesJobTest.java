package antlab.collect.ecos;

import static antlab.collect.ecos.FakeEcosClient.obs;
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
import antlab.observability.CollectMetrics;
import antlab.support.JpaMappingTest;
import antlab.support.MetricCounts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/** The job with a scripted client: run outcomes, windows, lag dates, revisions and the failure rules. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EcosSeriesJobTest {

    private static final String CATALOG = """
            code,source,source_series_id,unit,frequency,release_lag_days,revisable
            US_DGS2,FRED,DGS2,PERCENT,DAILY,1,false
            KR_KTB3Y,ECOS,817Y002/010200000,PERCENT,DAILY,0,false
            KR_USDKRW,ECOS,731Y001/0000001,KRW,DAILY,2,false
            KR_CPI,ECOS,901Y009/0,INDEX,MONTHLY,7,true
            KR_CLI,ECOS,901Y067/I16E,INDEX,MONTHLY,35,true
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CollectTestData data;
    private CollectTestData.TestClock clock;
    private FakeEcosClient client;

    @BeforeEach
    void seed() {
        data = new CollectTestData(jdbc);
        data.cleanUp();
        clock = new CollectTestData.TestClock(Instant.parse("2026-11-18T10:00:00Z"));
        client = new FakeEcosClient();
    }

    @AfterEach
    void clean() {
        data.cleanUp();
    }

    private EcosSeriesJob job(String catalog, Clock useClock) {
        return EcosSeriesJob.create(() -> client, SeriesCatalog.fromCsv(catalog), jdbc, transactions, useClock,
                EcosHarness.properties(1, 20, 100000));
    }

    private JobRunSummary run(String... codes) {
        return job(CATALOG, clock).run(RunTrigger.MANUAL, Set.of(codes));
    }

    private String errorDetail(JobRunSummary summary) {
        return jdbc.queryForObject("SELECT coalesce(error_detail, '') FROM job_run WHERE id = ?", String.class, summary.jobRunId());
    }

    private List<String> stored(String code) {
        return jdbc.queryForList("""
                SELECT o.ref_date || '/' || o.vintage_no || '/' || o.value::float8 || '/' || coalesce(o.available_on::text, '-')
                FROM series_observation o JOIN series s ON s.id = o.series_id WHERE s.code = ? ORDER BY o.ref_date, o.vintage_no
                """, String.class, code);
    }

    private void assertRulesHold() {
        for (String rule : MacroRules.ALL) {
            assertThat(jdbc.queryForList(rule)).as(rule).isEmpty();
        }
    }

    @Test
    void 아무것도_고르지_않으면_카탈로그의_모든_ECOS_계열만_받고_FRED_행은_건드리지_않는다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"))
                .answer("731Y001/0000001", obs("2025-11-17", "1458.5"))
                .answer("901Y009/0", obs("2025-10-01", "117.42"))
                .answer("901Y067/I16E", obs("2025-09-01", "99.7"));

        JobRunSummary summary = run();

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.requestCount()).isEqualTo(4);
        assertThat(summary.insertedCount()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT code FROM series ORDER BY code", String.class))
                .containsExactly("KR_CLI", "KR_CPI", "KR_KTB3Y", "KR_USDKRW");
        assertThat(jdbc.queryForObject("SELECT job_name || '/' || status FROM job_run WHERE id = ?", String.class, summary.jobRunId()))
                .isEqualTo("ECOS_SERIES/SUCCEEDED");
        assertRulesHold();
    }

    @Test
    void 코드로_좁히면_그_계열만_받고_통계와_항목을_나눠_주기와_함께_묻는다() {
        client.answer("901Y067/I16E", obs("2025-09-01", "99.7"));

        JobRunSummary summary = run("KR_CLI");

        assertThat(summary.insertedCount()).isEqualTo(1);
        assertThat(client.calls).singleElement().satisfies(call -> {
            assertThat(call.statCode()).isEqualTo("901Y067");
            assertThat(call.itemCode()).isEqualTo("I16E");
            assertThat(call.cycle()).isEqualTo(EcosCycle.M);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series", Integer.class)).isEqualTo(1);
    }

    @Test
    void 사용_가능일은_월별은_월말에_일별은_그날에_발표_시차를_더한_날이다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"))
                .answer("731Y001/0000001", obs("2025-11-17", "1458.5"))
                .answer("901Y009/0", obs("2025-10-01", "117.42"), obs("2025-02-01", "110.0"))
                .answer("901Y067/I16E", obs("2025-09-01", "99.7"));

        run();

        assertThat(stored("KR_KTB3Y")).containsExactly("2025-11-17/1/2.7/2025-11-17");
        assertThat(stored("KR_USDKRW")).containsExactly("2025-11-17/1/1458.5/2025-11-19");
        assertThat(stored("KR_CPI")).containsExactly("2025-02-01/1/110/2025-03-07", "2025-10-01/1/117.42/2025-11-07");
        assertThat(stored("KR_CLI")).containsExactly("2025-09-01/1/99.7/2025-11-04");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation WHERE realtime_start IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void 수정되는_월별_계열은_매번_기본_시작일부터_읽고_일별은_마지막_저장일_앞_창부터_읽는다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7")).answer("901Y009/0", obs("2025-10-01", "117.42"));

        run("KR_KTB3Y", "KR_CPI");
        clock.advance(Duration.ofDays(1));
        run("KR_KTB3Y", "KR_CPI");

        assertThat(client.calls).extracting(c -> c.statCode() + "@" + c.from() + ".." + c.to()).containsExactlyInAnyOrder(
                "817Y002@1990-01-01..2026-11-18", "901Y009@1990-01-01..2026-11-18",
                "817Y002@2025-09-18..2026-11-19", "901Y009@1990-01-01..2026-11-19");
    }

    @Test
    void 조회_창은_기본_시작일_앞으로_내려가지_않는다() {
        client.answer("817Y002/010200000", obs("1990-01-10", "8.0"));

        run("KR_KTB3Y");
        clock.advance(Duration.ofDays(1));
        run("KR_KTB3Y");

        assertThat(client.calls.getLast().from()).isEqualTo(LocalDate.of(1990, 1, 1));
    }

    /** A clock that starts at the given instant and then flows with real time; unlike the test clock it honours the zone. */
    private static Clock flowingFrom(Instant start, java.time.ZoneId zone) {
        long begin = System.nanoTime();
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(java.time.ZoneId other) {
                return flowingFrom(instant(), other);
            }

            @Override
            public Instant instant() {
                return start.plusNanos(System.nanoTime() - begin);
            }
        };
    }

    @Test
    void 범위의_끝은_한국_날짜_오늘이다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"));
        Clock lateUtc = flowingFrom(Instant.parse("2026-11-18T16:00:00Z"), ZoneOffset.UTC);

        job(CATALOG, lateUtc).run(RunTrigger.MANUAL, Set.of("KR_KTB3Y"));

        assertThat(client.calls.getFirst().to()).as("16:00 UTC is already the next day in Seoul").isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void 같은_값을_다시_받으면_아무것도_더하지_않는다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"), obs("2025-11-18", "2.71"));
        run("KR_KTB3Y");
        clock.advance(Duration.ofHours(1));

        JobRunSummary again = run("KR_KTB3Y");

        assertThat(again.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation", Integer.class)).isEqualTo(2);
    }

    @Test
    void 개정된_값은_다음_빈티지로_쌓이고_사용_가능일은_저장된_날_앞으로_가지_않는다() {
        clock.set(Instant.parse("2025-11-10T01:00:00Z"));
        client.answer("901Y009/0", obs("2025-10-01", "117.42"));
        run("KR_CPI");
        clock.set(Instant.parse("2026-02-10T01:00:00Z"));
        client.answer("901Y009/0", obs("2025-10-01", "117.50"));

        JobRunSummary again = run("KR_CPI");

        assertThat(again.revisedCount()).isEqualTo(1);
        assertThat(stored("KR_CPI")).containsExactly("2025-10-01/1/117.42/2025-11-07", "2025-10-01/2/117.5/2026-02-10");
        assertRulesHold();
    }

    @Test
    void 발표_시차_날짜_전에_저장된_개정은_사용_가능일을_그대로_둔다() {
        clock.set(Instant.parse("2025-11-01T01:00:00Z"));
        client.answer("901Y009/0", obs("2025-10-01", "117.42"));
        run("KR_CPI");
        clock.set(Instant.parse("2025-11-02T01:00:00Z"));
        client.answer("901Y009/0", obs("2025-10-01", "117.50"));

        run("KR_CPI");

        assertThat(stored("KR_CPI")).as("stored before the lag date: the lag date stays").containsExactly(
                "2025-10-01/1/117.42/2025-11-07", "2025-10-01/2/117.5/2025-11-07");
    }

    @Test
    void 답에_값이_하나도_없으면_계열을_실패시키고_나머지는_받는다() {
        client.answer("817Y002/010200000").answer("731Y001/0000001", obs("2025-11-17", "1458.5"));

        JobRunSummary summary = run("KR_KTB3Y", "KR_USDKRW");

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("ECOS_EMPTY");
        assertThat(summary.insertedCount()).isEqualTo(1);
        assertThat(errorDetail(summary)).startsWith("KR_KTB3Y: ");
    }

    @Test
    void 한_계열이_실패해도_나머지는_받고_모두_실패하면_실패다() {
        client.fail("817Y002/010200000", "ECOS_HTTP", "GET StatisticSearch HTTP 500")
                .answer("731Y001/0000001", obs("2025-11-17", "1458.5"));

        JobRunSummary partial = run("KR_KTB3Y", "KR_USDKRW");

        assertThat(partial.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(partial.errorCode()).isEqualTo("ECOS_HTTP");
        assertThat(errorDetail(partial)).isEqualTo("KR_KTB3Y: GET StatisticSearch HTTP 500");

        client.fail("731Y001/0000001", "ECOS_PARSE", "y");
        JobRunSummary all = run("KR_KTB3Y", "KR_USDKRW");

        assertThat(all.status()).isEqualTo(JobStatus.FAILED);
        assertThat(all.failedTargetCount()).isEqualTo(2);
    }

    @Test
    void 요청_상한_거절된_키_호출_한도는_나머지_계열을_시도하지_않고_멈춘다() {
        for (String code : new String[] {"REQUEST_CAP", "ECOS_AUTH", "ECOS_RATE_LIMIT"}) {
            client = new FakeEcosClient().fail("817Y002/010200000", code, "stop")
                    .answer("731Y001/0000001", obs("2025-11-17", "1458.5"));

            JobRunSummary summary = run("KR_KTB3Y", "KR_USDKRW");

            assertThat(summary.status()).as(code).isEqualTo(JobStatus.FAILED);
            assertThat(summary.errorCode()).isEqualTo(code);
            assertThat(summary.failedTargetCount()).isEqualTo(2);
            assertThat(client.calls).extracting(FakeEcosClient.Call::statCode).containsExactly("817Y002");
            clock.advance(Duration.ofHours(3));
        }
    }

    @Test
    void 일반_실패는_멈추지_않는다() {
        client.fail("817Y002/010200000", "ECOS_REJECTED", "x").answer("731Y001/0000001", obs("2025-11-17", "1458.5"));

        run("KR_KTB3Y", "KR_USDKRW");

        assertThat(client.calls).hasSize(2);
    }

    @Test
    void 예상하지_못한_예외는_내부_오류로_기록하고_클래스_이름만_남긴다() {
        client.crash("817Y002/010200000", new IllegalStateException("secret-looking detail"));

        JobRunSummary summary = run("KR_KTB3Y");

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(errorDetail(summary)).isEqualTo("IllegalStateException");
    }

    @Test
    void 알_수_없는_코드는_요청_전에_대상_오류로_끝나고_FRED_코드도_그렇다() {
        for (String code : new String[] {"KR_NOPE", "US_DGS2"}) {
            JobRunSummary summary = run(code);

            assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
            assertThat(summary.errorCode()).isEqualTo("TARGETS_INVALID");
            assertThat(summary.requestCount()).isZero();
            assertThat(errorDetail(summary)).contains(code);
            clock.advance(Duration.ofHours(3));
        }
        assertThat(client.calls).isEmpty();
    }

    @Test
    void 주간이거나_통계_항목_모양이_아닌_카탈로그_행은_요청_전에_대상_오류다() {
        for (String row : new String[] {"KR_WK,ECOS,817Y002/010200000,PERCENT,WEEKLY,0,false",
                "KR_BAD,ECOS,817Y002,PERCENT,DAILY,0,false", "KR_BAD,ECOS,817y002/010200000,PERCENT,DAILY,0,false",
                "KR_BAD,ECOS,817Y002/01/02,PERCENT,DAILY,0,false"}) {
            String catalog = "code,source,source_series_id,unit,frequency,release_lag_days,revisable\n" + row + "\n";

            JobRunSummary summary = job(catalog, clock).run(RunTrigger.MANUAL, Set.of());

            assertThat(summary.errorCode()).as(row).isEqualTo("TARGETS_INVALID");
            assertThat(client.calls).isEmpty();
            clock.advance(Duration.ofHours(3));
        }
    }

    @Test
    void 저장된_계열_행의_식별_열이_카탈로그와_다르면_요청_전에_대상_오류로_끝나고_저장_행은_그대로다() {
        jdbc.update("INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable) "
                + "VALUES ('KR_KTB3Y', 'ECOS', '817Y002/010200000', 'PERCENT', 'MONTHLY', 0, false)");

        JobRunSummary summary = run("KR_KTB3Y");

        assertThat(summary.errorCode()).isEqualTo("TARGETS_INVALID");
        assertThat(client.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT frequency FROM series WHERE code = 'KR_KTB3Y'", String.class)).isEqualTo("MONTHLY");
        assertThat(errorDetail(summary)).contains("KR_KTB3Y").contains("differs from the catalog");
    }

    @Test
    void 한_날짜에_값이_둘이면_그_계열만_빈티지_불일치로_실패한다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"), obs("2025-11-17", "2.8"))
                .answer("731Y001/0000001", obs("2025-11-17", "1458.5"));

        JobRunSummary summary = run("KR_KTB3Y", "KR_USDKRW");

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("VINTAGE_MISMATCH");
        assertThat(stored("KR_KTB3Y")).isEmpty();
        assertThat(stored("KR_USDKRW")).hasSize(1);
    }

    @Test
    void 같은_시각에_다시_돌려_값이_바뀌었으면_시계_오류로_그_계열만_실패한다() {
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"));
        run("KR_KTB3Y");
        client.answer("817Y002/010200000", obs("2025-11-17", "2.8"));

        JobRunSummary again = run("KR_KTB3Y");

        assertThat(again.errorCode()).isEqualTo("CLOCK_SKEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation", Integer.class)).isEqualTo(1);
    }

    @Test
    void 열린_실행이_있으면_요청_없이_거절한다() {
        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.ECOS_SERIES, RunTrigger.SCHEDULED, clock.instant());

        assertThatThrownBy(() -> run("KR_KTB3Y")).isInstanceOf(JobAlreadyRunningException.class);
        assertThat(client.calls).isEmpty();
    }

    @Test
    void FRED_실행이_열려_있어도_ECOS_실행은_돈다() {
        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.FRED_SERIES, RunTrigger.SCHEDULED, clock.instant());
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"));

        assertThat(run("KR_KTB3Y").status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void 끝나지_않은_옛_실행은_닫을_때_ECOS_행도_센다() {
        client.answer("901Y009/0", obs("2025-10-01", "117.42"));
        JobRunSummary first = run("KR_CPI");
        clock.advance(Duration.ofHours(1));
        client.answer("901Y009/0", obs("2025-10-01", "117.5"));
        jdbc.update("UPDATE job_run SET status = 'RUNNING', finished_at = NULL, request_count = NULL, inserted_count = NULL, "
                + "revised_count = NULL, unchanged_count = NULL, failed_target_count = NULL, error_code = NULL WHERE id = ?", first.jobRunId());

        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.ECOS_SERIES, RunTrigger.MANUAL, clock.instant().plus(Duration.ofHours(3)));

        assertThat(jdbc.queryForMap("SELECT status, error_code, inserted_count FROM job_run WHERE id = ?", first.jobRunId()))
                .containsEntry("status", "FAILED").containsEntry("error_code", "ABANDONED").containsEntry("inserted_count", 1);
    }

    @Test
    void 실행이_자정_전에_시작해도_자정_뒤에_받은_개정은_받은_날부터_읽힌다() {
        clock.set(Instant.parse("2026-11-10T01:00:00Z"));
        client.answer("901Y009/0", obs("2025-10-01", "117.42"));
        run("KR_CPI");
        clock.set(Instant.parse("2026-11-19T23:59:59Z"));
        client.answerAfter("901Y009/0", () -> clock.advance(Duration.ofSeconds(2)), obs("2025-10-01", "117.50"));

        run("KR_CPI");

        assertThat(jdbc.queryForObject("SELECT (observed_at AT TIME ZONE 'UTC')::date::text FROM series_observation WHERE vintage_no = 2", String.class))
                .as("observed_at stays the run start").isEqualTo("2026-11-19");
        assertThat(stored("KR_CPI")).containsExactly("2025-10-01/1/117.42/2025-11-07", "2025-10-01/2/117.5/2026-11-20");
        assertRulesHold();
    }

    @Test
    void 월별_기본_시작일이_달_중간이어도_요청과_조회가_같은_날이라_다시_돌려도_추가도_충돌도_없다() {
        var mid = new EcosProperties(true, EcosHarness.FAKE_KEY, 1, 20, 100000, LocalDate.of(2025, 9, 15), 60, Duration.ofHours(2));
        client.answer("901Y009/0", obs("2025-09-01", "117.06"), obs("2025-10-01", "117.42"));
        var job = EcosSeriesJob.create(() -> client, SeriesCatalog.fromCsv(CATALOG), jdbc, transactions, clock, mid);

        JobRunSummary first = job.run(RunTrigger.MANUAL, Set.of("KR_CPI"));
        clock.advance(Duration.ofHours(1));
        JobRunSummary again = job.run(RunTrigger.MANUAL, Set.of("KR_CPI"));

        assertThat(first.insertedCount()).isEqualTo(2);
        assertThat(again.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(2);
        assertThat(client.calls).extracting(FakeEcosClient.Call::from).containsOnly(LocalDate.of(2025, 9, 1));
    }

    @Test
    void 스물네_자리_경계_값은_정확히_저장되고_다시_받아도_그대로다() {
        java.math.BigDecimal big = new java.math.BigDecimal("9999999999999999.12345678");
        client.answer("901Y009/0", new EcosObservation(LocalDate.of(2025, 10, 1), big));
        run("KR_CPI");
        clock.advance(Duration.ofHours(1));

        JobRunSummary again = run("KR_CPI");

        assertThat(jdbc.queryForObject("SELECT value FROM series_observation", java.math.BigDecimal.class)).isEqualTo(big);
        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(1);
    }

    @Test
    void 저장된_계열_행의_수정_여부가_카탈로그와_다르면_대상_오류다() {
        jdbc.update("INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable) "
                + "VALUES ('KR_CPI', 'ECOS', '901Y009/0', 'INDEX', 'MONTHLY', 7, false)");

        JobRunSummary summary = run("KR_CPI");

        assertThat(summary.errorCode()).isEqualTo("TARGETS_INVALID");
        assertThat(client.calls).isEmpty();
    }

    private EcosSeriesJob metricJob(MeterRegistry registry) {
        return EcosSeriesJob.create(() -> client, SeriesCatalog.fromCsv(CATALOG), jdbc, transactions, clock,
                EcosHarness.properties(1, 20, 100000), new CollectMetrics(registry));
    }

    @Test
    void 실행_하나는_끝난_상태_한_계열만_한_번_센다() {
        var registry = new SimpleMeterRegistry();
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7"));

        metricJob(registry).run(RunTrigger.MANUAL, Set.of("KR_KTB3Y"));

        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "ECOS_SERIES", "status", "SUCCEEDED")).isEqualTo(1);
        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isEqualTo(1);
    }

    @Test
    void 일부_계열이_실패하면_PARTIAL_모두_실패하면_FAILED로_각각_한_번_센다() {
        var registry = new SimpleMeterRegistry();
        client.answer("817Y002/010200000", obs("2025-11-17", "2.7")).fail("731Y001/0000001", "ECOS_HTTP", "HTTP 500");

        metricJob(registry).run(RunTrigger.MANUAL, Set.of("KR_KTB3Y", "KR_USDKRW"));
        metricJob(registry).run(RunTrigger.MANUAL, Set.of("KR_USDKRW"));

        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "ECOS_SERIES", "status", "PARTIAL")).isEqualTo(1);
        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "ECOS_SERIES", "status", "FAILED")).isEqualTo(1);
        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isEqualTo(2);
    }

    @Test
    void 거절된_실행은_세지_않는다() {
        var registry = new SimpleMeterRegistry();
        new JobRunLog(jdbc, transactions, Duration.ofHours(2)).start(JobName.ECOS_SERIES, RunTrigger.SCHEDULED, clock.instant());

        assertThatThrownBy(() -> metricJob(registry).run(RunTrigger.MANUAL, Set.of("KR_KTB3Y"))).isInstanceOf(JobAlreadyRunningException.class);

        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isZero();
    }
}

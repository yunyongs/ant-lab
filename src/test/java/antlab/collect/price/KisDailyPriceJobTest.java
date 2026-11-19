package antlab.collect.price;

import static antlab.collect.price.FakeQuoteClient.close;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.CollectTestData;
import antlab.collect.CollectTestData.TestClock;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobAlreadyRunningException;
import antlab.collect.job.JobRunSummary;
import antlab.collect.kis.DailyClose;
import antlab.collect.kis.KisExchange;
import antlab.observability.CollectMetrics;
import antlab.support.JpaMappingTest;
import antlab.support.MetricCounts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily price job against a real PostgreSQL 17 and a scripted quote client. The job commits per target, so these
 * tests run outside a transaction and clean up what they wrote. Assets come from the tracked sample CSVs only.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class KisDailyPriceJobTest {

    /** KST 2026-01-13 16:30; New York is 02:30 the same day, so the last full session is 2026-01-12 in both. */
    static final Instant AFTER_CLOSE = Instant.parse("2026-01-13T07:30:00Z");
    static final String THREE = """
            market,ticker,exchange_code,price_basis
            KR,005930,,ADJUSTED
            KR,069500,,RAW
            US,SPY,AMS,ADJUSTED
            """;
    static final PriceJobProperties PROPERTIES = new PriceJobProperties(10, 30, Duration.ofHours(2));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CollectTestData data;
    private TestClock clock;
    private final AtomicInteger clientsCreated = new AtomicInteger();

    @BeforeEach
    void seed() {
        data = new CollectTestData(jdbc);
        data.cleanUp();
        data.seedAssets();
        clock = new TestClock(AFTER_CLOSE);
    }

    @AfterEach
    void clean() {
        data.cleanUp();
    }

    private KisDailyPriceJob job(String csv, FakeQuoteClient client) {
        return KisDailyPriceJob.create(() -> {
            clientsCreated.incrementAndGet();
            return client;
        }, KisTargetCatalog.fromCsv(csv), jdbc, transactions, clock, PROPERTIES);
    }

    private static FakeQuoteClient threeTargets() {
        return new FakeQuoteClient()
                .answer("KR:005930", close("2026-01-08", "70000"), close("2026-01-09", "70500"), close("2026-01-12", "71000"))
                .answer("KR:069500", close("2026-01-09", "35000.5"), close("2026-01-12", "35100"))
                .answer("US:SPY", close("2026-01-09", "500.1234"), close("2026-01-12", "501.0000"));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM asset_daily_price", Integer.class);
    }

    private void assertRulesHold() {
        for (String rule : CollectTestData.RULES) {
            assertThat(jdbc.queryForList(rule)).as(rule).isEmpty();
        }
    }

    private Map<String, Object> runRow(long id) {
        return jdbc.queryForMap("SELECT * FROM job_run WHERE id = ?", id);
    }

    private String snapshot() {
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]') FROM asset_daily_price t",
                String.class);
    }

    @Test
    void 첫_실행은_세_종목을_빈티지_1로_쓰고_실행_기록을_남긴다() {
        FakeQuoteClient client = threeTargets();

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).isEqualTo(7);
        assertThat(summary.requestCount()).isEqualTo(3);
        assertThat(rows()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price WHERE vintage_no = 1", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM asset_daily_price p JOIN job_run r ON r.id = p.job_run_id
                WHERE p.observed_at = r.started_at AND r.started_at = ?::timestamptz
                """, Integer.class, AFTER_CLOSE.toString())).isEqualTo(7);
        Map<String, Object> run = runRow(summary.jobRunId());
        assertThat(run).containsEntry("status", "SUCCEEDED").containsEntry("run_trigger", "MANUAL")
                .containsEntry("inserted_count", 7).containsEntry("failed_target_count", 0);
        assertThat(run.get("error_code")).isNull();
        assertThat(run.get("finished_at")).isNotNull();
        assertRulesHold();
    }

    @Test
    void 같은_응답으로_다시_돌리면_행을_더하지_않고_변동_없음으로_센다() {
        job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());
        String before = snapshot();
        clock.advance(Duration.ofHours(1));

        JobRunSummary again = job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(again.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(7);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 종가가_바뀌면_다음_빈티지를_더하고_앞의_행은_그대로다() {
        job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());
        String firstVintageRows = snapshot();
        clock.advance(Duration.ofHours(1));
        FakeQuoteClient revised = threeTargets().answer("KR:005930", close("2026-01-08", "70000"),
                close("2026-01-09", "70250"), close("2026-01-12", "71000"));

        JobRunSummary summary = job(THREE, revised).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.insertedCount()).isEqualTo(1);
        assertThat(summary.revisedCount()).isEqualTo(1);
        assertThat(summary.unchangedCount()).isEqualTo(6);
        assertThat(rows()).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM asset_daily_price t WHERE id <= (
                    SELECT max(id) FROM asset_daily_price WHERE vintage_no = 1)
                """, String.class)).isEqualTo(firstVintageRows);
        assertThat(jdbc.queryForObject("""
                SELECT vintage_no FROM asset_daily_price WHERE trade_date = DATE '2026-01-09' AND close_price = 70250
                """, Integer.class)).isEqualTo(2);
        assertRulesHold();
    }

    @Test
    void 시계가_뒤로_가거나_같은_시각이면_바뀐_종가를_쓰지_않고_그_종목을_실패로_센다() {
        job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());
        String before = snapshot();
        FakeQuoteClient revised = threeTargets().answer("KR:005930", close("2026-01-08", "70000"),
                close("2026-01-09", "70250"), close("2026-01-12", "71000"));

        for (Duration shift : List.of(Duration.ZERO, Duration.ofMinutes(-30))) {
            clock.set(AFTER_CLOSE.plus(shift));
            JobRunSummary summary = job(THREE, revised).run(RunTrigger.MANUAL, TargetFilter.all());

            assertThat(summary.status()).as(shift.toString()).isEqualTo(JobStatus.PARTIAL);
            assertThat(summary.errorCode()).isEqualTo("CLOCK_SKEW");
            assertThat(summary.failedTargetCount()).isEqualTo(1);
            assertThat(summary.unchangedCount()).isEqualTo(4);
            assertThat(snapshot()).isEqualTo(before);
        }
        clock.advance(Duration.ofHours(2));
        assertThat(job(THREE, revised).run(RunTrigger.MANUAL, TargetFilter.all()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertRulesHold();
    }

    @Test
    void 값이_A에서_B로_다시_A로_돌아오면_세_번째_빈티지로_남는다() {
        String one = """
                market,ticker,exchange_code,price_basis
                KR,005930,,ADJUSTED
                """;
        for (String price : List.of("70000", "69000", "70000")) {
            job(one, new FakeQuoteClient().answer("KR:005930", close("2026-01-09", price))).run(RunTrigger.MANUAL, TargetFilter.all());
            clock.advance(Duration.ofHours(1));
        }

        assertThat(jdbc.queryForList("SELECT vintage_no FROM asset_daily_price ORDER BY vintage_no", Integer.class))
                .containsExactly(1, 2, 3);
        assertRulesHold();
    }

    @Test
    void 오늘_세션은_요청하지도_쓰지도_않는다_한국_시각_오후_4시반과_아침_7시반() {
        FakeQuoteClient afternoon = threeTargets().answer("KR:005930", close("2026-01-12", "71000"), close("2026-01-13", "72000"));
        JobRunSummary summary = job(THREE, afternoon).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).as("the job drops a session past the window before the DB check has to refuse it")
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).isEqualTo(5);
        assertThat(afternoon.calls).allSatisfy(call -> assertThat(call.to()).isEqualTo(LocalDate.parse("2026-01-12")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price WHERE trade_date >= DATE '2026-01-13'",
                Integer.class)).isZero();

        data.cleanUp();
        data.seedAssets();
        clock.set(Instant.parse("2026-01-12T22:30:00Z"));
        FakeQuoteClient morning = threeTargets();
        job(THREE, morning).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(morning.calls.stream().filter(c -> c.key().startsWith("KR")).map(FakeQuoteClient.Call::to))
                .containsOnly(LocalDate.parse("2026-01-12"));
        assertThat(morning.calls.stream().filter(c -> c.key().startsWith("US")).map(FakeQuoteClient.Call::to))
                .as("New York is still on 2026-01-12 (17:30), so its window ends 2026-01-11").containsOnly(LocalDate.parse("2026-01-11"));
        assertThat(morning.calls.stream().filter(c -> c.key().startsWith("US")).findFirst().orElseThrow().exchange())
                .isEqualTo(KisExchange.AMS);
    }

    @Test
    void 해외_응답이_창을_무시하고_백_행과_오늘_행을_줘도_창_안의_행만_쓴다() {
        List<DailyClose> hundred = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-01-13");
        while (hundred.size() < 100) {
            if (day.getDayOfWeek().getValue() < 6) {
                hundred.add(close(day.toString(), "500." + (1000 + hundred.size())));
            }
            day = day.minusDays(1);
        }
        FakeQuoteClient client = threeTargets().answer("US:SPY", hundred.toArray(DailyClose[]::new));

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        long inWindow = hundred.stream().filter(c -> !c.tradeDate().isBefore(LocalDate.parse("2025-12-13"))
                && !c.tradeDate().isAfter(LocalDate.parse("2026-01-12"))).count();
        assertThat(inWindow).isBetween(10L, 99L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM asset_daily_price p JOIN asset a ON a.id = p.asset_id WHERE a.ticker = 'SPY'
                """, Integer.class)).isEqualTo((int) inWindow);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price WHERE trade_date >= DATE '2026-01-13'",
                Integer.class)).isZero();
        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void 요청_구간은_저장된_마지막_날짜에서_열흘_전부터이고_비어_있으면_서른_날_전부터다() {
        FakeQuoteClient first = threeTargets();
        job(THREE, first).run(RunTrigger.MANUAL, TargetFilter.all());
        assertThat(first.calls).allSatisfy(call -> assertThat(call.from()).isEqualTo(LocalDate.parse("2025-12-13")));

        clock.advance(Duration.ofDays(1));
        FakeQuoteClient second = threeTargets();
        job(THREE, second).run(RunTrigger.MANUAL, TargetFilter.all());
        assertThat(second.calls).allSatisfy(call -> {
            assertThat(call.from()).isEqualTo(LocalDate.parse("2026-01-02"));
            assertThat(call.to()).isEqualTo(LocalDate.parse("2026-01-13"));
        });
    }

    @Test
    void 구간이_뒤집히면_요청하지_않는다() {
        assertThat(PriceWindow.of(AFTER_CLOSE, "KR", LocalDate.parse("2026-02-01"), 10, 30)).isEmpty();
        assertThat(PriceWindow.of(AFTER_CLOSE, "KR", LocalDate.parse("2026-01-12"), 10, 30))
                .contains(new PriceWindow(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-12")));
        assertThat(PriceWindow.of(AFTER_CLOSE, "US", null, 10, 30))
                .contains(new PriceWindow(LocalDate.parse("2025-12-13"), LocalDate.parse("2026-01-12")));
    }

    @Test
    void 한_종목이_거절되면_부분_성공이고_나머지는_쓴다() {
        FakeQuoteClient client = threeTargets().fail("KR:069500", "KIS_REJECTED", "POST nothing HTTP 200 msg_cd=OPSQ0002: request rejected");

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("KIS_REJECTED");
        assertThat(summary.failedTargetCount()).isEqualTo(1);
        assertThat(summary.insertedCount()).isEqualTo(5);
        Map<String, Object> run = runRow(summary.jobRunId());
        assertThat(run).containsEntry("status", "PARTIAL").containsEntry("error_code", "KIS_REJECTED");
        assertThat((String) run.get("error_detail")).startsWith("KR:069500:");
        assertRulesHold();
    }

    @Test
    void 모두_실패하면_실패이고_실패_대상_수가_세_개다() {
        FakeQuoteClient client = new FakeQuoteClient().fail("KR:005930", "KIS_HTTP", "x").fail("KR:069500", "KIS_HTTP", "x")
                .fail("US:SPY", "KIS_RATE_LIMIT", "x");

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.failedTargetCount()).isEqualTo(3);
        assertThat(summary.errorCode()).isEqualTo("KIS_HTTP");
        assertThat(rows()).isZero();
    }

    @Test
    void 요청_상한에_닿으면_남은_종목은_시도하지_않고_부분_성공으로_끝난다() {
        FakeQuoteClient client = threeTargets().fail("KR:069500", "REQUEST_CAP", "cap");

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("REQUEST_CAP");
        assertThat(summary.failedTargetCount()).isEqualTo(2);
        assertThat(client.calls).extracting(FakeQuoteClient.Call::key).containsExactly("KR:005930", "KR:069500");
    }

    @Test
    void 새_행이_같은_빈티지와_부딪히면_그_종목만_실패한다() {
        jdbc.execute("""
                CREATE FUNCTION fake_vintage_race() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.asset_id = (SELECT id FROM asset WHERE market = 'KR' AND ticker = '005930') THEN
                        RAISE unique_violation USING CONSTRAINT = 'uq_asset_daily_price_vintage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER fake_race BEFORE INSERT ON asset_daily_price FOR EACH ROW EXECUTE FUNCTION fake_vintage_race()");
        try {
            JobRunSummary summary = job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());

            assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
            assertThat(summary.errorCode()).isEqualTo("DB_CONFLICT");
            assertThat(summary.insertedCount()).isEqualTo(4);
        } finally {
            jdbc.execute("DROP TRIGGER fake_race ON asset_daily_price");
            jdbc.execute("DROP FUNCTION fake_vintage_race()");
        }
    }

    @Test
    void 복구된_오래된_실행이_깨어나도_더_쓰지_못하고_성공으로_끝나지_않는다() {
        FakeQuoteClient later = threeTargets();
        FakeQuoteClient resumed = new FakeQuoteClient()
                .answerAfter("KR:005930", () -> {
                    clock.advance(Duration.ofHours(3));
                    job(THREE, later).run(RunTrigger.MANUAL, TargetFilter.all());
                }, close("2026-01-08", "70000"), close("2026-01-09", "70500"), close("2026-01-12", "71000"), close("2026-01-07", "69000"))
                .answer("KR:069500", close("2026-01-12", "35100"));

        JobRunSummary old = job(THREE, resumed).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(old.status()).isEqualTo(JobStatus.FAILED);
        assertThat(old.errorCode()).isEqualTo("RUN_ABANDONED");
        assertThat(resumed.calls).as("the stopped run asks for nothing more").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price WHERE job_run_id = ?", Integer.class,
                old.jobRunId())).isZero();
        assertThat(runRow(old.jobRunId())).containsEntry("status", "FAILED").containsEntry("error_code", "ABANDONED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price WHERE trade_date = DATE '2026-01-07'",
                Integer.class)).isZero();
        assertRulesHold();
    }

    @Test
    void 쓸_것이_없어도_이미_닫힌_실행은_성공으로_보고하지_않는다() {
        String none = "market,ticker,exchange_code,price_basis\n";
        KisDailyPriceJob old = KisDailyPriceJob.create(() -> {
            clock.advance(Duration.ofHours(3));
            job(none, new FakeQuoteClient()).run(RunTrigger.MANUAL, TargetFilter.all());
            return new FakeQuoteClient();
        }, KisTargetCatalog.fromCsv(none), jdbc, transactions, clock, PROPERTIES);

        JobRunSummary summary = old.run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("RUN_ABANDONED");
        assertThat(runRow(summary.jobRunId())).containsEntry("error_code", "ABANDONED");
    }

    @Test
    void 복구된_실행의_추가와_정정_건수는_남은_행에서_센다() {
        job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());
        clock.advance(Duration.ofHours(1));
        FakeQuoteClient revised = threeTargets().answer("KR:005930", close("2026-01-08", "70000"),
                close("2026-01-09", "70250"), close("2026-01-12", "71000"), close("2026-01-07", "69000"));
        JobRunSummary second = job(THREE, revised).run(RunTrigger.MANUAL, TargetFilter.all());
        assertThat(second.insertedCount()).isEqualTo(2);
        jdbc.update("""
                UPDATE job_run SET status = 'RUNNING', finished_at = NULL, request_count = NULL, inserted_count = NULL,
                    revised_count = NULL, unchanged_count = NULL, failed_target_count = NULL, error_code = NULL,
                    error_detail = NULL WHERE id = ?
                """, second.jobRunId());
        clock.advance(Duration.ofHours(3));

        job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());

        Map<String, Object> recovered = runRow(second.jobRunId());
        assertThat(recovered).containsEntry("error_code", "ABANDONED").containsEntry("inserted_count", 2)
                .containsEntry("revised_count", 1);
    }

    @Test
    void 대상_쓰기_도중_실패하면_그_대상의_앞선_행도_남지_않고_다른_대상은_쓴다() {
        jdbc.execute("""
                CREATE FUNCTION fake_second_row_fails() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.trade_date = DATE '2026-01-12'
                       AND NEW.asset_id = (SELECT id FROM asset WHERE market = 'KR' AND ticker = '005930') THEN
                        RAISE unique_violation USING CONSTRAINT = 'uq_asset_daily_price_vintage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER fake_late_row BEFORE INSERT ON asset_daily_price FOR EACH ROW EXECUTE FUNCTION fake_second_row_fails()");
        try {
            JobRunSummary summary = job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());

            assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
            assertThat(summary.errorCode()).isEqualTo("DB_CONFLICT");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM asset_daily_price p JOIN asset a ON a.id = p.asset_id WHERE a.ticker = '005930'
                    """, Integer.class)).as("the two rows before the failing one were rolled back").isZero();
            assertThat(rows()).isEqualTo(4);
        } finally {
            jdbc.execute("DROP TRIGGER fake_late_row ON asset_daily_price");
            jdbc.execute("DROP FUNCTION fake_second_row_fails()");
        }
    }

    @Test
    void 방금_시작한_실행이_있으면_거절하고_요청을_보내지_않는다() {
        jdbc.update("INSERT INTO job_run (job_name, run_trigger, status, started_at) VALUES ('KIS_DAILY_PRICE', 'MANUAL', 'RUNNING', ?::timestamptz)",
                AFTER_CLOSE.minus(Duration.ofMinutes(5)).toString());
        FakeQuoteClient client = threeTargets();

        assertThatThrownBy(() -> job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all()))
                .isInstanceOf(JobAlreadyRunningException.class);

        assertThat(client.calls).isEmpty();
        assertThat(clientsCreated).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_run", Integer.class)).isEqualTo(1);
    }

    @Test
    void 두_시간_넘게_RUNNING이면_ABANDONED로_닫고_새_실행이_진행된다() {
        long stale = jdbc.queryForObject("""
                INSERT INTO job_run (job_name, run_trigger, status, started_at)
                VALUES ('KIS_DAILY_PRICE', 'SCHEDULED', 'RUNNING', ?::timestamptz) RETURNING id
                """, Long.class, AFTER_CLOSE.minus(Duration.ofHours(3)).toString());

        JobRunSummary summary = job(THREE, threeTargets()).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        Map<String, Object> old = runRow(stale);
        assertThat(old).containsEntry("status", "FAILED").containsEntry("error_code", "ABANDONED");
        assertThat(old.get("finished_at")).isNotNull();
    }

    @Test
    void 대상_목록이_자산과_어긋나면_요청_전에_TARGETS_INVALID로_끝난다() {
        List<String> bad = List.of(
                "KR,999999,,ADJUSTED",
                "KR,005930,NAS,ADJUSTED",
                "KR,069500,,ADJUSTED",
                "KR,005930,,RAW",
                "US,SPY,,ADJUSTED",
                "US,SPY,XXX,ADJUSTED",
                "US,SPY,AMS,RAW",
                "KR,005930,,ADJUSTED\nKR,005930,,ADJUSTED");
        for (String row : bad) {
            FakeQuoteClient client = threeTargets();
            JobRunSummary summary = job("market,ticker,exchange_code,price_basis\n" + row + "\n", client)
                    .run(RunTrigger.MANUAL, TargetFilter.all());

            assertThat(summary.status()).as(row).isEqualTo(JobStatus.FAILED);
            assertThat(summary.errorCode()).as(row).isEqualTo("TARGETS_INVALID");
            assertThat(client.calls).as(row).isEmpty();
            assertThat(clientsCreated).as(row).hasValue(0);
            assertThat(summary.requestCount()).as(row).isZero();
        }
        assertThat(rows()).isZero();
    }

    @Test
    void 목록에_없는_종목을_고르면_TARGETS_INVALID다() {
        FakeQuoteClient client = threeTargets();

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.parse("KR:005930,US:AAPL"));

        assertThat(summary.errorCode()).isEqualTo("TARGETS_INVALID");
        assertThat(client.calls).isEmpty();
    }

    @Test
    void 고른_종목만_돈다() {
        FakeQuoteClient client = threeTargets();

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.parse("US:SPY"));

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(client.calls).extracting(FakeQuoteClient.Call::key).containsExactly("US:SPY");
    }

    @Test
    void 예상하지_못한_오류는_클래스_이름만_남기고_비밀값은_기록과_로그에_없다(CapturedOutput output) {
        String leaked = "FAKE-APP-SECRET-0001";
        FakeQuoteClient client = threeTargets().crash("KR:069500", new IllegalStateException("secret " + leaked + " leaked"));

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("INTERNAL_ERROR");
        String detail = (String) runRow(summary.jobRunId()).get("error_detail");
        assertThat(detail).isEqualTo("IllegalStateException").doesNotContain(leaked);
        assertThat(output.getAll()).doesNotContain(leaked);
    }

    @Test
    void 실패_종목의_설명은_500자에서_잘린다() {
        FakeQuoteClient client = threeTargets().fail("KR:005930", "KIS_HTTP", "x".repeat(900));

        JobRunSummary summary = job(THREE, client).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat((String) runRow(summary.jobRunId()).get("error_detail")).hasSize(500);
    }

    private KisDailyPriceJob metricJob(FakeQuoteClient client, MeterRegistry registry) {
        return KisDailyPriceJob.create(() -> client, KisTargetCatalog.fromCsv(THREE), jdbc, transactions, clock, PROPERTIES,
                new CollectMetrics(registry));
    }

    @Test
    void 실행_하나는_끝난_상태_한_계열만_한_번_센다() {
        var registry = new SimpleMeterRegistry();

        metricJob(threeTargets(), registry).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "KIS_DAILY_PRICE", "status", "SUCCEEDED")).isEqualTo(1);
        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isEqualTo(1);
    }

    @Test
    void 일부_종목이_실패하면_PARTIAL_모두_실패하면_FAILED로_각각_한_번_센다() {
        var registry = new SimpleMeterRegistry();
        FakeQuoteClient partial = threeTargets().fail("US:SPY", "KIS_HTTP", "HTTP 500");
        FakeQuoteClient allFail = new FakeQuoteClient().fail("KR:005930", "KIS_HTTP", "HTTP 500")
                .fail("KR:069500", "KIS_HTTP", "HTTP 500").fail("US:SPY", "KIS_HTTP", "HTTP 500");

        metricJob(partial, registry).run(RunTrigger.MANUAL, TargetFilter.all());
        metricJob(allFail, registry).run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "KIS_DAILY_PRICE", "status", "PARTIAL")).isEqualTo(1);
        assertThat(MetricCounts.count(registry, "antlab.collect.run", "job", "KIS_DAILY_PRICE", "status", "FAILED")).isEqualTo(1);
        assertThat(MetricCounts.total(registry, "antlab.collect.run")).isEqualTo(2);
    }
}

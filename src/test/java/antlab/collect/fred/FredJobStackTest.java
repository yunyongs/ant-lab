package antlab.collect.fred;

import static antlab.collect.fred.FredHarness.FAKE_KEY;
import static antlab.collect.fred.FredHarness.fixture;
import static antlab.collect.fred.FredHarness.page;
import static antlab.collect.fred.FredHarness.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.CollectTestData;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunSummary;
import antlab.collect.series.MacroRules;
import antlab.collect.series.SeriesCatalog;
import antlab.support.JpaMappingTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole chain except the network: the job, the real FRED client (allow-list, rate limit, paging, parsing), a mock server
 * that answers with the real shape-probe answers (files real_*), and a real PostgreSQL 17.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class FredJobStackTest {

    private static final String CATALOG = """
            code,source,source_series_id,unit,frequency,release_lag_days,revisable
            US_DGS2,FRED,DGS2,PERCENT,DAILY,1,false
            US_CPILFESL,FRED,CPILFESL,INDEX,MONTHLY,10,true
            US_ICSA,FRED,ICSA,COUNT,WEEKLY,5,true
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CollectTestData data;
    private FredHarness harness;
    private CollectTestData.TestClock clock;

    @BeforeEach
    void seed() {
        data = new CollectTestData(jdbc);
        data.cleanUp();
        harness = new FredHarness();
        clock = new CollectTestData.TestClock(FredHarness.START);
    }

    @AfterEach
    void clean() {
        data.cleanUp();
    }

    private JobRunSummary run(String... codes) {
        return FredSeriesJob.create(harness.factory::newClient, SeriesCatalog.fromCsv(CATALOG), jdbc, transactions, clock,
                harness.properties).run(RunTrigger.MANUAL, Set.of(codes));
    }

    /** The publication floor of the test series (the engine's rules): CPI month end + 10 days, DGS2 the next day. */
    private static LocalDate floor(String code, LocalDate ref) {
        return code.equals("US_CPILFESL") ? ref.plusMonths(1).minusDays(1).plusDays(10) : ref.plusDays(1);
    }

    /**
     * The documented as-of read (erd 10.6): a row is known from its realtime_start; a row without one is dated by the
     * publication floor, and a vintage 2 or later of such a row by the later of the floor and the day it was stored.
     */
    private BigDecimal asOf(String code, String refDate, String date) {
        LocalDate ref = LocalDate.parse(refDate);
        LocalDate day = LocalDate.parse(date);
        BigDecimal best = null;
        int bestVintage = 0;
        for (var row : jdbc.queryForList("""
                SELECT o.value, o.vintage_no, o.realtime_start, (o.observed_at AT TIME ZONE 'UTC')::date AS stored_on
                FROM series_observation o JOIN series s ON s.id = o.series_id
                WHERE s.code = ? AND o.ref_date = ?::date
                """, code, refDate)) {
            int vintage = ((Number) row.get("vintage_no")).intValue();
            LocalDate floor = floor(code, ref);
            LocalDate from;
            if (row.get("realtime_start") != null) {
                from = ((java.sql.Date) row.get("realtime_start")).toLocalDate();
            } else if (vintage == 1) {
                from = floor;
            } else {
                LocalDate stored = ((java.sql.Date) row.get("stored_on")).toLocalDate();
                from = stored.isAfter(floor) ? stored : floor;
            }
            if (!from.isAfter(day) && vintage > bestVintage) {
                best = (BigDecimal) row.get("value");
                bestVintage = vintage;
            }
        }
        return best;
    }

    private void assertRulesHold() {
        for (String rule : MacroRules.ALL) {
            assertThat(jdbc.queryForList(rule)).as(rule).isEmpty();
        }
    }

    private void assertNoKey(CapturedOutput output, long runId) {
        assertThat(output.getAll()).doesNotContain(FAKE_KEY);
        assertThat(jdbc.queryForList("SELECT coalesce(error_detail, '') FROM job_run WHERE id = ?", String.class, runId).getFirst())
                .doesNotContain(FAKE_KEY);
    }

    @Test
    void 두_빈티지는_둘_다_저장되고_개정_전_날짜의_조회는_첫_값을_돌려준다(CapturedOutput output) {
        harness.respondFixture("real_cpilfesl_2022_vintages");

        JobRunSummary summary = run("US_CPILFESL");

        harness.server.verify();
        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.requestCount()).isEqualTo(1);
        assertThat(summary.insertedCount()).isEqualTo(17);
        assertThat(summary.revisedCount()).as("vintage 2 and later").isEqualTo(13);
        assertThat(asOf("US_CPILFESL", "2022-12-01", "2023-02-09")).isEqualByComparingTo("300.974");
        assertThat(asOf("US_CPILFESL", "2022-12-01", "2023-02-10")).isEqualByComparingTo("301.460");
        assertThat(asOf("US_CPILFESL", "2022-12-01", "2024-02-09")).isEqualByComparingTo("301.423");
        assertThat(asOf("US_CPILFESL", "2022-12-01", "2026-11-18")).isEqualByComparingTo("301.394");
        assertThat(asOf("US_CPILFESL", "2022-12-01", "2023-01-11")).as("not yet released").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation WHERE observed_at = ?::timestamptz", Integer.class,
                FredHarness.START.toString())).as("observed_at is the run start").isEqualTo(17);
        assertRulesHold();
        assertNoKey(output, summary.jobRunId());
    }

    @Test
    void 같은_응답으로_다시_돌리면_요청만_나가고_행은_늘지_않는다() {
        harness.respondFixture("real_cpilfesl_2022_vintages");
        run("US_CPILFESL");
        harness.server.reset();
        harness.respondFixture("real_cpilfesl_2022_vintages");
        clock.advance(Duration.ofDays(1));

        JobRunSummary again = run("US_CPILFESL");

        harness.server.verify();
        assertThat(again.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(again.requestCount()).isEqualTo(1);
        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation", Integer.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series", Integer.class)).as("the series row is reused").isEqualTo(1);
        assertRulesHold();
    }

    @Test
    void 첫_빈티지가_두_발표_주기보다_오래된_행은_빈티지_날짜_없이_저장된다() {
        harness.respondFixture("real_cpilfesl_1990_first_vintage");

        JobRunSummary summary = run("US_CPILFESL");

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).isEqualTo(6);
        assertThat(jdbc.queryForList("""
                SELECT ref_date::text || ' v' || vintage_no || ' ' || coalesce(realtime_start::text, 'NULL') || ' ' || value
                FROM series_observation WHERE ref_date = DATE '1990-01-01' ORDER BY vintage_no
                """, String.class)).containsExactly("1990-01-01 v1 NULL 132.30000000", "1990-01-01 v2 2002-02-20 132.10000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation WHERE realtime_start IS NULL", Integer.class)).isEqualTo(3);
        assertThat(asOf("US_CPILFESL", "1990-01-01", "2026-11-18")).isEqualByComparingTo("132.1");
        assertThat(asOf("US_CPILFESL", "1990-01-01", "1990-02-09")).as("before the publication floor").isNull();
        assertThat(asOf("US_CPILFESL", "1990-01-01", "1995-06-01")).as("pre-vintage row is still readable").isEqualByComparingTo("132.3");
        assertThat(asOf("US_CPILFESL", "1990-01-01", "2002-02-19")).isEqualByComparingTo("132.3");
        assertThat(asOf("US_CPILFESL", "1990-01-01", "2002-02-20")).isEqualByComparingTo("132.1");
        assertRulesHold();
    }

    @Test
    void 주간_계열도_같은_규칙으로_저장된다() {
        harness.respondFixture("real_icsa_1990_first_vintage");

        JobRunSummary summary = run("US_ICSA");

        assertThat(summary.insertedCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation WHERE realtime_start IS NULL", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT value FROM series_observation WHERE ref_date = DATE '1990-01-06'", BigDecimal.class))
                .isEqualByComparingTo("355000");
        assertRulesHold();
    }

    @Test
    void 일간_계열은_점을_건너뛰고_저장하며_다시_돌려도_늘지_않는다() {
        harness.respondFixture("real_dgs2_2025_11");

        JobRunSummary summary = run("US_DGS2");

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).as("12 rows, one of them '.'").isEqualTo(11);
        assertThat(summary.revisedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation WHERE realtime_start IS NOT NULL OR available_on IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation WHERE ref_date = DATE '2025-11-11'", Integer.class)).isZero();
        harness.server.reset();
        harness.respondFixture("real_dgs2_2025_11");
        clock.advance(Duration.ofDays(1));

        JobRunSummary again = run("US_DGS2");

        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(11);
        assertRulesHold();
    }

    @Test
    void 일간_계열의_다음_요청은_저장된_마지막_날짜보다_조회_기간만큼_앞에서_시작한다() {
        harness.expectObservations().andExpect(queryParam("observation_start", "1990-01-01"))
                .andRespond(withSuccess(fixture("real_dgs2_2025_11"), MediaType.APPLICATION_JSON));
        run("US_DGS2");
        harness.server.reset();
        harness.expectObservations().andExpect(queryParam("observation_start", "2025-09-19"))
                .andRespond(withSuccess(fixture("real_dgs2_2025_11"), MediaType.APPLICATION_JSON));
        clock.advance(Duration.ofDays(1));

        run("US_DGS2");

        harness.server.verify();
    }

    @Test
    void 일간_계열의_값이_바뀌면_다음_빈티지로_쌓고_앞선_행은_그대로다() {
        harness.respondFixture("real_dgs2_2025_11");
        run("US_DGS2");
        String changed = fixture("real_dgs2_2025_11").replace("\"date\":\"2025-11-12\",\"value\":\"3.56\"", "\"date\":\"2025-11-12\",\"value\":\"3.57\"");
        harness.server.reset();
        harness.respond(changed);
        clock.advance(Duration.ofDays(1));

        JobRunSummary again = run("US_DGS2");

        assertThat(again.insertedCount()).isEqualTo(1);
        assertThat(again.revisedCount()).isEqualTo(1);
        assertThat(again.unchangedCount()).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT value || '@v' || vintage_no FROM series_observation WHERE ref_date = DATE '2025-11-12' ORDER BY vintage_no",
                String.class)).containsExactly("3.56000000@v1", "3.57000000@v2");
        assertRulesHold();
    }

    @Test
    void 저장된_빈티지가_출처_이력과_다르면_그_계열은_아무것도_쓰지_않고_실패한다(CapturedOutput output) {
        harness.respondFixture("real_cpilfesl_2022_vintages");
        run("US_CPILFESL");
        String rewritten = fixture("real_cpilfesl_2022_vintages").replace("\"value\":\"300.974\"", "\"value\":\"300.000\"");
        harness.server.reset();
        harness.respond(rewritten);
        clock.advance(Duration.ofDays(1));

        JobRunSummary again = run("US_CPILFESL");

        assertThat(again.status()).isEqualTo(JobStatus.FAILED);
        assertThat(again.errorCode()).isEqualTo("VINTAGE_MISMATCH");
        assertThat(again.insertedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_observation", Integer.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT error_detail FROM job_run WHERE id = ?", String.class, again.jobRunId()))
                .startsWith("US_CPILFESL:").doesNotContain("300");
        assertNoKey(output, again.jobRunId());
    }

    @Test
    void 새_개정이_나오면_그것만_다음_빈티지로_더한다() {
        harness.respondFixture("real_cpilfesl_2022_vintages");
        run("US_CPILFESL");
        String closedLast = fixture("real_cpilfesl_2022_vintages")
                .replace("{\"realtime_start\":\"2026-02-13\",\"realtime_end\":\"9999-12-31\",\"date\":\"2023-03-01\",\"value\":\"305.147\"}",
                        row("2023-03-01", "305.147", "2026-02-13", "2026-11-18") + "," + row("2023-03-01", "305.200", "2026-11-19", "9999-12-31"))
                .replace("\"count\":17", "\"count\":18");
        harness.server.reset();
        harness.respond(closedLast);
        clock.advance(Duration.ofDays(1));

        JobRunSummary again = run("US_CPILFESL");

        assertThat(again.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(again.insertedCount()).isEqualTo(1);
        assertThat(again.revisedCount()).isEqualTo(1);
        assertThat(again.unchangedCount()).isEqualTo(17);
        assertThat(asOf("US_CPILFESL", "2023-03-01", "2026-11-18")).isEqualByComparingTo("305.147");
        assertThat(asOf("US_CPILFESL", "2023-03-01", "2026-11-19")).isEqualByComparingTo("305.200");
        assertRulesHold();
    }

    @Test
    void 거절된_응답은_부분_성공이고_기록과_로그에_키가_없다(CapturedOutput output) {
        harness.respondFixture("real_dgs2_2025_11");
        harness.expectObservations().andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error_code\":400,\"error_message\":\"echo " + FAKE_KEY + "\"}").contentType(MediaType.APPLICATION_JSON));

        JobRunSummary summary = run("US_DGS2", "US_ICSA");

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("FRED_HTTP");
        assertThat(summary.insertedCount()).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT error_detail FROM job_run WHERE id = ?", String.class, summary.jobRunId()))
                .startsWith("US_ICSA:").contains("HTTP 400").contains("error_code=400");
        assertNoKey(output, summary.jobRunId());
    }

    @Test
    void 쪽이_여러_개인_답도_한_계열로_모두_저장한다() {
        var paged = new FredHarness(FredHarness.properties(1, 30, 2));
        harness = paged;
        paged.respond(page(3, 0, row("2025-11-03", "3.6", "2026-11-18", "2026-11-18"), row("2025-11-04", "3.58", "2026-11-18", "2026-11-18")));
        paged.respond(page(3, 2, row("2025-11-05", "3.63", "2026-11-18", "2026-11-18")));

        JobRunSummary summary = run("US_DGS2");

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.requestCount()).isEqualTo(2);
        assertThat(summary.insertedCount()).isEqualTo(3);
    }

    @Test
    void 최신_값_계열의_나중_개정은_그_개정이_저장된_날_전에는_보이지_않는다() {
        harness.respond(page(1, 0, row("2025-11-03", "3.60", "2026-11-18", "2026-11-18")));
        run("US_DGS2");
        harness.server.reset();
        harness.respond(page(1, 0, row("2025-11-03", "3.70", "2026-11-19", "2026-11-19")));
        clock.advance(Duration.ofDays(1));

        run("US_DGS2");

        assertThat(asOf("US_DGS2", "2025-11-03", "2025-11-03")).as("before the floor").isNull();
        assertThat(asOf("US_DGS2", "2025-11-03", "2025-11-04")).as("vintage 1 by the floor").isEqualByComparingTo("3.6");
        assertThat(asOf("US_DGS2", "2025-11-03", "2026-11-18")).as("the day before the revision was stored").isEqualByComparingTo("3.6");
        assertThat(asOf("US_DGS2", "2025-11-03", "2026-11-19")).isEqualByComparingTo("3.7");
        assertRulesHold();
    }

    @Test
    void 공표_하한_전에_모은_두_빈티지는_하한_당일부터_최신이_보인다() {
        // ref 2026-11-19 is stored at once on 2026-11-18 and 2026-11-19 (a same-day value): floor = ref + 1 = 2026-11-20
        harness.respond(page(1, 0, row("2026-11-19", "3.60", "2026-11-18", "2026-11-18")));
        run("US_DGS2");
        harness.server.reset();
        harness.respond(page(1, 0, row("2026-11-19", "3.70", "2026-11-19", "2026-11-19")));
        clock.advance(Duration.ofDays(1));

        run("US_DGS2");

        assertThat(asOf("US_DGS2", "2026-11-19", "2026-11-19")).as("both stored before the floor").isNull();
        assertThat(asOf("US_DGS2", "2026-11-19", "2026-11-20")).as("the latest from the floor day").isEqualByComparingTo("3.7");
        assertRulesHold();
    }
}

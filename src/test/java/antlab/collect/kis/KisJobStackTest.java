package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.FAKE_KEY;
import static antlab.collect.kis.KisHarness.FAKE_SECRET;
import static antlab.collect.kis.KisHarness.FAKE_TOKEN;
import static antlab.collect.kis.KisHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.CollectTestData;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunSummary;
import antlab.collect.price.KisDailyPriceJob;
import antlab.collect.price.KisTargetCatalog;
import antlab.collect.price.PriceJobProperties;
import antlab.collect.price.TargetFilter;
import antlab.support.JpaMappingTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole chain except the network: the job, the real quote client (allow-list, token, rate limit, parsing), a mock
 * server that answers with the synthetic FAKE fixtures, and a real PostgreSQL 17.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class KisJobStackTest {

    private static final String EMPTY_PAGE = "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"output1\":{\"zdiv\":\"4\"},\"output2\":[]}";
    private static final String KR_AND_US = """
            market,ticker,exchange_code,price_basis
            KR,005930,,ADJUSTED
            US,SPY,AMS,ADJUSTED
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CollectTestData data;
    private KisHarness harness;

    @BeforeEach
    void seed() {
        data = new CollectTestData(jdbc);
        data.cleanUp();
        data.seedAssets();
        harness = new KisHarness();
    }

    @AfterEach
    void clean() {
        data.cleanUp();
    }

    private JobRunSummary run(String csv) {
        return KisDailyPriceJob.create(harness.factory::newClient, KisTargetCatalog.fromCsv(csv), jdbc, transactions,
                harness.clock, new PriceJobProperties(10, 30, Duration.ofHours(2))).run(RunTrigger.MANUAL, TargetFilter.all());
    }

    private void expectKrAndUsPages() {
        harness.respondDomestic("kr_daily_p1");
        harness.respondOverseas("us_daily_p1");
        harness.respondOverseas("us_daily_p2");
        harness.expectGet(KisEndpoint.OVERSEAS_DAILY).andRespond(withSuccess(EMPTY_PAGE, MediaType.APPLICATION_JSON));
    }

    @Test
    void 한국과_미국_종목을_실제_클라이언트로_받아_쓰고_토큰은_한_번만_발급한다() {
        harness.expectTokenOk();
        expectKrAndUsPages();

        JobRunSummary summary = run(KR_AND_US);

        harness.server.verify();
        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.requestCount()).as("one token and four quote pages").isEqualTo(5);
        assertThat(summary.insertedCount()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price WHERE exchange_code = 'AMS'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT close_price FROM asset_daily_price WHERE trade_date = DATE '2026-01-08' AND exchange_code IS NULL",
                java.math.BigDecimal.class)).isEqualByComparingTo("10850.5");
        for (String rule : CollectTestData.RULES) {
            assertThat(jdbc.queryForList(rule)).as(rule).isEmpty();
        }
    }

    @Test
    void 같은_응답으로_다시_돌리면_요청만_나가고_행은_늘지_않는다() {
        harness.expectTokenOk();
        expectKrAndUsPages();
        run(KR_AND_US);
        harness.server.reset();
        expectKrAndUsPages();

        JobRunSummary again = run(KR_AND_US);

        harness.server.verify();
        assertThat(again.requestCount()).as("the token is reused").isEqualTo(4);
        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price", Integer.class)).isEqualTo(8);
    }

    @Test
    void 가격_기준은_요청_파라미터로_간다_수정주가는_0이고_원본은_1이다() {
        harness.expectTokenOk();
        harness.expectGet(KisEndpoint.DOMESTIC_DAILY).andExpect(queryParam("FID_ORG_ADJ_PRC", "0"))
                .andRespond(withSuccess(fixture("kr_daily_p1"), MediaType.APPLICATION_JSON));
        harness.expectGet(KisEndpoint.DOMESTIC_DAILY).andExpect(queryParam("FID_ORG_ADJ_PRC", "1"))
                .andRespond(withSuccess(fixture("kr_daily_p2"), MediaType.APPLICATION_JSON));

        JobRunSummary summary = run("""
                market,ticker,exchange_code,price_basis
                KR,005930,,ADJUSTED
                KR,069500,,RAW
                """);

        harness.server.verify();
        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM asset_daily_price p JOIN asset a ON a.id = p.asset_id
                WHERE a.ticker = '069500' AND p.price_basis = 'RAW'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void 거절된_응답은_부분_성공이고_기록과_로그에_키_비밀_토큰이_없다(CapturedOutput output) {
        harness.expectTokenOk();
        harness.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rejected"), MediaType.APPLICATION_JSON));
        harness.respondOverseas("us_daily_p1");
        harness.respondOverseas("us_daily_p2");
        harness.expectGet(KisEndpoint.OVERSEAS_DAILY).andRespond(withSuccess(EMPTY_PAGE, MediaType.APPLICATION_JSON));

        JobRunSummary summary = run(KR_AND_US);

        assertThat(summary.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(summary.errorCode()).isEqualTo("KIS_REJECTED");
        assertThat(summary.insertedCount()).isEqualTo(5);
        String detail = jdbc.queryForObject("SELECT error_detail FROM job_run WHERE id = ?", String.class, summary.jobRunId());
        assertThat(detail).startsWith("KR:005930:").contains("msg_cd=OPSQ0002");
        for (String secret : new String[] {FAKE_KEY, FAKE_SECRET, FAKE_TOKEN}) {
            assertThat(detail).doesNotContain(secret);
            assertThat(output.getAll()).doesNotContain(secret);
        }
    }
}

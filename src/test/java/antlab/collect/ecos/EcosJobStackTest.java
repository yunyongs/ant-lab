package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.answer;
import static antlab.collect.ecos.EcosHarness.fixture;
import static antlab.collect.ecos.EcosHarness.path;
import static antlab.collect.ecos.EcosHarness.result;
import static antlab.collect.ecos.EcosHarness.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.CollectTestData;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunSummary;
import antlab.collect.series.MacroRules;
import antlab.collect.series.SeriesCatalog;
import antlab.support.JpaMappingTest;
import java.util.List;
import java.util.Set;
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
 * The whole chain except the network: the job, the real ECOS client (allow-list, rate limit, paging, parsing), a mock server
 * that answers with the real shape-probe answers (files real_*), and a real PostgreSQL 17.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class EcosJobStackTest {

    private static final String CATALOG = """
            code,source,source_series_id,unit,frequency,release_lag_days,revisable
            KR_KTB3Y,ECOS,817Y002/010200000,PERCENT,DAILY,0,false
            KR_CPI,ECOS,901Y009/0,INDEX,MONTHLY,7,true
            KR_CLI,ECOS,901Y067/I16E,INDEX,MONTHLY,35,true
            KR_EXPORTS,ECOS,901Y118/T002,USD_THOUSAND,MONTHLY,15,true
            KR_USDKRW,ECOS,731Y001/0000001,KRW,DAILY,0,false
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CollectTestData data;
    private EcosHarness harness;
    private CollectTestData.TestClock clock;

    @BeforeEach
    void seed() {
        data = new CollectTestData(jdbc);
        data.cleanUp();
        harness = new EcosHarness();
        clock = new CollectTestData.TestClock(EcosHarness.START);
    }

    @AfterEach
    void clean() {
        data.cleanUp();
    }

    private JobRunSummary run(String... codes) {
        return EcosSeriesJob.create(harness.factory::newClient, SeriesCatalog.fromCsv(CATALOG), jdbc, transactions, clock,
                harness.properties).run(RunTrigger.MANUAL, Set.of(codes));
    }

    private void expect(String stat, String cycle, String start, String end, String item, String body) {
        harness.expectPath(path(1, 100000, stat, cycle, start, end, item)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private List<String> stored(String code) {
        return jdbc.queryForList("""
                SELECT o.ref_date || '/' || o.vintage_no || '/' || o.value::float8 || '/' || coalesce(o.available_on::text, '-')
                FROM series_observation o JOIN series s ON s.id = o.series_id WHERE s.code = ? ORDER BY o.ref_date, o.vintage_no
                """, String.class, code);
    }

    @Test
    void 다섯_계열을_진짜_응답_모양으로_받아_저장하고_요청은_계열마다_하나다() {
        expect("817Y002", "D", "19900101", "20261119", "010200000", fixture("real_ktb3y_817y002_d"));
        expect("901Y009", "M", "199001", "202611", "0", fixture("real_cpi_901y009_m"));
        expect("901Y067", "M", "199001", "202611", "I16E", fixture("real_cli_901y067_m"));
        expect("901Y118", "M", "199001", "202611", "T002", fixture("real_exports_901y118_m"));
        expect("731Y001", "D", "19900101", "20261119", "0000001", fixture("real_usdkrw_731y001_d"));

        JobRunSummary summary = run();

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.requestCount()).isEqualTo(5);
        assertThat(summary.insertedCount()).isEqualTo(12 + 2 + 2 + 2 + 12);
        harness.server.verify();
        assertThat(stored("KR_CPI")).containsExactly("2025-09-01/1/117.06/2025-10-07", "2025-10-01/1/117.42/2025-11-07");
        assertThat(stored("KR_CLI")).containsExactly("2025-09-01/1/99.7/2025-11-04", "2025-10-01/1/99.6/2025-12-05");
        assertThat(stored("KR_EXPORTS")).containsExactly("2025-09-01/1/65904118/2025-10-15", "2025-10-01/1/59511578/2025-11-15");
        assertThat(stored("KR_KTB3Y")).hasSize(12).first().isEqualTo("2025-11-03/1/2.741/2025-11-03");
        assertThat(stored("KR_USDKRW")).hasSize(12).first().isEqualTo("2025-11-03/1/1427.4/2025-11-03");
        for (String rule : MacroRules.ALL) {
            assertThat(jdbc.queryForList(rule)).as(rule).isEmpty();
        }
    }

    @Test
    void 다시_돌리면_월별은_처음부터_읽고_값이_그대로면_추가가_없다() {
        expect("901Y009", "M", "199001", "202611", "0", fixture("real_cpi_901y009_m"));
        run("KR_CPI");
        clock.advance(java.time.Duration.ofHours(1));
        harness = new EcosHarness();
        expect("901Y009", "M", "199001", "202611", "0", fixture("real_cpi_901y009_m"));

        JobRunSummary again = run("KR_CPI");

        assertThat(again.insertedCount()).isZero();
        assertThat(again.unchangedCount()).isEqualTo(2);
        harness.server.verify();
    }

    @Test
    void 데이터_없음_답은_요청_수에_세어지고_계열_실패로_기록되며_키_오류는_뒤_계열을_멈춘다() {
        harness.respond(result("INFO-200"));

        JobRunSummary empty = run("KR_KTB3Y");

        assertThat(empty.status()).isEqualTo(JobStatus.FAILED);
        assertThat(empty.errorCode()).isEqualTo("ECOS_EMPTY");
        assertThat(empty.requestCount()).isEqualTo(1);

        clock.advance(java.time.Duration.ofHours(3));
        harness = new EcosHarness();
        harness.respond(result("INFO-100"));
        JobRunSummary auth = run("KR_KTB3Y", "KR_USDKRW");

        assertThat(auth.errorCode()).isEqualTo("ECOS_AUTH");
        assertThat(auth.requestCount()).isEqualTo(1);
        assertThat(auth.failedTargetCount()).isEqualTo(2);
        harness.server.verify();
    }

    @Test
    void 쪽을_나눠도_한_계열로_저장되고_키는_로그에_없다(CapturedOutput output) {
        harness = new EcosHarness(EcosHarness.properties(1, 20, 2));
        harness.expectPath(path(1, 2, "817Y002", "D", "19900101", "20261119", "010200000")).andRespond(withSuccess(
                answer(3, row("817Y002", "010200000", "20251103", "2.7"), row("817Y002", "010200000", "20251104", "2.8")),
                MediaType.APPLICATION_JSON));
        harness.expectPath(path(3, 4, "817Y002", "D", "19900101", "20261119", "010200000")).andRespond(withSuccess(
                answer(3, row("817Y002", "010200000", "20251105", "2.9")), MediaType.APPLICATION_JSON));

        JobRunSummary summary = run("KR_KTB3Y");

        assertThat(summary.insertedCount()).isEqualTo(3);
        assertThat(summary.requestCount()).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain(EcosHarness.FAKE_KEY);
        assertThat(jdbc.queryForObject("SELECT error_detail FROM job_run WHERE id = ?", String.class, summary.jobRunId())).isNull();
    }
}

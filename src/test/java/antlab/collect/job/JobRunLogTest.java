package antlab.collect.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.CollectTestData;
import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.support.JpaMappingTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The run log on its own: one RUNNING row per job, stale recovery, and a finished row is never rewritten. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobRunLogTest {

    private static final Instant T0 = Instant.parse("2026-01-13T07:30:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private JobRunLog log;

    @BeforeEach
    void setUp() {
        new CollectTestData(jdbc).cleanUp();
        log = new JobRunLog(jdbc, transactions, Duration.ofHours(2));
    }

    @AfterEach
    void clean() {
        new CollectTestData(jdbc).cleanUp();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM job_run WHERE id = ?", id);
    }

    private static JobRunSummary summary(long id, JobStatus status, String code) {
        return new JobRunSummary(id, status, 3, 5, 1, 2, code == null ? 0 : 1, code);
    }

    @Test
    void 열린_실행이_있으면_둘째_시작은_거절되고_닫으면_다시_시작할_수_있다() {
        long first = log.start(JobName.KIS_DAILY_PRICE, RunTrigger.MANUAL, T0);

        assertThatThrownBy(() -> log.start(JobName.KIS_DAILY_PRICE, RunTrigger.SCHEDULED, T0.plusSeconds(60)))
                .isInstanceOf(JobAlreadyRunningException.class);

        log.finish(summary(first, JobStatus.SUCCEEDED, null), null, T0.plusSeconds(90));
        long second = log.start(JobName.KIS_DAILY_PRICE, RunTrigger.SCHEDULED, T0.plusSeconds(120));
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void 끝난_실행은_다시_쓰지_않는다() {
        long id = log.start(JobName.KIS_DAILY_PRICE, RunTrigger.MANUAL, T0);
        log.finish(summary(id, JobStatus.PARTIAL, "KIS_HTTP"), "first detail", T0.plusSeconds(10));

        log.finish(summary(id, JobStatus.SUCCEEDED, null), "second detail", T0.plusSeconds(20));

        assertThat(row(id)).containsEntry("status", "PARTIAL").containsEntry("error_detail", "first detail");
    }

    @Test
    void 종료_시각은_시작_시각보다_앞서지_않는다() {
        long id = log.start(JobName.KIS_DAILY_PRICE, RunTrigger.MANUAL, T0);

        log.finish(summary(id, JobStatus.SUCCEEDED, null), null, T0.minusSeconds(5));

        assertThat(jdbc.queryForObject("SELECT finished_at >= started_at FROM job_run WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void 정확히_두_시간이_지난_실행은_아직_살아_있다고_본다() {
        jdbc.update("INSERT INTO job_run (job_name, run_trigger, status, started_at) VALUES ('KIS_DAILY_PRICE', 'MANUAL', 'RUNNING', ?::timestamptz)",
                T0.minus(Duration.ofHours(2)).toString());

        assertThatThrownBy(() -> log.start(JobName.KIS_DAILY_PRICE, RunTrigger.MANUAL, T0))
                .isInstanceOf(JobAlreadyRunningException.class);
    }
}

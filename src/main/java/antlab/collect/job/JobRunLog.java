package antlab.collect.job;

import antlab.collect.JobName;
import antlab.collect.RunTrigger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the run log (job_run) with JdbcTemplate; the entity is read-only. Every call commits on its own, so a RUNNING
 * row is visible to a second process at once and a finished row survives whatever happens to the caller.
 */
public final class JobRunLog {

    static final int MAX_DETAIL = 500;
    static final String ABANDONED = "ABANDONED";

    private static final Logger LOG = LoggerFactory.getLogger(JobRunLog.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration staleAfter;

    public JobRunLog(JdbcTemplate jdbc, PlatformTransactionManager transactions, Duration staleAfter) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.staleAfter = staleAfter;
    }

    /**
     * Closes RUNNING rows of the job that started more than {@code staleAfter} before {@code startedAt} (a crashed run),
     * then opens a new run. The partial unique index uq_job_run_running lets only one RUNNING row per job exist.
     */
    public long start(JobName job, RunTrigger trigger, Instant startedAt) {
        try {
            Long id = tx.execute(status -> {
                int abandoned = jdbc.update("""
                        UPDATE job_run
                        SET status = 'FAILED', finished_at = ?, request_count = 0,
                            inserted_count = (SELECT count(*) FROM asset_daily_price p WHERE p.job_run_id = job_run.id)
                                           + (SELECT count(*) FROM series_observation o WHERE o.job_run_id = job_run.id),
                            revised_count = (SELECT count(*) FROM asset_daily_price p
                                             WHERE p.job_run_id = job_run.id AND p.vintage_no > 1)
                                          + (SELECT count(*) FROM series_observation o
                                             WHERE o.job_run_id = job_run.id AND o.vintage_no > 1),
                            unchanged_count = 0, failed_target_count = 0, error_code = ?,
                            error_detail = 'the run never finished; request and unchanged counts are lost', updated_at = ?
                        WHERE job_name = ? AND status = 'RUNNING' AND started_at < ?
                        """, utc(startedAt), ABANDONED, utc(startedAt), job.name(), utc(startedAt.minus(staleAfter)));
                if (abandoned > 0) {
                    LOG.warn("closed {} stale {} run(s) as {}", abandoned, job, ABANDONED);
                }
                return jdbc.queryForObject("""
                        INSERT INTO job_run (job_name, run_trigger, status, started_at)
                        VALUES (?, ?, 'RUNNING', ?) RETURNING id
                        """, Long.class, job.name(), trigger.name(), utc(startedAt));
            });
            return id;
        } catch (DuplicateKeyException e) {
            throw new JobAlreadyRunningException(job);
        }
    }

    /** Closes the run. {@code detail} must already be free of secrets; it is cut to 500 characters. */
    /** @return false when the row was not RUNNING any more (the run was closed as abandoned), so nothing was recorded */
    public boolean finish(JobRunSummary summary, String detail, Instant finishedAt) {
        String text = detail == null ? null : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        int updated = tx.execute(status -> jdbc.update("""
                UPDATE job_run
                SET status = ?, finished_at = GREATEST(?, started_at), request_count = ?, inserted_count = ?,
                    revised_count = ?, unchanged_count = ?, failed_target_count = ?, error_code = ?,
                    error_detail = ?, updated_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """, summary.status().name(), utc(finishedAt), summary.requestCount(), summary.insertedCount(),
                summary.revisedCount(), summary.unchangedCount(), summary.failedTargetCount(), summary.errorCode(),
                text, utc(finishedAt), summary.jobRunId()));
        if (updated != 1) {
            LOG.warn("run {} was not RUNNING any more, its result was not recorded", summary.jobRunId());
        }
        return updated == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

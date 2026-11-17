package antlab.collect.job;

import antlab.collect.JobStatus;

/** What one run did. {@code errorCode} is null for SUCCEEDED, else the first failure's stable code. */
public record JobRunSummary(long jobRunId, JobStatus status, int requestCount, int insertedCount, int revisedCount,
        int unchangedCount, int failedTargetCount, String errorCode) {
}

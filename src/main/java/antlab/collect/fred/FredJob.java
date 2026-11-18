package antlab.collect.fred;

import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunSummary;
import java.util.Set;

/** The FRED collector as the one-shot runner (and later the scheduler, M1-10) sees it. */
public interface FredJob {

    /**
     * Runs once and records the run.
     *
     * @param codes series codes of the catalog to collect; empty means every FRED series
     * @throws antlab.collect.job.JobAlreadyRunningException when a fresh run of the same job is open
     */
    JobRunSummary run(RunTrigger trigger, Set<String> codes);
}

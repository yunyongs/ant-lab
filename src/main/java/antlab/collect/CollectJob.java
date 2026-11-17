package antlab.collect;

import antlab.collect.job.JobRunSummary;
import antlab.collect.price.TargetFilter;

/** One collector job as the scheduler (M1-10) and the one-shot runner see it. */
public interface CollectJob {

    /** Runs once and records the run; throws {@code JobAlreadyRunningException} when a fresh run of the same job is open. */
    JobRunSummary run(RunTrigger trigger, TargetFilter filter);
}

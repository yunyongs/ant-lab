package antlab.collect.job;

import antlab.collect.JobName;

/** A fresh RUNNING row of the same job exists; no request was sent. */
public class JobAlreadyRunningException extends RuntimeException {

    public JobAlreadyRunningException(JobName job) {
        super("job " + job + " is already running", null, false, false);
    }
}

package antlab.collect.job;

/** The run is no longer RUNNING (another run closed it as abandoned); nothing more may be written under its id. */
public class RunLostException extends RuntimeException {

    public RunLostException(long jobRunId) {
        super("run " + jobRunId + " is no longer running", null, false, false);
    }
}

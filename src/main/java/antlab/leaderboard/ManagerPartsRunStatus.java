package antlab.leaderboard;

/**
 * How a manager parts run ended. {@code DONE}: it went through the queue up to its limit. {@code ALREADY_RUNNING}: another
 * run held the advisory lock. {@code ENGINE_UNAVAILABLE}: the engine could not answer and the run stopped.
 * {@code REFUSED}: a deployment setting forbids every run.
 */
public enum ManagerPartsRunStatus {
    DONE, ALREADY_RUNNING, ENGINE_UNAVAILABLE, REFUSED
}

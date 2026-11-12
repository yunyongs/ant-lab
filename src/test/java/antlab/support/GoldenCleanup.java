package antlab.support;

import org.testcontainers.lifecycle.Startable;

/** Golden test teardown: the container is stopped even when the harness cleanup fails. */
public final class GoldenCleanup {

    /** One teardown step that may throw. */
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    private GoldenCleanup() {
    }

    /** Closes the harness, then stops the container; either argument may be null (setup did not finish). */
    public static void closeThenStop(AutoCloseable harness, Startable container) throws Exception {
        closeThenStop(harness == null ? null : harness::close, container == null ? null : container::stop);
    }

    /** Runs {@code close} then {@code stop}; {@code stop} always runs. The first failure is thrown, a second is suppressed onto it. */
    static void closeThenStop(Step close, Step stop) throws Exception {
        Exception failure = null;
        try {
            if (close != null) {
                close.run();
            }
        } catch (Exception e) {
            failure = e;
        }
        try {
            if (stop != null) {
                stop.run();
            }
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

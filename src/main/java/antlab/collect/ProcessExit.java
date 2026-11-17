package antlab.collect;

/** Ends the process with an exit code; behind an interface so the one-shot runner can be tested without exiting. */
@FunctionalInterface
public interface ProcessExit {

    void exit(int code);
}

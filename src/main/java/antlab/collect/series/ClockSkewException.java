package antlab.collect.series;

/** A new vintage would carry an observation time that is not after the stored one (the clock went back). */
public class ClockSkewException extends RuntimeException {

    public ClockSkewException() {
        super("observation time is not after the stored vintage", null, false, false);
    }
}

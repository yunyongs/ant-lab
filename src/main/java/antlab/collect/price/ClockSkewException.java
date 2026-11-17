package antlab.collect.price;

/** A changed close would get an observation time that is not after the stored vintage's (the clock went back); nothing is written. */
final class ClockSkewException extends RuntimeException {

    ClockSkewException() {
        super("observed_at must increase with vintage_no", null, false, false);
    }
}

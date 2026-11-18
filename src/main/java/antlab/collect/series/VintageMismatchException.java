package antlab.collect.series;

/**
 * The source's vintage history no longer extends what is stored (a stored vintage is missing, differs, or two rows claim
 * one vintage). Nothing is written for the series; the message names the situation, never a value.
 */
public class VintageMismatchException extends RuntimeException {

    public VintageMismatchException(String message) {
        super(message, null, false, false);
    }
}

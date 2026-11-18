package antlab.season;

/** The open span after a position-only change, and whether a row was written. */
public record LineupChangeResult(Lineup lineup, boolean changed) {
}

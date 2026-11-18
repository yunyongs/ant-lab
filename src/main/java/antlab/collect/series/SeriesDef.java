package antlab.collect.series;

/**
 * One row of the tracked series catalog and of the {@code series} table. {@code code} is the engine's source series name,
 * {@code revisable} marks a series whose past values change (stored with all vintages when the source has them).
 */
public record SeriesDef(String code, SeriesSource source, String sourceSeriesId, String unit, SeriesFrequency frequency,
        int releaseLagDays, boolean revisable) {
}

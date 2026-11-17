package antlab.season;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Reconciliation stop policy. A season halts when the failed attempts of one pending turn reach
 * {@code haltAfterFailures}. A policy value, not a game value, so it is not stored per season.
 */
@Validated
@ConfigurationProperties("antlab.reconciliation")
public record ReconciliationProperties(@Min(1) @Max(100) int haltAfterFailures) {
}

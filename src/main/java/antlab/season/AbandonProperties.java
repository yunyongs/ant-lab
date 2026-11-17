package antlab.season;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Abandon policy. One abandon request runs at most this many auto turns; 60 is the longest season path, so the
 * default finishes a season in one request. Repeating the request continues from where it stopped.
 */
@Validated
@ConfigurationProperties("antlab.abandon")
public record AbandonProperties(@Min(1) @Max(60) int maxTurnsPerRequest) {
}

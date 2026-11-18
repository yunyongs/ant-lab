package antlab.collect.fred;

import antlab.collect.kis.SecretMasker;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the FRED collector. Off by default; the key is read from the environment only (a placeholder in
 * application.yml) and is required only when {@code enabled} is true.
 */
@Validated
@ConfigurationProperties("antlab.collect.fred")
public record FredProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @Min(1) @Max(2) @DefaultValue("1") int rps,
        @Min(1) @DefaultValue("30") int maxRequestsPerRun,
        @Min(1) @Max(100000) @DefaultValue("100000") int pageLimit,
        @DefaultValue("1990-01-01") LocalDate historyStart,
        @Min(0) @DefaultValue("60") int lookbackDays,
        @DefaultValue("2h") Duration staleAfter) {

    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** Names the missing environment variable, never a value. */
    void requireKey() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("antlab.collect.fred.enabled=true needs FRED_API_KEY");
        }
        if (!PLAIN_KEY.matcher(apiKey).matches()) {
            throw new IllegalStateException("FRED_API_KEY must be 8-64 letters, digits, '_' or '-'");
        }
    }

    @Override
    public String toString() {
        return "FredProperties[enabled=" + enabled + ", apiKey=" + SecretMasker.MASK + ", rps=" + rps
                + ", maxRequestsPerRun=" + maxRequestsPerRun + ", pageLimit=" + pageLimit + ", historyStart=" + historyStart
                + ", lookbackDays=" + lookbackDays + ", staleAfter=" + staleAfter + "]";
    }
}

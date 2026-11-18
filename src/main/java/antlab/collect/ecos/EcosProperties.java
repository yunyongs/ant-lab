package antlab.collect.ecos;

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
 * Settings of the ECOS collector. Off by default; the key is read from the environment only (a placeholder in
 * application.yml) and is required only when {@code enabled} is true.
 */
@Validated
@ConfigurationProperties("antlab.collect.ecos")
public record EcosProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @Min(1) @Max(2) @DefaultValue("1") int rps,
        @Min(1) @DefaultValue("20") int maxRequestsPerRun,
        @Min(1) @Max(100000) @DefaultValue("100000") int pageLimit,
        @DefaultValue("1990-01-01") LocalDate historyStart,
        @Min(0) @DefaultValue("60") int lookbackDays,
        @DefaultValue("2h") Duration staleAfter) {

    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9]{8,64}");

    /** Names the missing environment variable, never a value. */
    void requireKey() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("antlab.collect.ecos.enabled=true needs ECOS_API_KEY");
        }
        if (!PLAIN_KEY.matcher(apiKey).matches()) {
            throw new IllegalStateException("ECOS_API_KEY must be 8-64 letters or digits");
        }
    }

    @Override
    public String toString() {
        return "EcosProperties[enabled=" + enabled + ", apiKey=" + SecretMasker.MASK + ", rps=" + rps
                + ", maxRequestsPerRun=" + maxRequestsPerRun + ", pageLimit=" + pageLimit + ", historyStart=" + historyStart
                + ", lookbackDays=" + lookbackDays + ", staleAfter=" + staleAfter + "]";
    }
}

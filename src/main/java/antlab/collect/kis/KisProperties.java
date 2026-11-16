package antlab.collect.kis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the KIS quote client. Off by default; the keys are read from the environment only (placeholders in
 * application.yml) and are required only when {@code enabled} is true.
 */
@Validated
@ConfigurationProperties("antlab.collect.kis")
public record KisProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String appKey,
        @DefaultValue("") String appSecret,
        @DefaultValue("") String tokenCacheDir,
        @Min(1) @Max(2) @DefaultValue("2") int rps,
        @Min(1) @DefaultValue("60") int maxRequestsPerRun) {

    /** Names the missing environment variables, never a value. */
    void requireKeys() {
        List<String> missing = new ArrayList<>();
        if (appKey.isBlank()) {
            missing.add("KIS_APP_KEY");
        }
        if (appSecret.isBlank()) {
            missing.add("KIS_APP_SECRET");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("antlab.collect.kis.enabled=true needs " + String.join(" and ", missing));
        }
    }

    /** The shared token directory, or null for a memory-only token. */
    Path tokenCachePath() {
        return tokenCacheDir.isBlank() ? null : Path.of(tokenCacheDir);
    }

    @Override
    public String toString() {
        return "KisProperties[enabled=" + enabled + ", appKey=" + SecretMasker.MASK + ", appSecret=" + SecretMasker.MASK
                + ", tokenCacheDir=" + tokenCacheDir + ", rps=" + rps + ", maxRequestsPerRun=" + maxRequestsPerRun + "]";
    }
}

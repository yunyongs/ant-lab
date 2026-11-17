package antlab.engine.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the engine client. The token comes from the environment only and is never printed. With
 * {@code path-source=engine} the URL and a token of at least {@value #MIN_TOKEN_LENGTH} characters are required, so a
 * mis-configured deployment fails at startup instead of at the first season start.
 */
@Validated
@ConfigurationProperties("antlab.engine")
public record EngineProperties(
        @DefaultValue("") String url,
        @DefaultValue("") String token,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("3s") Duration pathsTimeout,
        @DefaultValue("15s") Duration projectionsTimeout,
        @DefaultValue("java") PathSourceMode pathSource,
        @DefaultValue("m2-sb6-v1") String pathVersion,
        @Min(100) @Max(5000) @DefaultValue("2000") int projectionPaths) {

    public enum PathSourceMode { JAVA, ENGINE }

    public static final int MIN_TOKEN_LENGTH = 32;
    static final Set<String> PATH_VERSIONS = Set.of("m2-sb6-v1", "p0-boot6-v1");
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(120);

    public EngineProperties {
        url = url.strip();
        requirePositive("connect-timeout", connectTimeout);
        requirePositive("paths-timeout", pathsTimeout);
        requirePositive("projections-timeout", projectionsTimeout);
        if (!PATH_VERSIONS.contains(pathVersion)) {
            throw new IllegalArgumentException("antlab.engine.path-version must be one of " + PATH_VERSIONS);
        }
        // any mode: a header value with a control or non-ASCII character makes the JDK echo the value in its exception
        if (token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException(
                    "ANTLAB_ENGINE_TOKEN must be printable ASCII without whitespace, as the engine requires");
        }
        if (pathSource == PathSourceMode.ENGINE) {
            if (url.isEmpty()) {
                throw new IllegalArgumentException("antlab.engine.path-source=engine needs ANTLAB_ENGINE_URL");
            }
            if (token.length() < MIN_TOKEN_LENGTH) {
                throw new IllegalArgumentException(
                        "antlab.engine.path-source=engine needs ANTLAB_ENGINE_TOKEN of at least " + MIN_TOKEN_LENGTH + " characters");
            }
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("antlab.engine." + name + " must be between 1ms and " + MAX_TIMEOUT);
        }
    }

    /** True when a call can be made at all; a blank URL or token means every call answers "unavailable". */
    boolean configured() {
        return !url.isEmpty() && !token.isEmpty();
    }

    @Override
    public String toString() {
        return "EngineProperties[url=" + url + ", token=" + (token.isEmpty() ? "" : "****") + ", connectTimeout=" + connectTimeout
                + ", pathsTimeout=" + pathsTimeout + ", projectionsTimeout=" + projectionsTimeout + ", pathSource=" + pathSource
                + ", pathVersion=" + pathVersion + ", projectionPaths=" + projectionPaths + "]";
    }
}

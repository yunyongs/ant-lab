package antlab.api.security;

import antlab.agent.AgentRunnerProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Compares the presented runner token with the configured one. Both sides are hashed first, so the comparison takes the
 * same time whatever the lengths are, and {@link MessageDigest#isEqual} is constant time. Without a usable token or
 * owner nothing matches (fail closed). The token value is never logged.
 */
@Slf4j
@Component
class RunnerTokenCheck {

    private final byte[] expected;

    RunnerTokenCheck(AgentRunnerProperties properties) {
        if (properties.tokenUsable() && properties.ownerConfigured()) {
            this.expected = sha256(properties.token());
        } else {
            this.expected = null;
            log.warn("The agent runner token or owner is not configured (token needs at least {} characters); every "
                    + "runner request answers 401", AgentRunnerProperties.MIN_TOKEN_LENGTH);
        }
    }

    boolean matches(String presented) {
        byte[] actual = sha256(presented == null ? "" : presented);
        return expected != null && MessageDigest.isEqual(expected, actual);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

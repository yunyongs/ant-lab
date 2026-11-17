package antlab.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the agent runner service. The token and the owner come from the environment only; neither has a default.
 * Binding does not validate them, because a binding failure report would print the rejected value: an unusable token
 * or a missing owner makes every runner request answer 401 instead.
 *
 * @param token                the shared service token (at least {@value #MIN_TOKEN_LENGTH} characters)
 * @param ownerMemberId        the human member that owns every agent this runner may act for
 * @param maxSeasonsPerWeek    seasons one agent may start in a rolling 7 days
 */
@ConfigurationProperties("antlab.agent-runner")
public record AgentRunnerProperties(String token, Long ownerMemberId, @DefaultValue("5") int maxSeasonsPerWeek) {

    public static final int MIN_TOKEN_LENGTH = 32;

    public boolean tokenUsable() {
        return token != null && token.length() >= MIN_TOKEN_LENGTH;
    }

    public boolean ownerConfigured() {
        return ownerMemberId != null && ownerMemberId > 0;
    }

    @Override
    public String toString() {
        return "AgentRunnerProperties[token=***, ownerMemberId=" + ownerMemberId + ", maxSeasonsPerWeek="
                + maxSeasonsPerWeek + "]";
    }
}

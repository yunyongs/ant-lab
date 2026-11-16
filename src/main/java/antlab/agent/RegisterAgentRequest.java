package antlab.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The agent is the (persona, model) pair of the configured owner; the display name is only a label. */
public record RegisterAgentRequest(
        @NotNull @Size(max = 64) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$")
        String personaKey,
        @NotNull
        ModelRoute modelRoute,
        @NotNull @Size(max = 200) @Pattern(regexp = "^\\S(.*\\S)?$")
        String modelId,
        @NotBlank @Size(max = 50)
        String displayName) {
}

package antlab.agent;

import antlab.season.VoidReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Voids an agent season. The note is the defect record PLAN §6.9 requires: what failed, in words of the runner's own.
 * It must carry no key, prompt or model reply.
 */
public record VoidSeasonRequest(@NotNull VoidReason reason, @NotBlank @Size(max = 500) String note) {
}

package antlab.agent.decision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Model calls made for a turn before its decision (a pause or a wait), to be stored as non-final attempts. */
public record CallsRequest(
        @NotNull @Size(min = 1, max = 20)
        List<@NotNull @Valid CallRecordRequest> calls) {
}

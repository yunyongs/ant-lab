package antlab.agent.memory;

import antlab.agent.ReflectionOutcome;
import antlab.agent.decision.CallRecordRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The outcome of one season's memory step. {@code ACCEPTED} carries a {@code season-reflection/1} object, the rewritten
 * {@code operating-note/1} object and the memory version the note was written on top of ({@code baseVersion}, null for
 * the first); {@code DISCARDED} carries fixed reason codes only. Both carry the model calls made, kinds REFLECTION and
 * PLAYBOOK.
 */
public record SubmitSeasonMemoryRequest(
        @Min(1)
        Integer baseVersion,
        @NotNull
        ReflectionOutcome outcome,
        JsonNode reflection,
        JsonNode playbook,
        Boolean playbookCarried,
        @Size(max = 9)
        List<@NotNull MemoryDiscardCode> discardCodes,
        @NotNull @Size(max = 20)
        List<@NotNull @Valid CallRecordRequest> calls) {

    /** An absent flag means false (a missing primitive would fail the whole request). */
    public boolean carried() {
        return Boolean.TRUE.equals(playbookCarried);
    }
}

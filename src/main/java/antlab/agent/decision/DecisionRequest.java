package antlab.agent.decision;

import antlab.agent.DecisionOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What the runner sends for one turn: its verdict on the model reply, the reply as a {@code turn-decision/1} object
 * (only with {@code DECISION}) and the model calls it made for the turn, the last one being the final call.
 */
public record DecisionRequest(
        @NotNull
        DecisionOutcome outcome,
        JsonNode decision,
        @NotNull @Size(min = 1, max = 20)
        List<@NotNull @Valid CallRecordRequest> calls) {
}

package antlab.agent.decision;

import antlab.agent.DecisionOutcome;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * What the engine did with one turn's decision, unit by unit. Unit rejects are results (HTTP 200), not API errors.
 * It names ids of rows the agent itself caused and codes of the engine; it never carries model text.
 */
public record DecisionResultDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Long seasonId,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED)
        int turnNo,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "DECISION, NO_RESPONSE or INVALID")
        DecisionOutcome outcome,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "True when the stored result of an earlier identical submit is returned")
        boolean replayed,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationResultDto formationChange,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "One entry per order of the reply, in the reply's order")
        List<OrderUnitResultDto> orders,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ThesesResultDto theses,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Pointers of the shape check when the outcome is INVALID, else empty")
        List<SchemaViolationDto> schemaViolations) {

    /** The formation unit. */
    public record FormationResultDto(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED)
            UnitStatus status,
            @ExposedFrom("agent_call.rejects")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"})
            String code,
            @ExposedFrom("orders.formation_change_id")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"})
            Long formationChangeId) {
    }

    /** One order unit; {@code index} is its place in the reply's orders array. */
    public record OrderUnitResultDto(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED)
            int index,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED)
            UnitStatus status,
            @ExposedFrom("agent_call.rejects")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"})
            String code,
            @ExposedFrom("orders.id")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"})
            Long orderId) {
    }

    /** The theses unit: always {@code NONE} or {@code REJECTED} until the outlook mode exists. */
    public record ThesesResultDto(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED)
            UnitStatus status,
            @ExposedFrom("agent_call.rejects")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"})
            String code) {
    }

    /** One place where the reply broke the shape of {@code turn-decision/1}. */
    public record SchemaViolationDto(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "JSON pointer")
            String pointer,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The schema keyword that failed")
            String keyword) {
    }
}

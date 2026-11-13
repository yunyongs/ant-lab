package antlab.season;

import antlab.common.exposure.ExposedFrom;
import antlab.history.EventCategory;
import antlab.history.EventPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** One event tag of the review. Carries no event id, event key or card numbers. */
public record ReviewEventDto(
        @ExposedFrom("month_event.category")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        EventCategory category,
        @ExposedFrom("month_event.event_type")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Event kind / 사건 유형")
        String eventType,
        @ExposedFrom("month_event.phase")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        EventPhase phase,
        @ExposedFrom("month_event.title")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Real name / 실제 이름")
        String title,
        @ExposedFrom("month_event.hindsight")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "Name given later and "
                + "hindsight assessment. null if there is none / 나중에 붙은 이름·사후 평가. 없으면 null")
        String hindsight,
        @ExposedFrom("month_event.source")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "URL or bibliography / URL 또는 서지")
        String source) {

    static ReviewEventDto from(RevealedEventRow r) {
        return new ReviewEventDto(r.category(), r.eventType(), r.phase(), r.title(), r.hindsight(), r.source());
    }
}

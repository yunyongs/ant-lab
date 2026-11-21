package antlab.league;

import antlab.common.exposure.ExposedFrom;
import antlab.member.MemberKind;
import antlab.season.SeasonStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * One seat of the league table. The multiple and the rank are shown only after the league is closed (and the multiple only
 * for a finished seat), so a finished seat never shows a seat still playing how the path went. No seed, date or path field.
 */
@Schema(description = "One seat of a league table. valuationMultiple and multipleRank "
        + "are null until the league is CLOSED / 리그 표의 한 자리. valuationMultiple 과 multipleRank 는 리그가 CLOSED 가 "
        + "되기 전에 null 이다")
public record LeagueTableEntryDto(
        @ExposedFrom("member.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Member identifier / 회원 식별자")
        Long memberId,
        @ExposedFrom("member.kind")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        MemberKind memberKind,
        @ExposedFrom("member.name")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Display name / 표시 이름")
        String displayName,
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Season identifier / 시즌 식별자")
        Long seasonId,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Effective season status; an overdue pause reads as ABANDONING / "
                + "효과 상태. 기한이 지난 정지는 ABANDONING 으로 읽힌다")
        SeasonStatus status,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Current turn (0 = start) / 현재 턴(0 = 시작)")
        Integer currentTurnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Persona and model for an agent seat. null for a human seat / 에이전트 자리의 페르소나와 모델. 사람 자리는 null")
        LeagueTableAiDto ai,
        @ExposedFrom("member.kind")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "True for a human seat whose owner also has an agent seat in "
                + "this league: the owner could watch the agents before playing. Always false for an agent seat / "
                + "소유자의 에이전트도 이 리그에 있는 사람 자리면 true: 소유자가 에이전트를 먼저 볼 수 있었다. 에이전트 자리는 항상 false")
        Boolean ownsAgentsHere,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Final valuation divided by initial capital, 8 decimal places. null until the league is CLOSED and for a seat that "
                        + "is not FINISHED / 최종 평가액 ÷ 초기 자금, 소수 8자리. 리그가 CLOSED 가 되기 전과 FINISHED 가 아닌 자리는 null")
        BigDecimal valuationMultiple,
        @ExposedFrom({"turn.total_value", "season.initial_cash", "league.status"})
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Rank by valuationMultiple (1 = highest; ties share the better rank). null until the league is CLOSED "
                        + "and for a seat without a multiple / valuationMultiple 순위(1 = 최고, 동률은 좋은 순위를 함께). 리그가 CLOSED 가 "
                        + "되기 전과 배수가 없는 자리는 null")
        Integer multipleRank) {
}

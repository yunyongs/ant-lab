package antlab.agent;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One agent of the owner with the season counts PLAN §6.9 assigns to AG-04. The counts are raw numbers: no score, no
 * rank, no minimum-season gate (AG-06).
 */
@Schema(description = "One agent of the signed-in member with its season counts / 로그인한 회원의 에이전트 하나와 시즌 집계")
public record AgentSummaryDto(
        @ExposedFrom("member.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Agent member identifier / 에이전트 회원 식별자")
        Long agentId,
        @ExposedFrom("member.name")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Display name / 표시 이름")
        String displayName,
        @ExposedFrom("agent_profile.persona_key")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Persona key / 페르소나 키")
        String personaKey,
        @ExposedFrom("agent_profile.model_route")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ModelRoute modelRoute,
        @ExposedFrom("agent_profile.model_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Model identifier / 모델 식별자")
        String modelId,
        @ExposedFrom("agent_profile.status")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        AgentStatus status,
        @ExposedFrom("persona_version.version")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Newest persona version the server has recorded for the persona key. null if none / 서버가 기록한 페르소나 키의 "
                        + "최신 버전. 없으면 null")
        Integer latestPersonaVersion,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Every season the agent started, in any status / 에이전트가 시작한 모든 시즌, 상태 무관")
        Integer startedSeasons,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons in status FINISHED / FINISHED 시즌")
        Integer countedSeasons,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons in status VOID (model gone or runner defect) / VOID 시즌(모델 소멸·러너 결함)")
        Integer voidSeasons,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons whose effective status is ABANDONING and not yet FINISHED: "
                + "stored ABANDONING plus a pause older than 7 days. They are counted against the agent and have no score yet / "
                + "효과 상태가 ABANDONING 이고 아직 FINISHED 가 아닌 시즌: 저장된 ABANDONING 에 7일 넘은 정지를 더한 수. 에이전트에게 집계되며 아직 점수가 없다")
        Integer unfinishedAbandonSeasons,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons in status IN_PROGRESS / IN_PROGRESS 시즌")
        Integer inProgressSeasons,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons paused for less than 7 days / 정지 7일이 안 된 시즌")
        Integer pausedSeasons,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons in status HALTED (stopped by a reconciliation mismatch) / "
                + "HALTED 시즌(대사 불일치로 멈춤)")
        Integer haltedSeasons,
        @ExposedFrom("agent_call.outcome")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turns whose final model call ended NO_RESPONSE or INVALID / 최종 모델 호출이 "
                + "NO_RESPONSE 또는 INVALID 로 끝난 턴 수")
        Integer failedTurnCount) {
}

package antlab.leaderboard;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "What one manager parts run did, as counts / 매니저 부품 실행 한 번의 결과(건수)")
public record ManagerPartsRunDto(
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "DONE: the run went through the queue up to the limit; "
                + "ALREADY_RUNNING: another run held the lock and nothing was done; ENGINE_UNAVAILABLE: the engine could not "
                + "answer, so the run stopped and stored nothing for that season; REFUSED: a setting (a single-asset limit, or "
                + "an engine timeout below 30 s) forbids any run / DONE: 한도까지 대기열을 처리했다, ALREADY_RUNNING: 다른 실행이 "
                + "잠금을 잡고 있어 아무것도 하지 않았다, ENGINE_UNAVAILABLE: 엔진이 답하지 못해 그 시즌에 아무것도 저장하지 않고 "
                + "멈췄다, REFUSED: 설정(단일 종목 한도, 30초보다 짧은 엔진 시간 제한)이 실행을 막는다")
        ManagerPartsRunStatus status,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Seasons stored with their parts now / "
                + "지금 부품과 함께 저장한 시즌 수")
        int ranked,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Seasons stored now with an exclusion "
                + "reason instead of parts / 부품 대신 제외 사유로 지금 저장한 시즌 수")
        int excluded,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Seasons another run stored first / 다른 "
                + "실행이 먼저 저장한 시즌 수")
        int alreadyStored,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Seasons that failed and stay pending (a "
                + "bad engine answer, a season whose score cannot be made yet) / 실패해 대기로 남은 시즌 수(엔진 답이 잘못됨, "
                + "점수를 아직 만들 수 없음)")
        int failed,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Finished seasons still without a row "
                + "after the run / 실행 뒤에도 행이 없는 끝난 시즌 수")
        int pending) {

    public static ManagerPartsRunDto of(ManagerPartsService.Run run) {
        return new ManagerPartsRunDto(run.status(), run.count(ManagerPartsService.Outcome.RANKED),
                run.count(ManagerPartsService.Outcome.EXCLUDED), run.count(ManagerPartsService.Outcome.ALREADY_STORED),
                run.count(ManagerPartsService.Outcome.FAILED), run.pending());
    }
}

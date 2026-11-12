package antlab.season;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 턴 전환 요청 — 같은 요청을 두 번 보내도 한 번만 적용되게 하는 멱등성 열쇠가 전환 전의 현재 턴이다. */
@Schema(description = "The current turn before the advance is the idempotency key / 전환 전의 현재 턴이 멱등성 열쇠다")
public record AdvanceTurnRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The current turn before the advance. 409 if "
                + "it differs from the season's current turn / 전환 전의 현재 턴. 시즌의 현재 턴과 다르면 409")
        @NotNull @Min(0) Integer expectedTurnNo) {
}

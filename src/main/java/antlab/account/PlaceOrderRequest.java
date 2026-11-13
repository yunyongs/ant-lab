package antlab.account;

import antlab.asset.Position;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 요청(시장가 매수·매도 한 건). 체결가·수수료·요율은 서버가 정한다 — 요청에서 받지 않는다.
 * 출발점(source)과 피드 번호는 받지 않고 서버가 DIRECT 로 고정한다(MVP 피드 API 가 선택 필드로 더한다).
 * 수량은 정수 주다. 소수·지수 표기는 읽는 단계에서 400 이다(Jackson 설정으로 정수 자리에 소수를 받지 않는다). Quoted numbers such as "3" are rejected with 400 as well.
 */
@Schema(description = "시장가 매수·매도 한 건. 체결가·수수료·요율은 서버가 정하고 출발점은 서버가 DIRECT 로 고정한다")
public record PlaceOrderRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "주문이 보는 턴. 시즌의 현재 턴과 다르면 409")
        @NotNull @Min(0)
        Integer expectedTurnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "BUY 또는 SELL(시장가)")
        @NotNull
        OrderSide side,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "종목 식별자(풀 응답의 assetId 와 같다)")
        @NotNull @Positive
        Long assetId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "정수 주(1 이상)")
        @NotNull @Min(1)
        Integer quantity,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED,
                description = "선언 포지션(매수 전용). 열린 구간이 없는 종목(처음 사거나 전량 매도 뒤)이면 필수, 있으면 생략할 수 있다. 매도에 보내면 400")
        Position declaredPosition) {
}

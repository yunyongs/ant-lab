package antlab.account;

import antlab.asset.Position;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 요청(시장가 매수·매도 한 건). 체결가·수수료·요율은 서버가 정한다 — 요청에서 받지 않는다.
 * 출발점(source)은 선택이며 없으면 DIRECT 다. FEED·ALERT 는 보인 피드 항목을 (턴, 표시 순서)로 가리키고, 서버가 그 항목이 이 시즌의
 * 현재 턴까지 보였는지 확인한 뒤에만 받는다(OrderService).
 * 수량은 정수 주다. 소수·지수 표기는 읽는 단계에서 400 이다(Jackson 설정으로 정수 자리에 소수를 받지 않는다). Quoted numbers such as "3" are rejected with 400 as well.
 */
@Schema(description = "One market buy or sell. The server decides the fill price, fee and rates; the origin is "
        + "DIRECT unless a source is sent\n"
        + "\n"
        + "시장가 매수·매도 한 건. 체결가·수수료·요율은 서버가 정하고, 출발점은 source 를 보내지 않으면 DIRECT 다")
public record PlaceOrderRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The turn the order applies to. 409 if it "
                + "differs from the season's current turn / 주문이 보는 턴. 시즌의 현재 턴과 다르면 409")
        @NotNull @Min(0)
        Integer expectedTurnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "BUY or SELL (market order) / BUY 또는 "
                + "SELL(시장가)")
        @NotNull
        OrderSide side,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in "
                + "the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)")
        @NotNull @Positive
        Long assetId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whole shares (1 or more) / 정수 주(1 이상)")
        @NotNull @Min(1)
        Integer quantity,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED,
                description = "Declared position (buys only). Required for an asset with no open span (first "
                        + "purchase, or after selling everything), optional otherwise. 400 if sent with a sell\n"
                        + "\n"
                        + "선언 포지션(매수 전용). 열린 구간이 없는 종목(처음 사거나 전량 매도 뒤)이면 필수, 있으면 생략할 수 있다. 매도에 보내면 400")
        Position declaredPosition,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Where the order started. Omitted or null "
                + "means DIRECT. A FEED or ALERT origin must name a feed item that was shown to this season at or "
                + "before the current turn, otherwise 422 SOURCE_NOT_VISIBLE\n"
                + "\n"
                + "주문이 시작된 곳. 없거나 null이면 DIRECT. FEED·ALERT는 이 시즌의 현재 턴까지 보인 피드 항목을 가리켜야 하며 "
                + "아니면 422 SOURCE_NOT_VISIBLE")
        @Valid
        OrderSourceRequest source) {
}

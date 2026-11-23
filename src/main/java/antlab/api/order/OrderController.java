package antlab.api.order;

import antlab.account.OrderQuoteDto;
import antlab.account.OrderResultDto;
import antlab.account.OrderService;
import antlab.account.PlaceOrderRequest;
import antlab.api.error.ApiProblem;
import antlab.common.error.DomainException;
import antlab.observability.GameMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "order")
public class OrderController {

    private static final String PROBLEM = "application/problem+json";

    private final OrderService orders;
    private final GameMetrics metrics;

    public OrderController(OrderService orders, GameMetrics metrics) {
        this.orders = orders;
        this.metrics = metrics;
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/orders", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "placeOrder", summary = "Order (market buy or sell) / 주문(시장가 매수·매도)",
            description = "Fills one order immediately at the current turn price. Turn-0 team setup and buys from turn 1 onward use the same path. A buy takes the amount and fee out of cash and "
                    + "changes holdings and lineup together. A sell adds the amount to cash, deducts the fee, "
                    + "records the realized P&L, and closes the position span when everything is sold.\n"
                    + "\n"
                    + "현재 턴 가격으로 한 건을 바로 체결한다. 0턴 팀 구성과 1턴 이후 매수가 같은 경로다. 매수는 대금과 수수료가 현금에서 빠지고 보유·라인업이 함께 바뀐다. "
                    + "매도는 대금이 현금에 더해지고 수수료가 빠지며 실현 손익이 기록되고, 전량 매도면 구간이 닫힌다.")
    @ApiResponse(responseCode = "201", description = "Fill result / 체결 결과")
    @ApiResponse(responseCode = "400", description = "The input is invalid (quantity 0 or less, declared "
            + "position on a sell, a malformed or BENCH source) — INVALID_ARGUMENT / 입력이 틀렸다(수량 0 이하, 매도에 선언 포지션, 모양이 틀렸거나 BENCH인 source) — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "An order cannot be placed now — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, "
                    + "LINEUP_MISMATCH, EVENT_TIME_CONFLICT\n"
                    + "\n"
                    + "지금은 주문할 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH, "
                    + "EVENT_TIME_CONFLICT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422",
            description = "The order content is not acceptable — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, "
                    + "INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED\n"
                    + "\n"
                    + "주문 내용을 받을 수 없다 — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, "
                    + "INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<OrderResultDto> place(@PathVariable Long seasonId,
            @Valid @RequestBody PlaceOrderRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(orders.place(seasonId, request));
        } catch (DomainException rejected) {
            metrics.orderRejected(rejected.getCode());
            throw rejected;
        }
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/orders/quote", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "quoteOrder", summary = "Order preview (quote) / 주문 미리보기(견적)",
            description = "Takes the same body as placeOrder and answers what that order would fill now: fill price, "
                    + "amount, fee, its FX part, cash after the fill and, for a buy, the largest quantity that fits. "
                    + "It runs the same checks in the same order and refuses with the same errors, but writes nothing "
                    + "and is not counted as a rejected order.\n"
                    + "\n"
                    + "placeOrder 와 같은 본문을 받아 그 주문이 지금 어떻게 체결될지 답한다: 체결가, 대금, 수수료와 그중 환전 비용, 체결 뒤 현금, "
                    + "매수면 들어갈 수 있는 최대 수량. 같은 검사를 같은 순서로 하고 같은 오류로 거절하지만 아무것도 쓰지 않으며 거절된 주문으로 "
                    + "세지 않는다.")
    @ApiResponse(responseCode = "200", description = "Quote / 견적")
    @ApiResponse(responseCode = "400", description = "The input is invalid (quantity 0 or less, declared "
            + "position on a sell, a malformed or BENCH source) — INVALID_ARGUMENT / 입력이 틀렸다(수량 0 이하, 매도에 선언 포지션, 모양이 틀렸거나 BENCH인 source) — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "An order cannot be placed now — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, "
                    + "LINEUP_MISMATCH\n"
                    + "\n"
                    + "지금은 주문할 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422",
            description = "The order content is not acceptable — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, "
                    + "INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED\n"
                    + "\n"
                    + "주문 내용을 받을 수 없다 — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, "
                    + "INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public OrderQuoteDto quote(@PathVariable Long seasonId, @Valid @RequestBody PlaceOrderRequest request) {
        return orders.quote(seasonId, request);
    }
}

package antlab.api.order;

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
            + "position on a sell) — INVALID_ARGUMENT / 입력이 틀렸다(수량 0 이하, 매도에 선언 포지션) — INVALID_ARGUMENT",
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
            description = "The order content is not acceptable — ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, "
                    + "INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED\n"
                    + "\n"
                    + "주문 내용을 받을 수 없다 — ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, "
                    + "POSITION_CONFLICT, POSITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE "
            + "/ 현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE",
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
}

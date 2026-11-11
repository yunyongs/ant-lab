package antlab.api.order;

import antlab.account.OrderResultDto;
import antlab.account.OrderService;
import antlab.account.PlaceOrderRequest;
import antlab.api.error.ApiProblem;
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

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/orders", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "placeOrder", summary = "주문(시장가 매수·매도)",
            description = "현재 턴 가격으로 한 건을 바로 체결한다. 0턴 팀 구성과 1턴 이후 매수가 같은 경로다."
                    + " 매수는 대금과 수수료가 현금에서 빠지고 보유·라인업이 함께 바뀐다."
                    + " 매도는 대금이 현금에 더해지고 수수료가 빠지며 실현 손익이 기록되고, 전량 매도면 구간이 닫힌다.")
    @ApiResponse(responseCode = "201", description = "체결 결과")
    @ApiResponse(responseCode = "400", description = "입력이 틀렸다(수량 0 이하, 매도에 선언 포지션) — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "지금은 주문할 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422",
            description = "주문 내용을 받을 수 없다 — ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<OrderResultDto> place(@PathVariable Long seasonId,
            @Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orders.place(seasonId, request));
    }
}

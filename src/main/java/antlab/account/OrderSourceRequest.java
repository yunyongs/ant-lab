package antlab.account;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Where an order started, as the client reports it. The server accepts a non-DIRECT origin only when the referenced item
 * was shown to this season at or before the current turn ({@link OrderService}); the reference is the feed item's
 * (turn, display order), never an internal row id.
 */
@Schema(description = "Where the order started. FEED and ALERT name the feed item that was shown (turn and display "
        + "order); RANKING and DIRECT carry no reference; BENCH is reserved and refused. The server accepts a "
        + "non-DIRECT origin only if that item was shown to this season at or before the current turn\n"
        + "\n"
        + "주문이 시작된 곳. FEED·ALERT는 보인 피드 항목(턴과 표시 순서)을 가리키고, RANKING·DIRECT는 참조가 없으며, "
        + "BENCH는 예약이라 거절한다. 서버는 그 항목이 이 시즌의 현재 턴까지 보인 경우에만 DIRECT 아닌 출발점을 받는다")
public record OrderSourceRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Origin of the order / 주문의 출발점")
        @NotNull
        OrderSource kind,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Turn in which the feed item was shown "
                + "(FEED and ALERT only, with feedSeq) / 피드 항목이 보인 턴(FEED·ALERT 전용, feedSeq와 함께)")
        @Min(1)
        Integer feedTurnNo,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Display order of the feed item inside that "
                + "turn (FEED and ALERT only, with feedTurnNo) / 그 턴 안에서 피드 항목의 표시 순서(FEED·ALERT 전용, "
                + "feedTurnNo와 함께)")
        @Min(1)
        Integer feedSeq) {

    /** FEED and ALERT carry a reference; every other kind carries none. */
    boolean shapeValid() {
        boolean refKind = kind == OrderSource.FEED || kind == OrderSource.ALERT;
        boolean hasRef = feedTurnNo != null || feedSeq != null;
        if (refKind) {
            return feedTurnNo != null && feedSeq != null;
        }
        return !hasRef;
    }
}

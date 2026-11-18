package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One feed item of a turn as it was shown, with what it really was. Carries no row id, event id, template, direction,
 * strength or repeat count.
 */
public record ReviewFeedItemDto(
        @ExposedFrom("turn_feed.seq")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Display order within the turn (from 1) / 턴 안의 "
                + "표시 순서(1부터)")
        Integer seq,
        @ExposedFrom("turn_feed.kind")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FeedKind kind,
        @ExposedFrom("turn_feed.kind")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "True exactly when the item was a real event card "
                + "(kind EVENT) / 실제 사건 카드(kind EVENT)일 때만 true")
        Boolean signal,
        @ExposedFrom("turn_feed.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "Asset the item named. "
                + "null if it named none / 항목이 가리킨 종목. 없으면 null")
        Long assetId,
        @ExposedFrom("turn_feed.asset_group_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "Asset group the item "
                + "named. null if it named none / 항목이 가리킨 그룹. 없으면 null")
        Long groupId,
        @ExposedFrom("turn_feed.body")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The sentence as it was shown / 보인 그대로의 문장")
        String body,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The real event card "
                + "behind the item. null unless signal is true / 항목 뒤의 실제 사건 카드. signal 이 true 일 때만 있다")
        ReviewEventDto event,
        @ExposedFrom("orders.turn_feed_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of orders of the season placed from this "
                + "item, whatever their outcome, including orders placed in a later turn / 이 항목을 보고 낸 주문 수"
                + "(체결 여부와 무관, 뒤 턴에 낸 주문 포함)")
        Integer ordersFromItem) {

    static ReviewFeedItemDto from(RevealedFeedRow r) {
        boolean signal = r.kind() == FeedKind.EVENT;
        ReviewEventDto event = null;
        if (signal) {
            if (r.title() == null) {
                throw new IllegalStateException("복기 원천이 모순이다: 사건 항목에 사건 태그가 없다");
            }
            event = new ReviewEventDto(r.category(), r.eventType(), r.phase(), r.title(), r.hindsight(), r.source());
        }
        return new ReviewFeedItemDto(r.seq(), r.kind(), signal, r.assetId(), r.groupId(), r.body(), event,
                Math.toIntExact(r.ordersFromItem()));
    }
}

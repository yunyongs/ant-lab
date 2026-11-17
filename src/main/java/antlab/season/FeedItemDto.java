package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One feed item as shown to the player. Event cards and noise share this shape: nothing here says which is which, and
 * the kind, template, group, event and strength of the stored row are never exported (PLAN §4.13, erd §9 P0-18·MVP DTO).
 * {@code turnNo} with {@code seq} is the visible reference of an item.
 */
@Schema(description = "One feed item as shown. Event cards and noise share this shape; what was a real event is "
        + "revealed in the review.\n"
        + "\n"
        + "보인 그대로의 피드 항목. 사건 카드와 소음은 같은 모양이며 무엇이 실제 사건이었는지는 복기에서 공개한다.")
public record FeedItemDto(
        @ExposedFrom("turn_feed.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turn the item was shown in / 보인 턴")
        Integer turnNo,
        @ExposedFrom("turn_feed.seq")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Display order within the turn, from 1 / 턴 안의 표시 순서, 1부터")
        Integer seq,
        @ExposedFrom("turn_feed.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Asset the sentence is about, "
                + "null when it names none / 문장이 다루는 종목, 없으면 null")
        Long assetId,
        @ExposedFrom("turn_feed.body")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The sentence as shown / 보인 문장")
        String body) {

    public static FeedItemDto from(TurnFeed row) {
        return new FeedItemDto(row.getTurnNo(), row.getSeq(), row.getAssetId(), row.getBody());
    }
}

package antlab.feed;

import antlab.history.MoveDirection;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What the generator may know about one pool asset at turn {@code asOfTurn}: the turn's own return, the previous turn's, the streak and
 * the attention figures of the current and the previous block, and what the previous turn's feed said about it. Every value is already
 * reduced to a scalar; nothing here can carry a later block, a month or a date.
 *
 * @param ret              this turn's price change, scale 8
 * @param prevRet          the previous turn's price change, null at turn 1
 * @param streak           count of turns with the same price-change sign (a zero change breaks it)
 * @param attention        attention index of the current block, null if none
 * @param attentionRank    rank among pool assets with an attention index, ties share a rank
 * @param attentionN       how many pool assets have an attention index
 * @param prevAttention    attention index of the previous turn's block, null if none
 * @param prevAttentionRank 1 + number of pool assets whose previous attention is greater; null if prevAttention is null
 * @param prevStoryDirection direction of the last RISE/FALL/ECHO row of this asset in the previous turn's feed, null if none
 * @param prevEchoRepeat   1 + the largest ECHO repeat count of this asset in the previous turn's feed (1 if none)
 * @param focusName        name of the pool asset with the highest current attention (ties: market, ticker), null if none
 */
public record AssetFacts(int asOfTurn, long assetId, String market, String ticker, String name, long groupId, String groupCode,
        String groupName, BigDecimal ret, BigDecimal prevRet, int streak, BigDecimal attention, Integer attentionRank, int attentionN,
        BigDecimal prevAttention, Integer prevAttentionRank, MoveDirection prevStoryDirection, int prevEchoRepeat, String focusName) {

    public AssetFacts {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(groupCode, "groupCode");
        Objects.requireNonNull(groupName, "groupName");
        Objects.requireNonNull(ret, "ret");
        if (ret.scale() != 8 || (prevRet != null && prevRet.scale() != 8)) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "a return must have scale 8");
        }
        if (streak < 0 || attentionN < 0 || prevEchoRepeat < 1) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "asset counts out of range");
        }
    }
}

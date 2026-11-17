package antlab.feed;

import antlab.history.MoveDirection;
import java.math.BigDecimal;

/** Hand-made inputs for the generator tests. */
final class FeedFacts {

    private FeedFacts() {
    }

    static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** A mutable builder with plain defaults: unchanged price, no attention, no earlier story. */
    static final class Asset {
        int turn = 5;
        long id;
        String market = "KR";
        String ticker;
        String name;
        long groupId = 1;
        String groupCode = "G1";
        String groupName = "기술";
        String ret = "0.00000000";
        String prevRet;
        int streak;
        String attention;
        Integer attentionRank;
        int attentionN;
        String prevAttention;
        Integer prevAttentionRank;
        MoveDirection prevStory;
        int prevEchoRepeat = 1;
        String focusName;

        Asset(long id, String ticker, String name) {
            this.id = id;
            this.ticker = ticker;
            this.name = name;
        }

        Asset turn(int value) {
            this.turn = value;
            return this;
        }

        Asset ret(String value) {
            this.ret = value;
            return this;
        }

        Asset prev(String prevRet, MoveDirection story, int echoRepeat) {
            this.prevRet = prevRet;
            this.prevStory = story;
            this.prevEchoRepeat = echoRepeat;
            return this;
        }

        Asset group(long id, String code, String name) {
            this.groupId = id;
            this.groupCode = code;
            this.groupName = name;
            return this;
        }

        Asset attention(String now, Integer rank, int ranked, String before, Integer beforeRank) {
            this.attention = now;
            this.attentionRank = rank;
            this.attentionN = ranked;
            this.prevAttention = before;
            this.prevAttentionRank = beforeRank;
            return this;
        }

        Asset focus(String name) {
            this.focusName = name;
            return this;
        }

        Asset streak(int value) {
            this.streak = value;
            return this;
        }

        AssetFacts build() {
            return new AssetFacts(turn, id, market, ticker, name, groupId, groupCode, groupName, bd(ret), bd(prevRet), streak,
                    bd(attention), attentionRank, attentionN, bd(prevAttention), prevAttentionRank, prevStory, prevEchoRepeat,
                    focusName);
        }
    }

    static Asset asset(long id, String ticker, String name) {
        return new Asset(id, ticker, name);
    }

    static EventFacts event(int turn, long id, String key, String category, String reaction) {
        return new EventFacts(turn, id, key, category, bd(reaction), 2, category == null);
    }
}

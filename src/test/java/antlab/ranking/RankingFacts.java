package antlab.ranking;

import antlab.feed.AssetFacts;
import java.math.BigDecimal;

/** Hand-made asset facts for the ranking tests. */
final class RankingFacts {

    static final int TURN = 5;

    private RankingFacts() {
    }

    static final class Asset {
        int turn = TURN;
        final long id;
        String market = "KR";
        final String ticker;
        long groupId = 1;
        String groupCode = "G1";
        String ret = "0.00000000";
        int streak = 1;
        String attention;
        Integer attentionRank;
        int attentionN;

        Asset(long id, String ticker) {
            this.id = id;
            this.ticker = ticker;
        }

        Asset turn(int value) {
            this.turn = value;
            return this;
        }

        Asset market(String value) {
            this.market = value;
            return this;
        }

        Asset group(long id, String code) {
            this.groupId = id;
            this.groupCode = code;
            return this;
        }

        Asset ret(String value, int streakValue) {
            this.ret = value;
            this.streak = streakValue;
            return this;
        }

        Asset attention(String index, Integer rank, int ranked) {
            this.attention = index;
            this.attentionRank = rank;
            this.attentionN = ranked;
            return this;
        }

        AssetFacts build() {
            return new AssetFacts(turn, id, market, ticker, "name-" + ticker, groupId, groupCode, "group-" + groupCode,
                    new BigDecimal(ret), null, streak, attention == null ? null : new BigDecimal(attention), attentionRank,
                    attentionN, null, null, null, 1, null);
        }
    }

    static Asset asset(long id, String ticker) {
        return new Asset(id, ticker);
    }
}

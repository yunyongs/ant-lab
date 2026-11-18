package antlab.ranking;

import java.math.BigDecimal;
import java.util.List;

/**
 * The seven lists of one turn, each cut at {@link RankingRules#BOARD_SIZE} rows and numbered by row position. Internal result of the
 * calculator; the response DTOs are built from it.
 */
public record RankingBoard(int turnNo, List<Asset> gainers, List<Asset> losers, List<Attention> attention,
        List<Streak> risingStreaks, List<Streak> fallingStreaks, List<Group> groupGainers, List<Group> groupLosers) {

    public record Asset(int rank, long assetId, BigDecimal changeRate) {
    }

    /** Grade 1..3 only; the attention index itself never leaves the calculator. */
    public record Attention(int rank, long assetId, int grade) {
    }

    public record Streak(int rank, long assetId, int streak, BigDecimal changeRate) {
    }

    public record Group(int rank, long groupId, BigDecimal changeRate) {
    }
}

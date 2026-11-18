package antlab.scoring;

import antlab.feed.AssetFacts;
import antlab.feed.FeedRules;
import antlab.history.MoveDirection;
import antlab.ranking.RankingBoard;
import antlab.ranking.RankingBoardCalculator;
import antlab.ranking.RankingInput;
import antlab.season.FeedKind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the player was pointed at in one turn, as asset ids that stay inside the service (MVP-19). The sets are a function of that
 * turn's stored feed rows and that turn's ranking board only: the board is computed from a {@link RankingInput}, which cannot carry a
 * later turn, and every feed row must carry the same turn number. Rows that name no single asset (group news, fading attention,
 * event cards, alerts) and the two streak lists point at nothing.
 *
 * @param up           assets named by up news of the turn, on its gainers list or on its attention list: buying them follows the noise
 * @param down         assets named by down news of the turn or on its losers list: selling them follows the noise
 * @param topAttention assets in the top attention grade of the turn (the whole pool, not only the cut board list)
 */
public record TurnSignals(int turnNo, Set<Long> up, Set<Long> down, Set<Long> topAttention) {

    /** The part of a stored feed row that counts. */
    public record FeedRow(int turnNo, FeedKind kind, Long assetId, MoveDirection direction) {
    }

    private static final int TOP_GRADE = 3;

    public TurnSignals {
        if (turnNo < 1 || up == null || down == null || topAttention == null) {
            throw new IllegalStateException("턴 신호 원천이 모순이다: 턴 번호나 집합이 없다");
        }
        up = Set.copyOf(up);
        down = Set.copyOf(down);
        topAttention = Set.copyOf(topAttention);
    }

    public static TurnSignals of(int turnNo, List<FeedRow> feedRows, RankingInput input) {
        if (input.turnNo() != turnNo) {
            throw new IllegalStateException("턴 신호 원천이 모순이다: 순위판 입력의 턴이 다르다");
        }
        Set<Long> up = new HashSet<>();
        Set<Long> down = new HashSet<>();
        for (FeedRow row : feedRows) {
            if (row.turnNo() != turnNo) {
                throw new IllegalStateException("턴 신호 원천이 모순이다: 다른 턴의 피드 행이 섞였다");
            }
            if (row.assetId() == null) {
                continue;
            }
            if ((row.kind() == FeedKind.RISE || row.kind() == FeedKind.ECHO) && row.direction() == MoveDirection.UP) {
                up.add(row.assetId());
            }
            if ((row.kind() == FeedKind.FALL || row.kind() == FeedKind.ECHO) && row.direction() == MoveDirection.DOWN) {
                down.add(row.assetId());
            }
        }
        RankingBoard board = RankingBoardCalculator.board(input);
        board.gainers().forEach(a -> up.add(a.assetId()));
        board.attention().forEach(a -> up.add(a.assetId()));
        board.losers().forEach(a -> down.add(a.assetId()));
        Set<Long> top = new HashSet<>();
        for (AssetFacts asset : input.assets()) {
            Integer grade = FeedRules.grade(asset.attentionRank(), asset.attentionN());
            if (grade != null && grade == TOP_GRADE) {
                top.add(asset.assetId());
            }
        }
        return new TurnSignals(turnNo, up, down, top);
    }
}

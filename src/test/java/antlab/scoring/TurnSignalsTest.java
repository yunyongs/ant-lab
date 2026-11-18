package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.feed.AssetFacts;
import antlab.history.MoveDirection;
import antlab.ranking.RankingInput;
import antlab.scoring.TurnSignals.FeedRow;
import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which assets a turn's feed rows and board point at: asset-level news, gainers, losers and attention lists only. */
class TurnSignalsTest {

    private static final int TURN = 4;

    private static AssetFacts fact(long id, String ret, int streak, String attention, Integer rank, int ranked) {
        return new AssetFacts(TURN, id, "KR", String.format("%06d", id), "name" + id, 1, "G1", "group", new BigDecimal(ret), null,
                streak, attention == null ? null : new BigDecimal(attention), rank, ranked, null, null, null, 1, null);
    }

    /**
     * 1 and 5..8 gain most, 9 gains least but has the longest rising streak (streak list only); 2 and 11..14 lose most, 10 loses least
     * with a falling streak and 15 is just outside the five; 3, 4 and 16 are on the attention list with grades 3, 2 and 1; only 3 is in the top grade.
     */
    private static RankingInput input() {
        List<AssetFacts> facts = new ArrayList<>();
        facts.add(fact(1, "0.10000000", 1, null, null, 0));
        facts.add(fact(2, "-0.10000000", 1, null, null, 0));
        facts.add(fact(3, "0.00000000", 1, "3.0", 1, 3));
        facts.add(fact(4, "0.00000000", 1, "2.0", 2, 3));
        facts.add(fact(16, "0.00000000", 1, "1.0", 3, 3));
        String[] gains = {"0.09000000", "0.08000000", "0.07000000", "0.06000000"};
        for (int i = 0; i < gains.length; i++) {
            facts.add(fact(5 + i, gains[i], 1, null, null, 0));
        }
        facts.add(fact(9, "0.01000000", 5, null, null, 0));
        facts.add(fact(10, "-0.01000000", 4, null, null, 0));
        String[] losses = {"-0.09000000", "-0.08000000", "-0.07000000", "-0.06000000", "-0.05000000"};
        for (int i = 0; i < losses.length; i++) {
            facts.add(fact(11 + i, losses[i], 1, null, null, 0));
        }
        return new RankingInput(TURN, facts);
    }

    private static FeedRow row(FeedKind kind, Long asset, MoveDirection direction) {
        return new FeedRow(TURN, kind, asset, direction);
    }

    @Test
    void onlyAssetNewsOfTheMatchingKindAndDirectionPointsAtAnAsset() {
        TurnSignals s = TurnSignals.of(TURN, List.of(row(FeedKind.RISE, 101L, MoveDirection.UP),
                row(FeedKind.ECHO, 102L, MoveDirection.UP), row(FeedKind.FALL, 103L, MoveDirection.DOWN),
                row(FeedKind.ECHO, 104L, MoveDirection.DOWN), row(FeedKind.FADE, 105L, MoveDirection.DOWN),
                row(FeedKind.FADE, 108L, MoveDirection.UP), row(FeedKind.RISE, null, MoveDirection.UP),
                row(FeedKind.FALL, null, MoveDirection.DOWN), row(FeedKind.SPREAD, null, MoveDirection.UP),
                row(FeedKind.EVENT, null, null), row(FeedKind.NUDGE, 106L, null), row(FeedKind.RISE, 107L, MoveDirection.DOWN),
                row(FeedKind.FALL, 109L, MoveDirection.UP)), input());
        assertThat(s.up()).contains(101L, 102L).doesNotContain(103L, 104L, 105L, 106L, 107L, 108L, 109L);
        assertThat(s.down()).contains(103L, 104L).doesNotContain(101L, 102L, 105L, 106L, 107L, 108L, 109L);
    }

    @Test
    void theBoardAddsGainersAndAttentionToUpAndLosersToDown() {
        TurnSignals s = TurnSignals.of(TURN, List.of(), input());
        assertThat(s.up()).containsExactlyInAnyOrder(1L, 5L, 6L, 7L, 8L, 3L, 4L, 16L);
        assertThat(s.down()).containsExactlyInAnyOrder(2L, 11L, 12L, 13L, 14L);
    }

    @Test
    void theStreakListsAndAssetsBelowTheBoardCutPointAtNothing() {
        TurnSignals s = TurnSignals.of(TURN, List.of(), input());
        assertThat(s.up()).as("9 is only on the rising streak list").doesNotContain(9L);
        assertThat(s.down()).as("10 is only on the falling streak list, 15 is the sixth loser").doesNotContain(10L, 15L);
    }

    @Test
    void topAttentionIsTheTopGradeOfTheWholePoolNotOfTheBoardList() {
        TurnSignals s = TurnSignals.of(TURN, List.of(), input());
        assertThat(s.topAttention()).containsExactly(3L);
    }

    @Test
    void aFeedRowOfAnotherTurnIsRefused() {
        FeedRow other = new FeedRow(TURN + 1, FeedKind.RISE, 101L, MoveDirection.UP);
        assertThatThrownBy(() -> TurnSignals.of(TURN, List.of(other), input())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다른 턴의 피드 행");
    }

    @Test
    void aBoardInputOfAnotherTurnIsRefused() {
        assertThatThrownBy(() -> TurnSignals.of(TURN + 1, List.of(), input())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순위판 입력의 턴이 다르다");
    }

    @Test
    void theSetsAreCopiedAndTheTurnIsPositive() {
        assertThatThrownBy(() -> new TurnSignals(0, Set.of(), Set.of(), Set.of())).isInstanceOf(IllegalStateException.class);
        assertThat(TurnSignals.of(TURN, List.of(), input()).up()).isUnmodifiable();
    }
}

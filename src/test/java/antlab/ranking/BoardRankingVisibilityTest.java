package antlab.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which ids of a board open the RANKING route: every asset list does, the group lists do not. */
class BoardRankingVisibilityTest {

    private static final BigDecimal UP = new BigDecimal("0.01000000");

    private static RankingBoard board(List<RankingBoard.Asset> gainers, List<RankingBoard.Asset> losers,
            List<RankingBoard.Attention> attention, List<RankingBoard.Streak> rising, List<RankingBoard.Streak> falling) {
        return new RankingBoard(1, gainers, losers, attention, rising, falling, List.of(new RankingBoard.Group(1, 99L, UP)),
                List.of(new RankingBoard.Group(1, 98L, UP.negate())));
    }

    @Test
    void every_asset_list_opens_the_route_and_the_group_lists_do_not() {
        RankingBoard b = board(List.of(new RankingBoard.Asset(1, 11L, UP)), List.of(new RankingBoard.Asset(1, 12L, UP.negate())),
                List.of(new RankingBoard.Attention(1, 13L, 3)), List.of(new RankingBoard.Streak(1, 14L, 2, UP)),
                List.of(new RankingBoard.Streak(1, 15L, 2, UP.negate())));
        assertThat(BoardRankingVisibility.assetIds(b)).containsExactlyInAnyOrder(11L, 12L, 13L, 14L, 15L);
    }

    @Test
    void each_list_alone_is_enough() {
        assertThat(BoardRankingVisibility.assetIds(board(List.of(new RankingBoard.Asset(1, 1L, UP)), List.of(), List.of(), List.of(),
                List.of()))).containsExactly(1L);
        assertThat(BoardRankingVisibility.assetIds(board(List.of(), List.of(new RankingBoard.Asset(1, 2L, UP)), List.of(), List.of(),
                List.of()))).containsExactly(2L);
        assertThat(BoardRankingVisibility.assetIds(board(List.of(), List.of(), List.of(new RankingBoard.Attention(1, 3L, 1)),
                List.of(), List.of()))).containsExactly(3L);
        assertThat(BoardRankingVisibility.assetIds(board(List.of(), List.of(), List.of(), List.of(new RankingBoard.Streak(1, 4L, 2, UP)),
                List.of()))).containsExactly(4L);
        assertThat(BoardRankingVisibility.assetIds(board(List.of(), List.of(), List.of(), List.of(),
                List.of(new RankingBoard.Streak(1, 5L, 2, UP.negate()))))).containsExactly(5L);
        assertThat(BoardRankingVisibility.assetIds(board(List.of(), List.of(), List.of(), List.of(), List.of()))).isEmpty();
    }
}

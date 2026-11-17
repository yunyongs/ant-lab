package antlab.ranking;

import static antlab.ranking.RankingFacts.TURN;
import static antlab.ranking.RankingFacts.asset;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.feed.AssetFacts;
import antlab.feed.FeedGenerator;
import antlab.feed.FeedInput;
import antlab.feed.FeedRules;
import antlab.feed.FeedTemplateCatalog;
import antlab.feed.GeneratedFeedRow;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RankingBoardCalculatorTest {

    private static RankingBoard board(List<AssetFacts> assets) {
        return RankingBoardCalculator.board(new RankingInput(TURN, assets));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** Eight assets in three groups; six of them carry an attention figure (rank 3 is shared). */
    private static List<AssetFacts> fixture() {
        return List.of(
                asset(1, "A1").group(1, "G1").ret("0.10000000", 3).attention("9.00000000", 1, 6).build(),
                asset(2, "A2").group(1, "G1").ret("0.05000000", 2).attention("7.00000000", 2, 6).build(),
                asset(3, "A3").group(2, "G2").ret("0.05000000", 1).attention("5.00000000", 3, 6).build(),
                asset(4, "A4").group(2, "G2").ret("-0.02000000", 2).attention("5.00000000", 3, 6).build(),
                asset(5, "A5").group(2, "G2").ret("-0.08000000", 4).attention("3.00000000", 5, 6).build(),
                asset(6, "A6").group(3, "G3").ret("0.00000000", 1).attention("1.00000000", 6, 6).build(),
                asset(7, "A7").group(3, "G3").ret("0.01000000", 1).build(),
                asset(8, "A8").group(3, "G3").ret("-0.30000000", 1).build());
    }

    @Test
    void the_seven_lists_on_a_hand_fixture() {
        RankingBoard b = board(fixture());
        assertThat(b.turnNo()).isEqualTo(TURN);
        assertThat(b.gainers()).containsExactly(new RankingBoard.Asset(1, 1, bd("0.10000000")),
                new RankingBoard.Asset(2, 2, bd("0.05000000")), new RankingBoard.Asset(3, 3, bd("0.05000000")),
                new RankingBoard.Asset(4, 7, bd("0.01000000")));
        assertThat(b.losers()).containsExactly(new RankingBoard.Asset(1, 8, bd("-0.30000000")),
                new RankingBoard.Asset(2, 5, bd("-0.08000000")), new RankingBoard.Asset(3, 4, bd("-0.02000000")));
        assertThat(b.risingStreaks()).containsExactly(new RankingBoard.Streak(1, 1, 3, bd("0.10000000")),
                new RankingBoard.Streak(2, 2, 2, bd("0.05000000")));
        assertThat(b.fallingStreaks()).containsExactly(new RankingBoard.Streak(1, 5, 4, bd("-0.08000000")),
                new RankingBoard.Streak(2, 4, 2, bd("-0.02000000")));
        assertThat(b.groupGainers()).containsExactly(new RankingBoard.Group(1, 1, bd("0.07500000")));
        assertThat(b.groupLosers()).containsExactly(new RankingBoard.Group(1, 3, bd("-0.09666667")),
                new RankingBoard.Group(2, 2, bd("-0.01666667")));
    }

    @Test
    void a_zero_change_is_on_no_list_and_a_list_may_be_empty() {
        RankingBoard b = board(List.of(asset(1, "A1").ret("0.00000000", 1).build(), asset(2, "A2").ret("0.00000000", 1).build()));
        assertThat(b.gainers()).isEmpty();
        assertThat(b.losers()).isEmpty();
        assertThat(b.risingStreaks()).isEmpty();
        assertThat(b.fallingStreaks()).isEmpty();
        assertThat(b.groupGainers()).isEmpty();
        assertThat(b.groupLosers()).isEmpty();
        assertThat(b.attention()).isEmpty();
        RankingBoard none = board(List.of());
        assertThat(none.gainers()).isEmpty();
    }

    @Test
    void every_list_is_cut_at_five_rows_and_ranks_are_row_positions() {
        List<AssetFacts> assets = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            assets.add(asset(i, "T" + i).group(i, "G" + i).ret("0.0" + i + "000000", i).attention(i + ".00000000", 9 - i, 8).build());
            assets.add(asset(100 + i, "U" + i).group(100 + i, "H" + i).ret("-0.0" + i + "000000", i).build());
        }
        RankingBoard b = board(assets);
        assertThat(RankingRules.BOARD_SIZE).isEqualTo(5);
        assertThat(b.gainers()).extracting(RankingBoard.Asset::assetId).containsExactly(8L, 7L, 6L, 5L, 4L);
        assertThat(b.gainers()).extracting(RankingBoard.Asset::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(b.losers()).extracting(RankingBoard.Asset::assetId).containsExactly(108L, 107L, 106L, 105L, 104L);
        assertThat(b.attention()).hasSize(5);
        assertThat(b.risingStreaks()).extracting(RankingBoard.Streak::assetId).containsExactly(8L, 7L, 6L, 5L, 4L);
        assertThat(b.fallingStreaks()).hasSize(5);
        assertThat(b.groupGainers()).hasSize(5);
        assertThat(b.groupLosers()).hasSize(5);
    }

    @Test
    void equal_changes_are_ordered_by_market_then_ticker_bytes() {
        List<AssetFacts> assets = List.of(asset(1, "A").market("US").ret("0.02000000", 1).build(),
                asset(2, "a").ret("0.02000000", 1).build(), asset(3, "Z").ret("0.02000000", 1).build(),
                asset(4, "A").ret("0.02000000", 1).build());
        RankingBoard b = board(assets);
        assertThat(b.gainers()).extracting(RankingBoard.Asset::assetId).containsExactly(4L, 3L, 2L, 1L);
        RankingBoard losers = board(List.of(asset(1, "A").market("US").ret("-0.02000000", 1).build(),
                asset(2, "A").ret("-0.02000000", 1).build()));
        assertThat(losers.losers()).extracting(RankingBoard.Asset::assetId).containsExactly(2L, 1L);
        assertThat(board(List.of(asset(9, "B").ret("0.02000000", 2).build(), asset(8, "A").ret("0.02000000", 2).build()))
                .risingStreaks()).extracting(RankingBoard.Streak::assetId).containsExactly(8L, 9L);
    }

    @Test
    void a_longer_streak_wins_and_a_bigger_move_breaks_a_streak_tie() {
        RankingBoard b = board(List.of(asset(1, "A1").ret("0.01000000", 2).build(), asset(2, "A2").ret("0.09000000", 2).build(),
                asset(3, "A3").ret("0.02000000", 3).build()));
        assertThat(b.risingStreaks()).extracting(RankingBoard.Streak::assetId).containsExactly(3L, 2L, 1L);
        RankingBoard falling = board(List.of(asset(1, "A1").ret("-0.01000000", 2).build(), asset(2, "A2").ret("-0.09000000", 2).build(),
                asset(3, "A3").ret("-0.02000000", 3).build()));
        assertThat(falling.fallingStreaks()).extracting(RankingBoard.Streak::assetId).containsExactly(3L, 2L, 1L);
    }

    @Test
    void the_attention_list_skips_assets_without_an_index_and_shows_the_feed_grade_only() {
        RankingBoard b = board(fixture());
        assertThat(b.attention()).extracting(RankingBoard.Attention::assetId).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(b.attention()).extracting(RankingBoard.Attention::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(b.attention()).extracting(RankingBoard.Attention::grade)
                .containsExactly(FeedRules.grade(1, 6), FeedRules.grade(2, 6), FeedRules.grade(3, 6), FeedRules.grade(3, 6),
                        FeedRules.grade(5, 6))
                .containsExactly(3, 2, 2, 2, 1);
        for (Class<?> record : List.of(RankingBoard.Attention.class, AttentionRankDto.class)) {
            for (RecordComponent c : record.getRecordComponents()) {
                assertThat(c.getType()).as(record.getSimpleName() + "." + c.getName()).isNotEqualTo(BigDecimal.class);
            }
        }
        RankingBoard withoutIndex = board(List.of(asset(1, "A1").ret("0.01000000", 1).build()));
        assertThat(withoutIndex.attention()).isEmpty();
    }

    @Test
    void a_streak_needs_two_turns_and_turn_one_has_none() {
        assertThat(RankingRules.MIN_STREAK).isEqualTo(2);
        RankingBoard b = board(List.of(asset(1, "A1").ret("0.05000000", 1).build(), asset(2, "A2").ret("0.05000000", 2).build(),
                asset(3, "A3").ret("-0.05000000", 1).build(), asset(4, "A4").ret("-0.05000000", 2).build()));
        assertThat(b.risingStreaks()).extracting(RankingBoard.Streak::assetId).containsExactly(2L);
        assertThat(b.fallingStreaks()).extracting(RankingBoard.Streak::assetId).containsExactly(4L);
        RankingBoard first = RankingBoardCalculator.board(new RankingInput(1, List.of(asset(1, "A1").turn(1).ret("0.05000000", 1).build(),
                asset(2, "A2").turn(1).ret("-0.05000000", 1).build())));
        assertThat(first.risingStreaks()).isEmpty();
        assertThat(first.fallingStreaks()).isEmpty();
        assertThat(first.gainers()).hasSize(1);
    }

    @Test
    void the_group_change_is_the_mean_rounded_once_and_ties_follow_the_group_code() {
        RankingBoard b = board(List.of(asset(1, "A1").group(7, "GB").ret("0.03000000", 1).build(),
                asset(2, "A2").group(7, "GB").ret("0.04000000", 1).build(), asset(3, "A3").group(9, "GA").ret("0.03500000", 1).build()));
        assertThat(b.groupGainers()).extracting(RankingBoard.Group::groupId).containsExactly(9L, 7L);
        assertThat(b.groupGainers()).extracting(RankingBoard.Group::changeRate).containsOnly(bd("0.03500000"));
        RankingBoard mean = board(List.of(asset(1, "A1").ret("0.00000001", 1).build(), asset(2, "A2").ret("0.00000000", 1).build()));
        assertThat(mean.groupGainers()).extracting(RankingBoard.Group::changeRate).containsExactly(bd("0.00000001"));
    }

    @Test
    void the_group_change_equals_the_figure_the_feed_generator_states() {
        List<AssetFacts> assets = List.of(asset(1, "A1").group(1, "G1").ret("0.10000000", 1).build(),
                asset(2, "A2").group(1, "G1").ret("0.05000000", 1).build(), asset(3, "A3").group(2, "G2").ret("-0.07000000", 1).build(),
                asset(4, "A4").group(2, "G2").ret("-0.01000000", 1).build(), asset(5, "A5").group(2, "G2").ret("-0.00000001", 1).build(),
                asset(6, "A6").group(3, "G3").ret("0.06000000", 1).build(), asset(7, "A7").group(3, "G3").ret("0.03333333", 1).build(),
                asset(8, "A8").group(3, "G3").ret("0.01000001", 1).build());
        RankingBoard b = board(assets);
        List<RankingBoard.Group> groups = new ArrayList<>(b.groupGainers());
        groups.addAll(b.groupLosers());
        assertThat(groups).hasSize(3);
        FeedGenerator generator = new FeedGenerator(new FeedTemplateCatalog());
        Pattern groupRet = Pattern.compile("\"group_ret\": (-?[0-9.]+)");
        int compared = 0;
        for (long seed = 1; seed <= 60; seed++) {
            for (GeneratedFeedRow row : generator.generate(new FeedInput(seed, TURN, assets, List.of()))) {
                if (row.assetGroupId() == null) {
                    continue;
                }
                Matcher m = groupRet.matcher(row.templateVarsJson());
                assertThat(m.find()).as(row.templateVarsJson()).isTrue();
                RankingBoard.Group mine = groups.stream().filter(g -> g.groupId() == row.assetGroupId()).findFirst().orElseThrow();
                assertThat(mine.changeRate()).isEqualByComparingTo(m.group(1));
                assertThat(mine.changeRate().scale()).isEqualTo(8);
                compared++;
            }
        }
        assertThat(compared).as("group rows compared with the feed").isPositive();
    }

    @Test
    void the_same_input_gives_the_same_board() {
        assertThat(board(fixture())).isEqualTo(board(fixture()));
    }
}

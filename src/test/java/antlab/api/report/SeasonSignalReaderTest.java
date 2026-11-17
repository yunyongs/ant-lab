package antlab.api.report;

import static antlab.api.turn.RankingSupport.playedSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.SeasonSignalReader;
import antlab.scoring.TurnSignals;
import antlab.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The signals of turn k are a function of that turn's feed rows and board only: changing every later turn changes nothing of k. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonSignalReaderTest {

    private static final int TURNS = 12;
    private static final int KEEP = 5;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonSignalReader reader;

    @Test
    void 이후_턴의_가격과_관심과_피드를_모두_바꿔도_앞_턴의_신호는_그대로다() throws Exception {
        long season = playedSeason(mvc, jdbc, TURNS);
        List<TurnSignals> before = reader.read(season, TURNS);
        assertThat(before).extracting(TurnSignals::turnNo).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(before.stream().mapToInt(s -> s.up().size() + s.down().size()).sum()).as("not vacuous").isPositive();

        jdbc.update("UPDATE turn_price SET price = price * 3 + turn_no WHERE season_id = ? AND turn_no > ?", season, KEEP);
        jdbc.update("""
                UPDATE asset_month_attention SET attention_index = round(0.95 - attention_index, 8)
                WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no > ?)""", season, KEEP);
        jdbc.update("""
                UPDATE turn_feed SET kind = CASE kind WHEN 'RISE' THEN 'FALL' WHEN 'FALL' THEN 'RISE' ELSE kind END,
                       direction = CASE direction WHEN 'UP' THEN 'DOWN' WHEN 'DOWN' THEN 'UP' END
                WHERE season_id = ? AND turn_no > ?""", season, KEEP);
        List<TurnSignals> after = reader.read(season, TURNS);

        assertThat(after.subList(0, KEEP)).as("turns up to " + KEEP + " ignore later turns").isEqualTo(before.subList(0, KEEP));
        assertThat(after.subList(KEEP, after.size())).as("the mutation does change later turns")
                .isNotEqualTo(before.subList(KEEP, before.size()));
    }

    @Test
    void 피드_행이_하나도_없으면_순위판만으로_신호가_만들어진다() throws Exception {
        long season = playedSeason(mvc, jdbc, TURNS);
        List<TurnSignals> withFeed = reader.read(season, TURNS);
        jdbc.update("DELETE FROM turn_feed WHERE season_id = ?", season);
        List<TurnSignals> boardOnly = reader.read(season, TURNS);
        for (int i = 0; i < withFeed.size(); i++) {
            assertThat(boardOnly.get(i).up()).isSubsetOf(withFeed.get(i).up());
            assertThat(boardOnly.get(i).down()).isSubsetOf(withFeed.get(i).down());
            assertThat(boardOnly.get(i).topAttention()).isEqualTo(withFeed.get(i).topAttention());
        }
        assertThat(boardOnly.stream().mapToInt(s -> s.up().size()).sum()).isPositive();
    }

    @Test
    void 한_턴짜리_보고에는_판정할_턴이_없다() {
        assertThat(reader.read(1L, 1)).isEmpty();
    }
}

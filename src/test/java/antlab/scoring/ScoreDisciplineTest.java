package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.assetId;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The discipline item of a stored score comes from the same trades as the season analysis. Orders are written straight
 * into the tables (a sell and a buy of every asset at every turn), the shares come from the independent SQL oracle of the
 * discipline tests, and the stored item must be 100 x (1 - mean of the two shares), rounded once.
 */
class ScoreDisciplineTest extends ScoreApiTestBase {

    @Autowired
    JdbcClient client;
    @Autowired
    DisciplineSource source;

    private long price(long season, long asset, int turn) {
        return jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = ?",
                Long.class, season, asset, turn);
    }

    private void fill(long season, long asset, int turn, String side, int quantity) {
        Long order = jdbc.queryForObject("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source) "
                + "VALUES (?, ?, ?, ?, ?, 'FILLED', 'DIRECT') RETURNING id", Long.class, season, asset, turn, side, quantity);
        jdbc.update("INSERT INTO execution (order_id, fill_price, quantity) VALUES (?, ?, ?)", order,
                price(season, asset, turn), quantity);
    }

    private static BigDecimal ratio(Object part, Object whole) {
        BigDecimal denominator = (BigDecimal) whole;
        return denominator.signum() == 0 ? null
                : ((BigDecimal) part).divide(denominator, 8, RoundingMode.HALF_UP);
    }

    @Test
    void 규율_항목은_분석과_같은_매매에서_나오고_독립_SQL_산식과_같다() throws Exception {
        long season = finishedSeason();
        List<Long> assets = List.of(assetId(jdbc, "000660"), assetId(jdbc, "069500"), assetId(jdbc, "SPY"),
                assetId(jdbc, "BRK.B"));
        for (int turn = 1; turn < TURNS; turn++) {
            for (int i = 0; i < assets.size(); i++) {
                int quantity = 1 + (i + turn) % 4;
                fill(season, assets.get(i), turn, "SELL", quantity);
                fill(season, assets.get(i), turn, "BUY", quantity);
            }
        }
        String sql = new ClassPathResource("scoring/discipline_oracle.sql").getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> oracle = client.sql(sql).param("seasonId", season).query().singleRow();
        BigDecimal panic = ratio(oracle.get("panic_sell_notional"), oracle.get("sell_notional"));
        BigDecimal chase = ratio(oracle.get("chase_buy_notional"), oracle.get("buy_notional"));
        assertThat(panic).as("sells exist").isNotNull();
        assertThat(chase).as("buys exist").isNotNull();

        Optional<DisciplineRatios> found = source.of(season);

        assertThat(found).isPresent();
        assertThat(found.get().panicSellRatio()).isEqualByComparingTo(panic);
        assertThat(found.get().chaseBuyRatio()).isEqualByComparingTo(chase);
        scoring.ensureMember(memberId());
        BigDecimal mean = panic.add(chase).divide(BigDecimal.TWO, 30, RoundingMode.HALF_UP);
        BigDecimal expected = BigDecimal.valueOf(100).multiply(BigDecimal.ONE.subtract(mean)).setScale(2, RoundingMode.HALF_UP);
        assertThat((BigDecimal) scoreRow(season).get("discipline_score")).isEqualByComparingTo(expected);
        assertThat(((Number) scoreRow(season).get("process_items")).intValue()).as("one more item than a no-trade season")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void 매매가_없으면_규율_항목은_비고_점수는_나머지_항목으로_매긴다() throws Exception {
        long season = finishedSeason();

        assertThat(source.of(season)).isEmpty();
        scoring.ensureMember(memberId());

        assertThat(scoreRow(season).get("discipline_score")).isNull();
        assertThat(score(season)).isNotNull();
    }
}

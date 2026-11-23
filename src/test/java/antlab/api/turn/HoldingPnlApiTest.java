package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.money.MoneyMath;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** Display-only unrealised P&L: computed from the stored price and average cost, rounded once, never in the ledger. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class HoldingPnlApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void each_holding_pnl_and_return_equal_an_independent_exact_calculation() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 4; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        JsonNode body = json(getHoldings(mvc, season));
        assertThat(body.get("holdings")).hasSize(4);
        BigDecimal exactCost = BigDecimal.ZERO;
        BigDecimal valueSum = BigDecimal.ZERO;
        for (JsonNode h : body.get("holdings")) {
            BigDecimal value = new BigDecimal(h.get("value").asString());
            BigDecimal cost = new BigDecimal(h.get("avgPrice").asString()).multiply(BigDecimal.valueOf(h.get("quantity").asInt()));
            assertThat(h.get("unrealizedPnl").asString())
                    .isEqualTo(value.subtract(cost).setScale(0, RoundingMode.HALF_UP).toPlainString());
            assertThat(h.get("unrealizedReturn").asString())
                    .isEqualTo(value.subtract(cost).divide(cost, 8, RoundingMode.HALF_UP).toPlainString());
            assertThat(h.get("unrealizedReturn").asString()).matches("-?[0-9]+\\.[0-9]{8}");
            exactCost = exactCost.add(cost);
            valueSum = valueSum.add(value);
        }
        assertThat(new BigDecimal(body.get("holdingsValue").asString())).isEqualByComparingTo(valueSum);
        assertThat(body.get("unrealizedPnl").asString())
                .isEqualTo(valueSum.subtract(exactCost).setScale(0, RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    void a_loss_is_negative_and_a_gain_is_positive() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long block = jdbc.queryForObject("SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1",
                Long.class, season);
        jdbc.update("UPDATE asset_month_return SET local_return = -0.50000000 WHERE month_block_id = ?", block);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        JsonNode body = json(getHoldings(mvc, season));
        assertThat(new BigDecimal(body.get("unrealizedPnl").asString())).isNegative();
        body.get("holdings").forEach(h -> assertThat(new BigDecimal(h.get("unrealizedReturn").asString())).isNegative());
    }

    @Test
    void the_values_are_unchanged_by_the_new_fields() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        JsonNode body = json(getHoldings(mvc, season));
        BigDecimal valueSum = BigDecimal.ZERO;
        for (JsonNode h : body.get("holdings")) {
            BigDecimal value = new BigDecimal(h.get("value").asString());
            assertThat(value).isEqualByComparingTo(
                    new BigDecimal(h.get("price").asString()).multiply(BigDecimal.valueOf(h.get("quantity").asInt())));
            valueSum = valueSum.add(value);
        }
        assertThat(new BigDecimal(body.get("holdingsValue").asString())).isEqualByComparingTo(valueSum);
        BigDecimal cash = new BigDecimal(body.get("cashBalance").asString());
        assertThat(new BigDecimal(body.get("totalValue").asString())).isEqualByComparingTo(cash.add(valueSum));
        BigDecimal stored = jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = 1",
                BigDecimal.class, season);
        assertThat(new BigDecimal(body.get("totalValue").asString())).isEqualByComparingTo(stored);
    }

    @Test
    void rounding_is_half_up_away_from_zero_once_and_money_is_exact() {
        assertThat(MoneyMath.unrealizedPnl(new BigDecimal("1000"), new BigDecimal("999.5000"))).isEqualByComparingTo("1");
        assertThat(MoneyMath.unrealizedPnl(new BigDecimal("1000"), new BigDecimal("1000.5000"))).isEqualByComparingTo("-1");
        assertThat(MoneyMath.unrealizedPnl(new BigDecimal("1000"), new BigDecimal("999.4999"))).isEqualByComparingTo("1");
        assertThat(MoneyMath.unrealizedReturn(new BigDecimal("1100"), new BigDecimal("1000.0000")).toPlainString())
                .isEqualTo("0.10000000");
        assertThat(MoneyMath.unrealizedReturn(new BigDecimal("1"), new BigDecimal("3.0000")).toPlainString())
                .isEqualTo("-0.66666667");
    }
}

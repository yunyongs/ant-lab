package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.assetId;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.scoring.BehaviourOrder;
import antlab.scoring.BehaviourQuery;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** One row per filled order, fills and fees summed, the price at the turn and six turns later, the window cut at the last turn. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class BehaviourQueryTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BehaviourQuery query;

    private BigDecimal price(long season, long asset, int turn) {
        return jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = ?",
                BigDecimal.class, season, asset, turn);
    }

    @Test
    void 체결과_수수료는_주문마다_합산되고_여섯_턴_뒤_가격은_끝을_넘으면_비어_있다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        long brk = assetId(jdbc, "BRK.B");
        long early = BehaviourFixtures.fill(jdbc, season, brk, 3, "BUY", "RANKING", 2, 1000, 7);
        jdbc.update("INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (?, 2000, 3, 5)", early);
        long edge = BehaviourFixtures.fill(jdbc, season, brk, 6, "SELL", "FEED", 1, 1000, 0);
        long late = BehaviourFixtures.fill(jdbc, season, brk, 7, "BUY", "DIRECT", 1, 1000, 1);

        List<BehaviourOrder> orders = query.orders(season, 12);
        assertThat(orders).extracting(BehaviourOrder::turnNo).isSorted();
        assertThat(orders).filteredOn(o -> o.turnNo() == 0).hasSize(4).allSatisfy(o -> assertThat(o.source()).isEqualTo(OrderSource.DIRECT));
        BehaviourOrder first = orders.stream().filter(o -> o.orderId() == early).findFirst().orElseThrow();
        assertThat(first.notional()).as("2 x 1,000 + 3 x 2,000").isEqualByComparingTo("8000");
        assertThat(first.fee()).isEqualByComparingTo("12");
        assertThat(first.side()).isEqualTo(OrderSide.BUY);
        assertThat(first.source()).isEqualTo(OrderSource.RANKING);
        assertThat(first.price()).isEqualByComparingTo(price(season, brk, 3));
        assertThat(first.priceAfter()).isEqualByComparingTo(price(season, brk, 9));
        BehaviourOrder boundary = orders.stream().filter(o -> o.orderId() == edge).findFirst().orElseThrow();
        assertThat(boundary.source()).isEqualTo(OrderSource.FEED);
        assertThat(boundary.priceAfter()).as("6 + 6 = 12 = T is complete").isEqualByComparingTo(price(season, brk, 12));
        BehaviourOrder tooLate = orders.stream().filter(o -> o.orderId() == late).findFirst().orElseThrow();
        assertThat(tooLate.priceAfter()).as("7 + 6 > T").isNull();
        assertThat(tooLate.price()).isEqualByComparingTo(price(season, brk, 7));
    }

    @Test
    void 마지막_턴과_그_뒤의_주문은_읽지_않는다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        long brk = assetId(jdbc, "BRK.B");
        BehaviourFixtures.fill(jdbc, season, brk, 12, "BUY", "DIRECT", 1, 1000, 1);
        assertThat(query.orders(season, 12)).allSatisfy(o -> assertThat(o.turnNo()).isLessThan(12));
        assertThat(query.orders(season, 12)).hasSize(4);
    }

    @Test
    void 체결이_없는_주문은_행이_없다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source) "
                + "VALUES (?, ?, 4, 'BUY', 1, 'REJECTED', 'DIRECT')", season, assetId(jdbc, "BRK.B"));
        assertThat(query.orders(season, 12)).filteredOn(o -> o.turnNo() == 4).isEmpty();
    }
}

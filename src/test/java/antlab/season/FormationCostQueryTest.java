package antlab.season;

import static antlab.api.formation.FormationApiSupportAccess.A;
import static antlab.api.formation.FormationApiSupportAccess.B;
import static antlab.api.formation.FormationApiSupportAccess.change;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변경 비용 내부 조회 — 변경별 연결된 체결의 대금·수수료·세금 합. 주문 없는 변경도 한 줄(주문 0건)이고, 다중 체결 주문이 주문 수와
 * 비용을 중복해 세지 않으며(주문별로 먼저 합친다), SQL {@code formation_costs} 와 같은 결과다. 값은 이 시험이 직접 더한 기대값과 비교한다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationCostQueryTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FormationCostQuery query;
    @Autowired
    PlatformTransactionManager txManager;

    private long seasonAtTurnOne() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        return season;
    }

    private void sell(long season, String ticker, int qty) {
        String b = "{\"expectedTurnNo\": 1, \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, ticker) + ", \"quantity\": " + qty + "}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(MediaType.APPLICATION_JSON).content(b).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
    }

    @Test
    void 변경이_없으면_빈_목록이다() throws Exception {
        long season = seasonAtTurnOne();
        buy(mvc, jdbc, season, 1, "069500", 1);
        assertThat(query.bySeason(season)).isEmpty();
    }

    @Test
    void 주문_없는_변경도_한_줄이고_변경_전_주문은_어느_변경에도_더해지지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        buy(mvc, jdbc, season, 1, "069500", 1); // 변경 전 — 연결 없음
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        assertThat(change(mvc, season, 1, B).getStatus()).isEqualTo(201); // 이 변경은 주문이 없다

        List<FormationCost> costs = query.bySeason(season);

        assertThat(costs).hasSize(2);
        assertThat(costs).allSatisfy(c -> {
            assertThat(c.orderCount()).isZero();
            assertThat(c.buyNotional()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(c.sellNotional()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(c.feeSum()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(c.taxSum()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        assertThat(costs.get(0).formationChangeId()).isLessThan(costs.get(1).formationChangeId());
    }

    @Test
    void 변경별로_매수_매도_대금과_수수료를_주문별로_먼저_합치고_다중_체결에서도_주문을_중복해_세지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 1, "069500", 3);
        sell(season, "069500", 1);
        assertThat(change(mvc, season, 1, B).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 1, "000660", 2);
        long a = jdbc.queryForObject("SELECT min(id) FROM formation_change WHERE season_id = ?", Long.class, season);
        long b = jdbc.queryForObject("SELECT max(id) FROM formation_change WHERE season_id = ?", Long.class, season);
        // 변경 A 의 BUY 주문에 두 번째 체결을 붙인다(한 주문의 다중 체결 — 조인 증폭 검출용)
        long buyOrder = jdbc.queryForObject("SELECT id FROM orders WHERE formation_change_id = ? AND side = 'BUY'", Long.class, a);
        long extraFill = jdbc.queryForObject(
                "INSERT INTO execution (order_id, fill_price, quantity, fee, tax) VALUES (?, 1000, 2, 7, 0) RETURNING id", Long.class, buyOrder);

        List<FormationCost> costs = query.bySeason(season);

        assertThat(costs).extracting(FormationCost::formationChangeId).containsExactly(a, b);
        FormationCost ca = costs.get(0);
        FormationCost cb = costs.get(1);
        assertThat(ca.orderCount()).as("A: BUY 1건(체결 2개)·SELL 1건 — 체결 수가 아니라 주문 수").isEqualTo(2L);
        assertThat(cb.orderCount()).isEqualTo(1L);
        // 기대값은 체결 행을 직접 더한 값(이 시험이 따로 계산)
        assertThat(ca.buyNotional()).isEqualByComparingTo(sum("e.fill_price * e.quantity", a, "BUY"));
        assertThat(ca.sellNotional()).isEqualByComparingTo(sum("e.fill_price * e.quantity", a, "SELL"));
        assertThat(ca.feeSum()).isEqualByComparingTo(sum("e.fee", a, null));
        assertThat(ca.taxSum()).isEqualByComparingTo(sum("e.tax", a, null));
        assertThat(cb.buyNotional()).isEqualByComparingTo(sum("e.fill_price * e.quantity", b, "BUY"));
        assertThat(cb.sellNotional()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ca.buyNotional().signum()).isPositive();
        assertThat(ca.sellNotional().signum()).isPositive();
        // 두 번째 체결(대금 2,000·수수료 7)이 합에 한 번씩만 들어갔다
        BigDecimal firstFillOnly = jdbc.queryForObject("""
                SELECT sum(e.fill_price * e.quantity) FROM execution e WHERE e.order_id = ? AND e.id <> ?""", BigDecimal.class, buyOrder, extraFill);
        assertThat(ca.buyNotional()).isEqualByComparingTo(firstFillOnly.add(new BigDecimal("2000")));
    }

    @Test
    void SQL_formation_costs와_같은_결과다() throws Exception {
        long season = seasonAtTurnOne();
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 1, "069500", 3);
        sell(season, "069500", 1);
        assertThat(change(mvc, season, 1, B).getStatus()).isEqualTo(201);
        long buyOrder = jdbc.queryForObject("SELECT min(id) FROM orders WHERE side = 'BUY' AND turn_no = 1 AND season_id = ?", Long.class, season);
        jdbc.update("INSERT INTO execution (order_id, fill_price, quantity, fee, tax) VALUES (?, 1000, 2, 7, 0)", buyOrder);

        List<FormationCost> java = query.bySeason(season);
        List<List<Object>> sql = new TransactionTemplate(txManager).execute(status -> {
            try {
                jdbc.execute(Files.readString(Path.of("db/25_trade_formation_def.sql")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return jdbc.query("SELECT formation_change_id, turn_no, order_count, buy_notional, sell_notional, fee_sum, tax_sum "
                    + "FROM pg_temp.formation_costs(?)", (rs, i) -> List.<Object>of(rs.getLong(1), rs.getInt(2), rs.getLong(3),
                    rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)), season);
        });

        assertThat(sql).hasSize(java.size());
        for (int i = 0; i < java.size(); i++) {
            FormationCost c = java.get(i);
            List<Object> row = sql.get(i);
            assertThat(c.formationChangeId()).isEqualTo(row.get(0));
            assertThat(c.turnNo()).isEqualTo(row.get(1));
            assertThat(c.orderCount()).isEqualTo(row.get(2));
            assertThat(c.buyNotional()).isEqualByComparingTo((BigDecimal) row.get(3));
            assertThat(c.sellNotional()).isEqualByComparingTo((BigDecimal) row.get(4));
            assertThat(c.feeSum()).isEqualByComparingTo((BigDecimal) row.get(5));
            assertThat(c.taxSum()).isEqualByComparingTo((BigDecimal) row.get(6));
        }
    }

    /** 그 변경에 연결된 체결 행을 직접 더한 값. side 가 null 이면 방향 구분 없이. */
    private BigDecimal sum(String expression, long changeId, String side) {
        return jdbc.queryForObject("SELECT coalesce(sum(" + expression + "), 0) FROM execution e JOIN orders o ON o.id = e.order_id "
                + "WHERE o.formation_change_id = ?" + (side == null ? "" : " AND o.side = '" + side + "'"), BigDecimal.class, changeId);
    }
}

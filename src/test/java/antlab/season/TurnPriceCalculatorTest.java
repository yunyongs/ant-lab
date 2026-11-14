package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.money.MoneyMath;
import antlab.common.money.MoneyMath.ReturnFactors;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 가격 계산기: ① 다음 턴 뒤의 달 수익률을 바꿔도 결과가 같다(사후 정보 배제) ② 원화 종목은 환율 행 없이 계산된다
 * ③ 결과 = {@link MoneyMath#priceAt} 을 손으로 부른 값이고, 매 턴 반올림한 값과 다른 칸이 최소 하나 있다(의미 있는 시험 조건).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnPriceCalculatorTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    TurnPriceCalculator calculator;
    @Autowired
    SeasonPathRepository seasonPaths;

    private long startSeason() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    private Map<Long, BigDecimal> compute(long seasonId, int turn) {
        Season season = seasons.findById(seasonId).orElseThrow();
        return calculator.compute(season, turn).stream()
                .collect(Collectors.toMap(TurnPriceCalculator.AssetPrice::assetId, TurnPriceCalculator.AssetPrice::price));
    }

    @Test
    void 다음_턴_뒤_달의_수익률을_바꿔도_결과가_같다() throws Exception {
        long season = startSeason();
        Map<Long, BigDecimal> before = compute(season, 2);
        jdbc.update("""
                UPDATE asset_month_return SET local_return = 0.5
                WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no > 2)""", season);
        jdbc.update("""
                UPDATE fx_month SET fx_change = 0.5
                WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no > 2)""", season);
        assertThat(compute(season, 2)).isEqualTo(before);
        assertThat(compute(season, 3)).isNotEqualTo(before);
    }

    @Test
    void 원화_종목은_환율_변화가_비어_있어도_계산되고_조회_행의_환율은_null이다() throws Exception {
        long season = startSeason();
        List<TurnFactorRow> rows = seasonPaths.findTurnFactors(season, 2);
        assertThat(rows).hasSize(4 * 2);
        for (TurnFactorRow row : rows) {
            if ("KRW".equals(row.getCurrency())) {
                assertThat(row.getFxChange()).isNull();
            } else {
                assertThat(row.getFxChange()).isNotNull();
            }
            assertThat(row.getTurnNo()).isBetween(1, 2);
        }
        assertThat(compute(season, 2)).hasSize(4);
    }

    @Test
    void 반올림하면_0이_되는_가격은_0_이하_가드가_거절한다() throws Exception {
        long season = startSeason();
        // price * 1e-8 is far below 0.5, so the single final rounding gives 0 although no return is -1: pins signum() <= 0
        Long cheapest = jdbc.queryForObject("SELECT asset_id FROM turn_price WHERE season_id = ? AND turn_no = 0 ORDER BY price, asset_id LIMIT 1",
                Long.class, season);
        jdbc.update("""
                UPDATE asset_month_return SET local_return = -0.99999999
                WHERE asset_id = ? AND month_block_id = (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1)""",
                cheapest, season);
        Season current = seasons.findById(season).orElseThrow();
        assertThatThrownBy(() -> calculator.compute(current, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("계산된 턴 가격이 0 이하다");
    }

    @Test
    void 결과는_priceAt_손_계산과_같고_매_턴_반올림한_값과_다른_칸이_있다() throws Exception {
        long season = startSeason();
        int different = 0;
        for (int turn : new int[] {1, 3, 6, 12}) {
            Map<Long, BigDecimal> actual = compute(season, turn);
            for (Map.Entry<Long, BigDecimal> entry : actual.entrySet()) {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT tp.price AS p0, a.currency, amr.local_return, fx.fx_change
                        FROM turn_price tp JOIN asset a ON a.id = tp.asset_id
                        JOIN season_path sp ON sp.season_id = tp.season_id AND sp.turn_no BETWEEN 1 AND ?
                        JOIN asset_month_return amr ON amr.asset_id = tp.asset_id AND amr.month_block_id = sp.month_block_id
                        LEFT JOIN fx_month fx ON fx.month_block_id = sp.month_block_id AND fx.currency = a.currency
                        WHERE tp.season_id = ? AND tp.turn_no = 0 AND tp.asset_id = ? ORDER BY sp.turn_no""",
                        turn, season, entry.getKey());
                BigDecimal p0 = (BigDecimal) rows.get(0).get("p0");
                List<ReturnFactors> factors = new ArrayList<>();
                BigDecimal rolling = p0;
                for (Map<String, Object> row : rows) {
                    BigDecimal local = (BigDecimal) row.get("local_return");
                    BigDecimal fx = "KRW".equals(row.get("currency")) ? BigDecimal.ZERO : (BigDecimal) row.get("fx_change");
                    factors.add(new ReturnFactors(local, fx));
                    rolling = rolling.multiply(BigDecimal.ONE.add(local)).multiply(BigDecimal.ONE.add(fx))
                            .setScale(0, RoundingMode.HALF_UP);
                }
                assertThat(entry.getValue()).isEqualByComparingTo(MoneyMath.priceAt(p0, factors));
                if (entry.getValue().compareTo(rolling) != 0) {
                    different++;
                }
            }
        }
        assertThat(different).as("매 턴 반올림한 값과 다른 칸").isPositive();
    }
}

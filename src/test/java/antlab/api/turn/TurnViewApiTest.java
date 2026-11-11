package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnApiSupport.state;
import static antlab.api.turn.TurnViewSupport.changeFormation;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static antlab.api.turn.TurnViewSupport.ratio;
import static antlab.api.turn.TurnViewSupport.won;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * 턴 결과 조회(GET turns/n): 합성 시즌(12턴 경로)에서 금액은 그 턴 {@code turn} 행과 같고, 비율은 시험 쪽 독립 계산과 같다.
 * 전환 응답과 조회 본문이 같은 조립 코드에서 나온다는 것(T2), 목표 포메이션 경계(T3), 직전 총액·변화(T4), 위험 선언 대비(T5),
 * 거절(T6), 끝난 시즌(T7)을 본다. 경로는 무작위라 값이 아니라 식과 관계를 단언한다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnViewApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private Map<String, Object> turnRow(long season, int turn) {
        return jdbc.queryForMap("SELECT * FROM turn WHERE season_id = ? AND turn_no = ?", season, turn);
    }

    private BigDecimal previousTotal(long season, int turn) {
        return turn == 1 ? won(jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season))
                : won(turnRow(season, turn - 1).get("total_value"));
    }

    /** 응답 한 건이 DB 행과 시험 쪽 식에서 나온 기대값과 같은지 본다. 목표 포메이션은 호출자가 준다. */
    private void assertTurnBody(JsonNode o, long season, int turn, int[] target) {
        Map<String, Object> row = turnRow(season, turn);
        BigDecimal total = won(row.get("total_value"));
        BigDecimal prev = previousTotal(season, turn);
        assertThat(o.get("seasonId").asLong()).isEqualTo(season);
        assertThat(o.get("turnNo").asInt()).isEqualTo(turn);
        assertThat(o.get("cashBalance").asString()).isEqualTo(won(row.get("cash_balance")).toPlainString());
        assertThat(o.get("holdingsValue").asString()).isEqualTo(won(row.get("holdings_value")).toPlainString());
        assertThat(o.get("totalValue").asString()).isEqualTo(total.toPlainString());
        assertThat(o.get("goalkeeperValue").asString()).isEqualTo(won(row.get("goalkeeper_value")).toPlainString());
        assertThat(o.get("defenderValue").asString()).isEqualTo(won(row.get("defender_value")).toPlainString());
        assertThat(o.get("midfielderValue").asString()).isEqualTo(won(row.get("midfielder_value")).toPlainString());
        assertThat(o.get("forwardValue").asString()).isEqualTo(won(row.get("forward_value")).toPlainString());
        BigDecimal drawdown = (BigDecimal) row.get("drawdown");
        assertThat(o.get("drawdown").asString()).isEqualTo(drawdown.toPlainString());

        assertThat(o.get("previousTotalValue").asString()).isEqualTo(prev.toPlainString());
        assertThat(o.get("valueChange").asString()).isEqualTo(total.subtract(prev).toPlainString());
        assertThat(o.get("periodReturn").asString()).isEqualTo(ratio(total.subtract(prev), prev).toPlainString());

        BigDecimal riskLimit = jdbc.queryForObject("SELECT risk_limit FROM season WHERE id = ?", BigDecimal.class, season);
        assertThat(o.get("riskLimit").asString()).isEqualTo(riskLimit.toPlainString());
        assertThat(o.get("riskHeadroom").asString()).isEqualTo(drawdown.subtract(riskLimit).toPlainString());
        assertThat(o.get("riskBreached").asBoolean()).isEqualTo(drawdown.compareTo(riskLimit) < 0);

        String[] positions = {"goalkeeper", "defender", "midfielder", "forward"};
        BigDecimal hundredTotal = total.multiply(BigDecimal.valueOf(100));
        BigDecimal deviationNumerator = BigDecimal.ZERO;
        for (int i = 0; i < 4; i++) {
            BigDecimal v = won(row.get(positions[i] + "_value"));
            assertThat(o.get(positions[i] + "Weight").asString()).isEqualTo(ratio(v, total).toPlainString());
            BigDecimal diff = v.multiply(BigDecimal.valueOf(100)).subtract(BigDecimal.valueOf(target[i]).multiply(total));
            assertThat(o.get(positions[i] + "Gap").asString()).isEqualTo(ratio(diff, hundredTotal).toPlainString());
            deviationNumerator = deviationNumerator.add(diff.abs());
            assertThat(o.get("targetFormation").get(positions[i]).asInt()).isEqualTo(target[i]);
        }
        assertThat(o.get("formationDeviation").asString()).isEqualTo(ratio(deviationNumerator, hundredTotal).toPlainString());
    }

    @Test
    void 턴_조회는_턴_행의_금액과_독립_계산한_비율을_준다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 3; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        for (int n = 1; n <= 3; n++) {
            MockHttpServletResponse r = getTurn(mvc, season, n);
            assertThat(r.getStatus()).as("GET turns/" + n).isEqualTo(200);
            JsonNode o = json(r);
            assertThat(o.get("status").asString()).isEqualTo("IN_PROGRESS");
            assertThat(o.get("pathLength").asInt()).isEqualTo(12);
            assertTurnBody(o, season, n, new int[] {10, 30, 30, 30});
        }
    }

    @Test
    void 전환_응답과_바로_뒤_조회_본문이_JSON으로_같고_전술_변경이_낀_턴도_같다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 4; t++) {
            if (t == 2) {
                // 턴 2에 전술을 바꾼다 — 다음 전환(턴 3)의 목표가 새 포메이션이다.
                changeFormation(jdbc, season, 2, 25, 25, 25, 25);
            }
            JsonNode posted = json(advance(mvc, season, t));
            JsonNode fetched = json(getTurn(mvc, season, t + 1));
            assertThat(fetched).as("턴 " + (t + 1) + " 본문").isEqualTo(posted);
            assertTurnBody(fetched, season, t + 1, t + 1 >= 3 ? new int[] {25, 25, 25, 25} : new int[] {10, 30, 30, 30});
        }
    }

    @Test
    void 목표_포메이션_경계_변경한_턴은_그대로_다음_턴부터_새_값_보유_조회는_바로_새_값() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        changeFormation(jdbc, season, 2, 40, 20, 20, 20);

        JsonNode atChange = json(getTurn(mvc, season, 2));
        assertThat(atChange.get("targetFormation").get("goalkeeper").asInt()).as("변경한 턴 자체").isEqualTo(10);
        JsonNode holdings = json(getHoldings(mvc, season));
        assertThat(holdings.get("currentFormation").get("goalkeeper").asInt()).as("보유 조회는 바로 새 값").isEqualTo(40);
        assertThat(holdings.get("currentFormation").get("defender").asInt()).isEqualTo(20);

        assertThat(advance(mvc, season, 2).getStatus()).isEqualTo(201);
        assertTurnBody(json(getTurn(mvc, season, 3)), season, 3, new int[] {40, 20, 20, 20});
        assertTurnBody(json(getTurn(mvc, season, 2)), season, 2, new int[] {10, 30, 30, 30});
    }

    @Test
    void 직전_총액은_1턴이면_초기_자금_이후는_앞_턴_총액이고_하락_턴은_음수_변화와_수익률이다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        // 턴 1의 달 블록 수익률을 크게 낮춘다(시험 전용 고정본 수정) — 하락 턴이 반드시 생긴다.
        long block = jdbc.queryForObject("SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1",
                Long.class, season);
        jdbc.update("UPDATE asset_month_return SET local_return = -0.50000000 WHERE month_block_id = ?", block);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);

        JsonNode first = json(getTurn(mvc, season, 1));
        BigDecimal initialCash = jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season);
        assertThat(first.get("previousTotalValue").asString()).isEqualTo(won(initialCash).toPlainString());
        assertThat(new BigDecimal(first.get("valueChange").asString())).isNegative();
        assertThat(new BigDecimal(first.get("periodReturn").asString())).isNegative();
        JsonNode second = json(getTurn(mvc, season, 2));
        assertThat(second.get("previousTotalValue").asString()).isEqualTo(first.get("totalValue").asString());
    }

    @Test
    void 큰_하락이면_위험_선언을_넘고_평상_턴은_선언_안이다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long calmBlock = jdbc.queryForObject("SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1",
                Long.class, season);
        long crashBlock = jdbc.queryForObject("SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 2",
                Long.class, season);
        // 평상 턴: 모든 가짜 종목 +1%, 환율 변화 없음 / 폭락 턴: -90%
        jdbc.update("UPDATE asset_month_return SET local_return = 0.01000000 WHERE month_block_id = ?", calmBlock);
        jdbc.update("UPDATE asset_month_return SET local_return = -0.90000000 WHERE month_block_id = ?", crashBlock);
        jdbc.update("UPDATE fx_month SET fx_change = 0 WHERE month_block_id IN (?, ?)", calmBlock, crashBlock);
        for (int t = 0; t < 2; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        JsonNode calm = json(getTurn(mvc, season, 1));
        assertThat(calm.get("riskBreached").asBoolean()).isFalse();
        assertThat(new BigDecimal(calm.get("riskHeadroom").asString())).isNotNegative();
        JsonNode crash = json(getTurn(mvc, season, 2));
        assertThat(crash.get("riskBreached").asBoolean()).isTrue();
        assertThat(new BigDecimal(crash.get("riskHeadroom").asString())).isNegative();
        assertThat(crash.get("riskLimit").asString()).isEqualTo("-0.20000000");
    }

    @Test
    void 아직_진행하지_않은_턴은_409이고_숫자_아닌_번호와_1_미만은_400이며_상태는_그대로다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        Map<String, Object> before = state(jdbc, season);
        assertThat(getTurn(mvc, season, 1).getStatus()).as("0턴 시즌의 turns/1").isEqualTo(409);
        assertThat(code(getTurn(mvc, season, 1))).isEqualTo("TURN_NOT_REACHED");
        assertThat(getTurn(mvc, season, 0).getStatus()).isEqualTo(400);
        assertThat(code(getTurn(mvc, season, 0))).isEqualTo("INVALID_ARGUMENT");
        assertThat(getTurn(mvc, season, -1).getStatus()).isEqualTo(400);
        assertThat(getTurn(mvc, season, "abc").getStatus()).isEqualTo(400);
        assertThat(getTurn(mvc, season, "99999999999").getStatus()).isEqualTo(400);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(getTurn(mvc, season, 2).getStatus()).as("현재 턴 + 1").isEqualTo(409);
        assertThat(getTurn(mvc, season, 1).getStatus()).isEqualTo(200);
        assertThat(state(jdbc, season).get("turn")).isEqualTo(1L);
        assertThat(before.get("turn")).isEqualTo(0L);
    }

    @Test
    void 대사가_어긋나_넘어가지_못한_턴은_조회해도_409다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(409);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = getTurn(mvc, season, 1);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("TURN_NOT_REACHED");
        assertThat(r.getContentAsString()).doesNotContain("LINEUP").doesNotContain("RECONCILIATION");
        assertThat(state(jdbc, season)).as("조회는 아무것도 바꾸지 않는다").isEqualTo(before);
    }

    @Test
    void 끝난_시즌도_마지막_턴과_보유를_그대로_준다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        JsonNode last = json(getTurn(mvc, season, 12));
        assertThat(last.get("status").asString()).isEqualTo("FINISHED");
        assertTurnBody(last, season, 12, new int[] {10, 30, 30, 30});
        MockHttpServletResponse holdings = getHoldings(mvc, season);
        assertThat(holdings.getStatus()).isEqualTo(200);
        assertThat(json(holdings).get("turnNo").asInt()).isEqualTo(12);
        assertThat(getTurn(mvc, season, 13).getStatus()).isEqualTo(409);
        List<JsonNode> all = List.of(last, json(holdings));
        all.forEach(n -> assertThat(n.toString()).doesNotContainPattern("\\d{4}-\\d{2}"));
    }
}

package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static antlab.api.order.OrderApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
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
 * 1턴 이후 매수와 무결성 가드. 턴 전환을 거치지 않고 JDBC 로 1턴 상태를 직접 만든 <b>고정본</b>이다 —
 * 매수 경로(같은 코드가 0턴이 아닌 턴도 처리하는지, 전술 변경에 묶는지)를 확인하는 것이 목적이고,
 * 이 상태는 대사 규칙을 만족하지 않는다(턴 스냅샷·기준 포트폴리오가 없다). 1턴 가격을 손으로 바꿔 체결가 분기를 보는 시험이라
 * 실제 전환으로 대신할 수 없어 남겨 둔다. 실제 전환 뒤 매수와 다음 전환의 대사 17개 일치는 {@code TurnAdvanceApiTest} 가 확인한다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class BuyOrderTurnTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** 0턴 가격을 1턴으로 복사하되 한 종목만 가격을 바꿔(1턴 가격으로 체결되는지 보려고) 시즌을 1턴으로 넘긴다. */
    private long seasonAtTurnOne(String changedTicker, String newPrice) throws Exception {
        long season = startSeason(mvc);
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) "
                + "SELECT season_id, 1, asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = 0", season);
        jdbc.update("UPDATE turn_price SET price = ?::numeric WHERE season_id = ? AND turn_no = 1 AND asset_id = ?",
                newPrice, season, assetId(jdbc, changedTicker));
        jdbc.update("UPDATE season SET current_turn_no = 1 WHERE id = ?", season);
        return season;
    }

    @Test
    void 일_턴_매수는_그_턴_가격으로_체결되고_번호가_가장_큰_전술_변경에_묶인다() throws Exception {
        long season = seasonAtTurnOne("000660", "160000");
        // 그 턴의 전술 변경 두 행 — 연결은 시각이 아니라 번호 순이다. 정상 쓰기에서는 둘이 같은 순서이고(전술 변경 골든·규칙 16 변이 시험이 보인다)
        // 여기서는 번호 선택 규칙 자체를 고정하려고 일부러 어긋나게 만든 변조 데이터를 쓴다.
        long late = changeRow(season, 1, "2020-01-01 10:00:00", 10, 30, 30, 30, 10, 40, 30, 20);
        long early = changeRow(season, 1, "2020-01-01 09:00:00", 10, 40, 30, 20, 10, 30, 30, 30);
        // 0턴의 변경은 묶이지 않는다
        changeRow(season, 0, "2020-01-01 00:00:00", 10, 30, 30, 30, 20, 30, 30, 20);
        assertThat(early).isGreaterThan(late);

        MockHttpServletResponse r = place(mvc, season, body(1, "BUY", assetId(jdbc, "000660"), 10, "FORWARD_BREAKOUT"));

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(o.get("turnNo").asInt()).isEqualTo(1);
        assertThat(o.get("fillPrice").asString()).isEqualTo("160000");
        assertThat(o.get("formationChangeId").asLong()).isEqualTo(early);
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE season_id = ?", season);
        assertThat(order.get("turn_no")).isEqualTo(1);
        assertThat(order.get("formation_change_id")).isEqualTo(early);
        assertThat(jdbc.queryForObject("SELECT start_turn FROM lineup WHERE season_id = ?", Integer.class, season)).isEqualTo(1);
    }

    @Test
    void 변경이_없는_턴의_주문은_변경_번호가_없다() throws Exception {
        long season = seasonAtTurnOne("000660", "160000");
        JsonNode o = json(place(mvc, season, body(1, "BUY", assetId(jdbc, "069500"), 1, "MIDFIELDER")));
        assertThat(o.get("formationChangeId").isNull()).isTrue();
    }

    @Test
    void 일_턴_후_추가_매수는_이동평균을_쓰고_구간은_그대로다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT")); // 150,000원에 10주
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) "
                + "SELECT season_id, 1, asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = 0", season);
        jdbc.update("UPDATE turn_price SET price = 180000 WHERE season_id = ? AND turn_no = 1 AND asset_id = ?", season, asset);
        jdbc.update("UPDATE season SET current_turn_no = 1 WHERE id = ?", season);

        JsonNode o = json(place(mvc, season, body(1, "BUY", asset, 10, null)));

        // (150,000 × 10 + 180,000 × 10) / 20 = 165,000
        assertThat(o.get("holdingQuantity").asInt()).isEqualTo(20);
        assertThat(o.get("avgPrice").asString()).isEqualTo("165000.0000");
        assertThat(o.get("declaredPosition").asString()).isEqualTo("FORWARD_BREAKOUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lineup WHERE season_id = ?", Long.class, season)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT start_turn FROM lineup WHERE season_id = ?", Integer.class, season)).isZero();
    }

    @Test
    void 평균단가는_소수_넷째_자리에서_한_번_반올림해_저장하고_응답한다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT")); // 0턴 가격 150,000원에 1주
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) "
                + "SELECT season_id, 1, asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = 0", season);
        jdbc.update("UPDATE turn_price SET price = 150001 WHERE season_id = ? AND turn_no = 1 AND asset_id = ?", season, asset);
        jdbc.update("UPDATE season SET current_turn_no = 1 WHERE id = ?", season);

        JsonNode o = json(place(mvc, season, body(1, "BUY", asset, 2, null)));

        // (1 × 150,000 + 2 × 150,001) / 3 = 450,002 / 3 = 150,000.666666… → 소수 넷째 자리 HALF_UP = 150000.6667
        // (내림했다면 150000.6666 — 다섯째 자리가 6 이라 올림 경계를 본다)
        assertThat(o.get("avgPrice").asString()).isEqualTo("150000.6667");
        assertThat(jdbc.queryForObject("SELECT avg_price::text FROM holding WHERE season_id = ?", String.class, season))
                .isEqualTo("150000.6667");
    }

    // ---- 무결성 가드 (BuyOrderIntegrityTest 의 몫을 같은 고정본 안에 둔다) ----

    @Test
    void 원장_사슬이_깨지면_409_LEDGER_MISMATCH이고_행은_그대로다() throws Exception {
        long season = startSeason(mvc);
        // 잔액과 맞지 않는 원장 한 행을 끼워 최신 원장의 거래 후 잔액을 틀어 놓는다
        jdbc.update("INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (?, 'ADJUSTMENT', -1, 29999999)",
                season);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", assetId(jdbc, "000660"), 1, "FORWARD_BREAKOUT"));
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(OrderApiSupport.code(r)).isEqualTo("LEDGER_MISMATCH");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 보유_0인데_열린_구간이_있으면_409_LINEUP_MISMATCH다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        jdbc.update("INSERT INTO lineup (season_id, asset_id, declared_position, start_turn) VALUES (?, ?, 'FORWARD_BREAKOUT', 0)",
                season, asset);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"));
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(OrderApiSupport.code(r)).isEqualTo("LINEUP_MISMATCH");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 보유가_있는데_열린_구간이_없으면_409_LINEUP_MISMATCH다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        assertThat(place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT")).getStatus()).isEqualTo(201);
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ?", season);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"));
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(OrderApiSupport.code(r)).isEqualTo("LINEUP_MISMATCH");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    private long changeRow(long season, int turn, String createdAt, int bGk, int bDf, int bMf, int bFw,
            int aGk, int aDf, int aMf, int aFw) {
        return jdbc.queryForObject("""
                INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder,
                    before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamp) RETURNING id""",
                Long.class, season, turn, bGk, bDf, bMf, bFw, aGk, aDf, aMf, aFw, createdAt);
    }
}

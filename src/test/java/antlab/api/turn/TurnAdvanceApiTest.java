package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 합성 시즌(12턴)의 정상 턴 전환: 응답, 쓰인 행, 마지막 턴의 종료, 하류 약속(가격 조회·전환 뒤 매수·진행 중 상한). */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private long startSeason() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    @Test
    void 일_턴_전환은_201과_함께_가격_스냅샷_대사_17행을_쓰고_턴을_올린다() throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        long pool = jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 0", Long.class, season);
        long accountVersion = jdbc.queryForObject("SELECT version FROM cash_account WHERE season_id = ?", Long.class, season);
        long seasonVersion = jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season);

        MockHttpServletResponse r = advance(mvc, season, 0);

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(o.get("seasonId").asLong()).isEqualTo(season);
        assertThat(o.get("turnNo").asInt()).isEqualTo(1);
        assertThat(o.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(o.get("pathLength").asInt()).isEqualTo(12);
        Map<String, Object> turn = jdbc.queryForMap("SELECT * FROM turn WHERE season_id = ? AND turn_no = 1", season);
        assertThat(o.get("totalValue").asString()).isEqualTo(((java.math.BigDecimal) turn.get("total_value")).setScale(0).toPlainString());
        assertThat(o.get("drawdown").asString()).isEqualTo(((java.math.BigDecimal) turn.get("drawdown")).toPlainString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 1", Long.class, season))
                .isEqualTo(pool);
        List<Map<String, Object>> log = jdbc.queryForList(
                "SELECT turn_no, attempt_no, status FROM reconciliation_log WHERE season_id = ?", season);
        assertThat(log).hasSize(17).allSatisfy(row -> {
            assertThat(row.get("turn_no")).isEqualTo(1);
            assertThat(row.get("attempt_no")).isEqualTo(1);
            assertThat(row.get("status")).isEqualTo("MATCH");
        });
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season)).isEqualTo(seasonVersion + 1);
        assertThat(jdbc.queryForObject("SELECT version FROM cash_account WHERE season_id = ?", Long.class, season))
                .as("계좌는 읽기만 한다").isEqualTo(accountVersion);
    }

    @Test
    void 열두_번_전환하면_끝나고_응답의_상태가_FINISHED다() throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 12; t++) {
            MockHttpServletResponse r = advance(mvc, season, t);
            assertThat(r.getStatus()).as("전환 " + t).isEqualTo(201);
            assertThat(json(r).get("status").asString()).isEqualTo(t == 11 ? "FINISHED" : "IN_PROGRESS");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn WHERE season_id = ?", Long.class, season)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season))
                .isEqualTo(12 * 17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status <> 'MATCH'",
                Long.class, season)).isZero();
    }

    @Test
    void 전환_뒤에는_그_턴_가격_조회가_200이고_전환_전에는_409다() throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        MockHttpServletResponse before = mvc.get().uri("/api/v1/seasons/" + season + "/prices?turnNo=1").exchange().getResponse();
        assertThat(before.getStatus()).isEqualTo(409);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        MockHttpServletResponse after = mvc.get().uri("/api/v1/seasons/" + season + "/prices?turnNo=1").exchange().getResponse();
        assertThat(after.getStatus()).isEqualTo(200);
    }

    @Test
    void 전환_뒤_매수는_새_턴_가격으로_체결되고_다음_전환의_대사_17개가_일치한다() throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        TurnApiSupport.buy(mvc, jdbc, season, 1, "000660", 3);   // 기존 종목 추가
        TurnApiSupport.buy(mvc, jdbc, season, 1, "BRK.B", 2);    // 기존 종목 추가
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17);
    }

    @Test
    void 전환_뒤_새_종목과_기존_종목_매수가_다음_턴_스냅샷에_들어가고_대사_17개가_일치한다() throws Exception {
        long season = startSeason();
        TurnApiSupport.buy(mvc, jdbc, season, 0, "000660", 10, "FORWARD_BREAKOUT");
        TurnApiSupport.buy(mvc, jdbc, season, 0, "069500", 100, "MIDFIELDER");
        TurnApiSupport.buy(mvc, jdbc, season, 0, "BRK.B", 5, "GOALKEEPER");
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        TurnApiSupport.buy(mvc, jdbc, season, 1, "SPY", 5, "DEFENDER");   // 새 종목(외화): 1턴에 새로 연 구간
        TurnApiSupport.buy(mvc, jdbc, season, 1, "000660", 3);            // 기존 종목 추가
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);

        long spy = TurnApiSupport.assetId(jdbc, "SPY");
        assertThat(jdbc.queryForObject("SELECT start_turn FROM lineup WHERE season_id = ? AND asset_id = ?", Integer.class,
                season, spy)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT defender_value = 0 FROM turn WHERE season_id = ? AND turn_no = 1", Boolean.class,
                season)).isTrue();
        assertThat(jdbc.queryForObject("SELECT t.defender_value = (SELECT h.quantity * tp.price FROM holding h"
                + " JOIN turn_price tp ON tp.season_id = h.season_id AND tp.asset_id = h.asset_id AND tp.turn_no = 2"
                + " WHERE h.season_id = t.season_id AND h.asset_id = ?) FROM turn t WHERE t.season_id = ? AND t.turn_no = 2",
                Boolean.class, spy, season)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17);
    }

    @Test
    void 아무것도_안_산_시즌도_넘어간다_팀_규칙은_강제하지_않는다() throws Exception {
        long season = startSeason();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT holdings_value::bigint FROM turn WHERE season_id = ? AND turn_no = 1", Long.class, season)).isZero();
    }
}

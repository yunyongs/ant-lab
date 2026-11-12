package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.advanceRaw;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** 턴 전환 거절: 쓰기 전에 거절되는 사례는 어떤 행도 바꾸지 않는다. 데이터 손상(경로·수익률 빠짐)은 고정 문장의 500이다. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceRejectTest {

    private static final String FIXED_500 = "처리 중 오류가 났다";

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

    private long teamSeason() throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        return season;
    }

    private void assertRejected(MockHttpServletResponse r, int status, String code, Map<String, Object> before, long season)
            throws Exception {
        assertThat(r.getStatus()).isEqualTo(status);
        assertThat(code(r)).isEqualTo(code);
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 따옴표_숫자_expectedTurnNo는_400이고_아무것도_쓰지_않는다() throws Exception {
        long season = teamSeason();
        Map<String, Object> before = state(jdbc, season);
        assertRejected(advanceRaw(mvc, season, "{\"expectedTurnNo\": \"0\"}"), 400, "INVALID_ARGUMENT", before, season);
    }

    @Test
    void 끝난_시즌_전환은_409_SEASON_NOT_ACTIVE이고_아무것도_쓰지_않는다() throws Exception {
        long season = teamSeason();
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        Map<String, Object> before = state(jdbc, season);
        assertRejected(advance(mvc, season, 12), 409, "SEASON_NOT_ACTIVE", before, season);
        assertRejected(advance(mvc, season, 11), 409, "SEASON_NOT_ACTIVE", before, season);
    }

    @Test
    void 포기_중인_시즌은_409_SEASON_NOT_ACTIVE이고_아무것도_쓰지_않는다() throws Exception {
        // 23 SQL은 ABANDONING도 진행한다(PLAN §4.1-7). Phase 0 API에는 포기 기능이 없어 거절한다 —
        // 포기 기능(MVP)이 Season.advanceTurn과 함께 바꾼다(D7).
        long season = teamSeason();
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", season);
        Map<String, Object> before = state(jdbc, season);
        assertRejected(advance(mvc, season, 0), 409, "SEASON_NOT_ACTIVE", before, season);
    }

    @Test
    void 중간_턴에서_같은_기대_턴을_두_번_보내면_둘째는_TURN_MISMATCH다() throws Exception {
        long season = teamSeason();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        Map<String, Object> before = state(jdbc, season);
        assertRejected(advance(mvc, season, 0), 409, "TURN_MISMATCH", before, season);
        assertRejected(advance(mvc, season, 5), 409, "TURN_MISMATCH", before, season);
    }

    @Test
    void 마지막_턴에서_같은_기대_턴을_두_번_보내면_둘째는_SEASON_NOT_ACTIVE다() throws Exception {
        long season = teamSeason();
        for (int t = 0; t < 11; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        assertThat(advance(mvc, season, 11).getStatus()).isEqualTo(201);
        Map<String, Object> before = state(jdbc, season);
        assertRejected(advance(mvc, season, 11), 409, "SEASON_NOT_ACTIVE", before, season);
    }

    @Test
    void 없는_시즌과_남의_시즌은_404다() throws Exception {
        long season = startSeason();
        MockHttpServletResponse missing = advance(mvc, 987654321L, 0);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");

        Long other = jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES ('other-p033@example.invalid', '다른 감독', 'x')"
                + " RETURNING id", Long.class);
        Long mine = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
        try {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", other, season);
            Map<String, Object> before = state(jdbc, season);
            assertRejected(advance(mvc, season, 0), 404, "NOT_FOUND", before, season);
        } finally {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", mine, season);
            jdbc.update("DELETE FROM member WHERE id = ?", other);
        }
    }

    @Test
    void 기대_턴이_없거나_음수이거나_소수면_400이다() throws Exception {
        long season = startSeason();
        Map<String, Object> before = state(jdbc, season);
        for (String body : new String[] {"{}", "{\"expectedTurnNo\": -1}", "{\"expectedTurnNo\": 1.5}",
                "{\"expectedTurnNo\": null}", "{\"expectedTurnNo\": \"0\"}x"}) {
            MockHttpServletResponse r = advanceRaw(mvc, season, body);
            assertThat(r.getStatus()).as(body).isEqualTo(400);
            assertThat(code(r)).as(body).isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 다음_턴_경로가_없으면_500_고정_문장이고_아무것도_쓰지_않는다() throws Exception {
        long season = teamSeason();
        jdbc.update("DELETE FROM season_path WHERE season_id = ? AND turn_no = 1", season);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertThat(json(r).get("detail").asString()).isEqualTo(FIXED_500);
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    private String blockOfTurnOne(long season) {
        return "(SELECT month_block_id FROM season_path WHERE season_id = " + season + " AND turn_no = 1)";
    }

    @Test
    void 풀_종목의_수익률이_빠지면_500_고정_문장이고_종목이나_달이_본문에_없다() throws Exception {
        long season = teamSeason();
        jdbc.update("DELETE FROM asset_month_return WHERE asset_id = ? AND month_block_id = " + blockOfTurnOne(season),
                assetId(jdbc, "069500"));
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(json(r).get("detail").asString()).isEqualTo(FIXED_500);
        assertThat(r.getContentAsString()).doesNotContain("069500").doesNotContainPattern("\\d{4}-\\d{2}");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 외화_종목의_환율이_빠지면_500이고_원화_종목만의_빠짐과_구분된다() throws Exception {
        long season = teamSeason();
        jdbc.update("DELETE FROM fx_month WHERE month_block_id = " + blockOfTurnOne(season));
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 현지_수익률이_마이너스_일이면_계산_가격이_0이라_500이고_아무것도_쓰지_않는다() throws Exception {
        long season = teamSeason();
        jdbc.update("UPDATE asset_month_return SET local_return = -1 WHERE asset_id = ? AND month_block_id = "
                + blockOfTurnOne(season), assetId(jdbc, "000660"));
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(json(r).get("detail").asString()).isEqualTo(FIXED_500);
        assertThat(state(jdbc, season)).isEqualTo(before);
    }
}

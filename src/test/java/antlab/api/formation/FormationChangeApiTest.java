package antlab.api.formation;

import static antlab.api.formation.FormationApiSupport.A;
import static antlab.api.formation.FormationApiSupport.B;
import static antlab.api.formation.FormationApiSupport.C;
import static antlab.api.formation.FormationApiSupport.body;
import static antlab.api.formation.FormationApiSupport.change;
import static antlab.api.formation.FormationApiSupport.changeCount;
import static antlab.api.formation.FormationApiSupport.code;
import static antlab.api.formation.FormationApiSupport.current;
import static antlab.api.formation.FormationApiSupport.json;
import static antlab.api.formation.FormationApiSupport.state;
import static antlab.api.formation.FormationApiSupport.text;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.List;
import java.util.function.Supplier;
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
 * 전술 변경 API — 정상, 잘못된 입력(400), 상태 거절(404·409), 0턴 정책, 같은 턴 여러 변경과 주문 연결.
 * 시즌은 실제 턴 전환으로 1턴까지 진행한 상태에서 시작한다(팀을 사고 0턴 → 1턴). 거절은 어떤 행도 번호표도 남기지 않는다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationChangeApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    /** 팀을 사고 1턴까지 진행한 시즌. */
    private long seasonAtTurnOne() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        return season;
    }

    /**
     * 거절 도우미 — 요청을 값이 아니라 콜백으로 받아 <b>상태 캡처 → 요청 → 상태·코드 → 상태 비교</b> 순서를 지킨다.
     * 요청을 인자로 먼저 실행하면 캡처가 요청 뒤 상태가 되어 "아무것도 바꾸지 않았다"가 항상 통과한다.
     */
    private void assertRejected(long season, Supplier<MockHttpServletResponse> request, int status, String code)
            throws Exception {
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = request.get();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(code(r)).isEqualTo(code);
        assertThat(state(jdbc, season)).as("거절된 요청은 아무것도 바꾸지 않는다").isEqualTo(before);
    }

    // ---- T01 정상 ----

    @Test
    void 정상_변경은_201이고_이전은_직전_현재값_이후는_요청값이며_시즌의_현재_포메이션이_바뀐다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse r = change(mvc, season, body(1, A, "\"과열 신호\""));

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(o.get("turnNo").asInt()).isEqualTo(1);
        assertThat(text(fields(o.get("before")))).isEqualTo("10/30/30/30");
        assertThat(text(fields(o.get("after")))).isEqualTo(text(A));
        assertThat(o.get("reason").asString()).isEqualTo("과열 신호");
        assertThat(current(jdbc, season)).isEqualTo(text(A));
        assertThat(jdbc.queryForObject("SELECT start_goalkeeper || '/' || start_defender || '/' || start_midfielder || '/' || start_forward FROM season WHERE id = ?",
                String.class, season)).as("시작 포메이션은 그대로").isEqualTo("10/30/30/30");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM formation_change WHERE season_id = ?", season);
        assertThat(row.get("turn_no")).isEqualTo(1);
        assertThat(row.get("reason")).isEqualTo("과열 신호");
    }

    private static int[] fields(JsonNode f) {
        return new int[] {f.get("goalkeeper").asInt(), f.get("defender").asInt(), f.get("midfielder").asInt(),
                f.get("forward").asInt()};
    }

    // ---- T02 잘못된 입력 ----

    @Test
    void 잘못된_입력은_400이고_아무것도_남지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        List<String> bodies = List.of(
                body(1, new int[] {10, 40, 30, 19}, null), // 합 99
                body(1, new int[] {10, 40, 30, 21}, null), // 합 101
                body(1, new int[] {0, 40, 30, 30}, null), // 골키퍼 0
                body(1, new int[] {10, -10, 50, 50}, null), // 음수
                body(1, new int[] {101, 0, 0, 0}, null), // 상한 초과
                body(-1, A, null), // 음수 턴
                "{\"formation\": " + FormationApiSupport.formation(A) + "}", // 턴 누락
                "{\"expectedTurnNo\": 1}", // 포메이션 누락
                "{\"expectedTurnNo\": 1, \"formation\": {\"goalkeeper\": 10, \"defender\": 40, \"midfielder\": 30}}", // 칸 누락
                "{\"expectedTurnNo\": \"일\", \"formation\": " + FormationApiSupport.formation(A) + "}", // 잘못된 자료형
                // 소수 — 자르면 합이 100 이 되는 값이라 "합 검증"이 아니라 "정수만 받는다"로 거절된다
                "{\"expectedTurnNo\": 1, \"formation\": {\"goalkeeper\": 10.5, \"defender\": 40, \"midfielder\": 30, \"forward\": 20}}",
                "{\"expectedTurnNo\": 1.5, \"formation\": " + FormationApiSupport.formation(A) + "}",
                // 사유는 문자열만 받는다 — 숫자·불리언·배열은 문자열로 바꿔 받지 않는다
                "{\"expectedTurnNo\": 1, \"formation\": " + FormationApiSupport.formation(A) + ", \"reason\": [\"가\"]}",
                "{\"expectedTurnNo\": 1, \"formation\": " + FormationApiSupport.formation(A) + ", \"reason\": 7}",
                "{\"expectedTurnNo\": 1, \"formation\": " + FormationApiSupport.formation(A) + ", \"reason\": 1.5}",
                "{\"expectedTurnNo\": 1, \"formation\": " + FormationApiSupport.formation(A) + ", \"reason\": true}");
        for (String b : bodies) {
            String body = b;
            assertRejected(season, () -> change(mvc, season, body), 400, "INVALID_ARGUMENT");
        }
    }

    // ---- T03 같은 포메이션 ----

    @Test
    void 현재와_같은_포메이션은_400이고_버전도_그대로다() throws Exception {
        long season = seasonAtTurnOne();
        assertRejected(season, () -> change(mvc, season, 1, new int[] {10, 30, 30, 30}), 400, "INVALID_ARGUMENT");
        // 한 번 바꾼 뒤 그 값으로 다시 보내도 같다
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        assertRejected(season, () -> change(mvc, season, 1, A), 400, "INVALID_ARGUMENT");
    }

    // ---- T12 사슬 불일치 ----

    @Test
    void 저장된_현재_포메이션이_이력과_맞지_않으면_409_INVALID_FORMATION이고_쓰기가_없다() throws Exception {
        long season = seasonAtTurnOne();
        // (a) 이력 없음 — 현재 값이 시작 값과 다른 유효 포메이션(합 100)으로 어긋나 있다
        setCurrent(season, B);
        assertRejected(season, () -> change(mvc, season, 1, A), 409, "INVALID_FORMATION");
        // (b) 이력 있음 — 변경 1회 뒤 현재 값이 마지막 이후 값과 다른 유효 값으로 어긋났다
        setCurrent(season, new int[] {10, 30, 30, 30});
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        setCurrent(season, C);
        assertRejected(season, () -> change(mvc, season, 1, B), 409, "INVALID_FORMATION");
        assertThat(changeCount(jdbc, season)).isEqualTo(1L);
    }

    private void setCurrent(long season, int[] f) {
        jdbc.update("UPDATE season SET current_goalkeeper = ?, current_defender = ?, current_midfielder = ?, current_forward = ? WHERE id = ?",
                f[0], f[1], f[2], f[3], season);
    }

    // ---- T04 사유 ----

    @Test
    void 사유_생략_null_공백은_그대로_저장되고_200자_경계와_초과가_구분된다() throws Exception {
        long season = seasonAtTurnOne();
        MockHttpServletResponse omitted = change(mvc, season, body(1, A, null));
        assertThat(omitted.getStatus()).isEqualTo(201);
        assertThat(json(omitted).get("reason").isNull()).isTrue();
        MockHttpServletResponse explicitNull = change(mvc, season, body(1, B, "null"));
        assertThat(explicitNull.getStatus()).isEqualTo(201);
        assertThat(json(explicitNull).get("reason").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM formation_change WHERE season_id = ? AND reason IS NULL",
                Long.class, season)).isEqualTo(2L);
        // 앞뒤 공백도 지우지 않는다
        MockHttpServletResponse spaced = change(mvc, season, body(1, C, "\"  띄어쓰기  \""));
        assertThat(spaced.getStatus()).isEqualTo(201);
        assertThat(json(spaced).get("reason").asString()).isEqualTo("  띄어쓰기  ");
        assertThat(jdbc.queryForObject("SELECT reason FROM formation_change WHERE season_id = ? ORDER BY id DESC LIMIT 1",
                String.class, season)).isEqualTo("  띄어쓰기  ");
    }

    @Test
    void 사유_길이는_코드_포인트로_센다() throws Exception {
        long season = seasonAtTurnOne();
        // 보조 평면 문자(UTF-16 으로는 두 칸)를 200개 — PostgreSQL VARCHAR(200) 은 200자로 센다
        String emoji200 = "😀".repeat(200);
        MockHttpServletResponse ok = change(mvc, season, body(1, A, "\"" + emoji200 + "\""));
        assertThat(ok.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT length(reason) FROM formation_change WHERE season_id = ?", Integer.class,
                season)).isEqualTo(200);
        // 201자는 거절 — 거부한 사유 원문은 응답에 싣지 않는다
        String tooLong = "가".repeat(201);
        MockHttpServletResponse r = change(mvc, season, body(1, B, "\"" + tooLong + "\""));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
        assertThat(r.getContentAsString()).doesNotContain(tooLong);
        assertThat(changeCount(jdbc, season)).isEqualTo(1L);
        // 문자 200개(BMP) 경계는 통과
        assertThat(change(mvc, season, body(1, B, "\"" + "가".repeat(200) + "\"")).getStatus()).isEqualTo(201);
        // NUL 문자는 PostgreSQL 텍스트에 못 넣는다 — 500 이 아니라 400
        MockHttpServletResponse nul = change(mvc, season, body(1, C, "\"가\\u0000나\""));
        assertThat(nul.getStatus()).isEqualTo(400);
        assertThat(changeCount(jdbc, season)).isEqualTo(2L);
    }

    // ---- T05 상태 ----

    @Test
    void 없는_시즌은_404_종료된_시즌은_409_턴이_다르면_409다() throws Exception {
        long season = seasonAtTurnOne();
        assertThat(change(mvc, 999_999_999L, 1, A).getStatus()).isEqualTo(404);
        assertRejected(season, () -> change(mvc, season, 2, A), 409, "TURN_MISMATCH");
        assertRejected(season, () -> change(mvc, season, 0, A), 409, "TURN_MISMATCH");
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        assertRejected(season, () -> change(mvc, season, 1, A), 409, "SEASON_NOT_ACTIVE");
    }

    @Test
    void 다른_회원의_시즌은_404다() throws Exception {
        long season = seasonAtTurnOne();
        long mine = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
        jdbc.update("INSERT INTO member (email, name, password) VALUES ('other@example.test', '다른 회원', 'x')");
        long other = jdbc.queryForObject("SELECT id FROM member WHERE email = 'other@example.test'", Long.class);
        jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", other, season);
        try {
            MockHttpServletResponse r = change(mvc, season, 1, A);
            assertThat(r.getStatus()).isEqualTo(404);
            assertThat(code(r)).isEqualTo("NOT_FOUND");
            assertThat(changeCount(jdbc, season)).isZero();
        } finally {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", mine, season);
            jdbc.update("DELETE FROM member WHERE email = 'other@example.test'");
        }
    }

    // ---- T06 0턴 정책 ----

    @Test
    void 영_턴_전술_변경은_409이고_영_턴_매수는_그대로_된다() throws Exception {
        long season = startSeason(mvc);
        assertRejected(season, () -> change(mvc, season, 0, A), 409, "TURN_MISMATCH");
        buy(mvc, jdbc, season, 0, "000660", 1, "FORWARD_BREAKOUT"); // 0턴 매수는 허용된 채다(실패하면 예외)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season)).isEqualTo(1L);
        assertThat(changeCount(jdbc, season)).isZero();
    }

    // ---- T07·T08 연결과 다중 변경 ----

    @Test
    void 변경_전_주문은_연결되지_않고_변경_뒤_매수와_매도와_다음_턴_주문은_각각_맞게_묶인다() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "000660");
        buy(mvc, jdbc, season, 1, "069500", 1); // 변경 전 — 연결 없음
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        long a = lastChangeId(season);
        buy(mvc, jdbc, season, 1, "069500", 1); // 변경 뒤 매수
        sell(season, 1, "069500", 1); // 변경 뒤 매도
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 2, "069500", 1); // 다음 턴 — 그 턴에 변경이 없으면 연결 NULL

        List<Long> links = jdbc.queryForList("SELECT formation_change_id FROM orders WHERE season_id = ? AND asset_id = ? "
                + "ORDER BY id", Long.class, season, assetId(jdbc, "069500"));
        // 0턴 팀 구성 매수, 변경 전 매수, 변경 뒤 매수·매도, 다음 턴 매수
        assertThat(links).containsExactly(null, null, a, a, null);
        assertThat(asset).isPositive();
    }

    @Test
    void 같은_턴_두_번_변경하면_둘_다_남고_다음_주문은_마지막_변경_번호를_받는다_변경만_하고_주문이_없어도_남는다() throws Exception {
        long season = seasonAtTurnOne();
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        long a = lastChangeId(season);
        buy(mvc, jdbc, season, 1, "069500", 1);
        assertThat(change(mvc, season, 1, B).getStatus()).isEqualTo(201);
        long b = lastChangeId(season);
        buy(mvc, jdbc, season, 1, "069500", 1);
        assertThat(change(mvc, season, 1, C).getStatus()).isEqualTo(201); // 이 변경은 주문이 없다
        long c = lastChangeId(season);

        assertThat(List.of(a, b, c)).isSorted();
        assertThat(jdbc.queryForList("SELECT formation_change_id FROM orders WHERE season_id = ? AND turn_no = 1 "
                + "AND asset_id = ? ORDER BY id", Long.class, season, assetId(jdbc, "069500")))
                .as("첫 주문은 A, 둘째 주문은 B").containsExactly(a, b);
        // 사슬: B.before = A.after, C.before = B.after, 시즌 현재 = C.after
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM formation_change x JOIN formation_change y ON y.season_id = x.season_id AND y.id > x.id
                WHERE x.season_id = ? AND y.id = (SELECT min(id) FROM formation_change z WHERE z.season_id = x.season_id AND z.id > x.id)
                  AND (y.before_goalkeeper, y.before_defender, y.before_midfielder, y.before_forward)
                      = (x.after_goalkeeper, x.after_defender, x.after_midfielder, x.after_forward)""", Long.class, season))
                .isEqualTo(2L);
        assertThat(changeCount(jdbc, season)).isEqualTo(3L);
        assertThat(current(jdbc, season)).isEqualTo(text(C));
    }

    // ---- T10 시각 범위 ----

    @Test
    void 이전_턴의_늦은_시각이_다음_턴의_변경을_막지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        // 1턴에 아주 늦은 시각의 변경 한 줄(정상 사슬) — 정상 쓰기로는 만들 수 없는 변조 데이터다
        jdbc.update("""
                INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder,
                    before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward, created_at)
                VALUES (?, 1, 10, 30, 30, 30, 10, 40, 30, 20, now() + interval '30 days')""", season);
        jdbc.update("UPDATE season SET current_goalkeeper = 10, current_defender = 40, current_midfielder = 30, current_forward = 20 WHERE id = ?",
                season);
        // 같은 턴의 새 변경은 그 늦은 시각 때문에 막힌다
        assertRejected(season, () -> change(mvc, season, 1, B), 409, "EVENT_TIME_CONFLICT");
        // 그러나 다음 턴은 영향이 없다(턴 전환은 규칙 16 의 변조를 본다면 막힐 수 있어, 전환 대신 같은 턴 비교 범위만 확인한다)
        jdbc.update("UPDATE season SET current_turn_no = 2 WHERE id = ?", season);
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) SELECT season_id, 2, asset_id, price "
                + "FROM turn_price WHERE season_id = ? AND turn_no = 1", season);
        assertThat(change(mvc, season, 2, B).getStatus()).isEqualTo(201);
    }

    private long lastChangeId(long season) {
        return jdbc.queryForObject("SELECT max(id) FROM formation_change WHERE season_id = ?", Long.class, season);
    }

    private void sell(long season, int turn, String ticker, int qty) {
        String b = "{\"expectedTurnNo\": " + turn + ", \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, ticker)
                + ", \"quantity\": " + qty + "}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(b).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
    }
}

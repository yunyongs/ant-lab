package antlab.api.formation;

import static antlab.api.formation.FormationApiSupport.A;
import static antlab.api.formation.FormationApiSupport.B;
import static antlab.api.formation.FormationApiSupport.C;
import static antlab.api.formation.FormationApiSupport.change;
import static antlab.api.formation.FormationApiSupport.code;
import static antlab.api.formation.FormationApiSupport.state;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 주문·전술 변경의 이벤트 시각 가드 — 제어형 시계로 동률·역행·마이크로초 경계를 만들고, 가드가 확정한 시각이 그대로 저장되는지 본다.
 * 한 요청에서 시계를 처음 읽는 곳이 가드이므로 값 하나를 맡기면 그 값이 가드의 시각이다.
 */
@IntegrationTest
@Import(ScriptedClockConfig.class)
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationEventTimeTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ScriptedClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private long seasonAtTurnOne() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        return season;
    }

    private Instant createdAt(String table, String where, long season) {
        Timestamp t = jdbc.queryForObject("SELECT created_at FROM " + table + " WHERE season_id = ? AND " + where
                + " ORDER BY id DESC LIMIT 1", Timestamp.class, season);
        return t.toInstant();
    }

    private MockHttpServletResponse buy(long season, int turn) {
        String b = "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, "069500")
                + ", \"quantity\": 1}";
        return mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(b).exchange().getResponse();
    }

    @Test
    void 가드가_확정한_시각이_변경과_주문에_그대로_저장되고_마이크로초로_잘린다() throws Exception {
        long season = seasonAtTurnOne();
        Instant t1 = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        // 나노초가 붙은 시각을 맡겨도 저장되는 값은 마이크로초로 자른 값이다(가드가 본 값 = 저장한 값)
        clock.script(t1.plusNanos(789));
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        assertThat(createdAt("formation_change", "turn_no = 1", season)).isEqualTo(t1);

        Instant t2 = t1.plusSeconds(5);
        clock.script(t2);
        assertThat(buy(season, 1).getStatus()).isEqualTo(201);
        assertThat(createdAt("orders", "turn_no = 1", season)).isEqualTo(t2);
    }

    @Test
    void 같은_턴의_앞선_이벤트와_시각이_같거나_역행하면_변경은_409_EVENT_TIME_CONFLICT이고_아무것도_남지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        Instant t1 = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        clock.script(t1);
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);

        for (Instant bad : new Instant[] {t1, t1.minusNanos(1_000), t1.minusSeconds(60)}) {
            Map<String, Object> before = state(jdbc, season);
            clock.script(bad);
            MockHttpServletResponse r = change(mvc, season, 1, B);
            assertThat(r.getStatus()).as(bad.toString()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("EVENT_TIME_CONFLICT");
            assertThat(state(jdbc, season)).isEqualTo(before);
        }
        // 마이크로초로 자르면 같아지는 시각(+999ns)은 동률이다. 한 칸(+1µs) 뒤는 통과한다.
        clock.script(t1.plusNanos(999));
        assertThat(code(change(mvc, season, 1, B))).isEqualTo("EVENT_TIME_CONFLICT");
        clock.script(t1.plusNanos(1_000));
        assertThat(change(mvc, season, 1, B).getStatus()).isEqualTo(201);
        assertThat(createdAt("formation_change", "turn_no = 1", season)).isEqualTo(t1.plusNanos(1_000));
    }

    @Test
    void 같은_턴의_앞선_변경과_시각이_같거나_역행하면_주문도_409이고_주문_행도_번호표도_남지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        Instant t1 = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        clock.script(t1);
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);

        for (Instant bad : new Instant[] {t1, t1.minusSeconds(1)}) {
            Map<String, Object> before = state(jdbc, season);
            long identity = orderIdentity();
            clock.script(bad);
            MockHttpServletResponse r = buy(season, 1);
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("EVENT_TIME_CONFLICT");
            assertThat(state(jdbc, season)).isEqualTo(before);
            assertThat(orderIdentity()).as("검증은 첫 INSERT 앞이라 번호표도 쓰지 않는다").isEqualTo(identity);
        }
        // 앞선 주문과도 겹치면 변경이 막힌다
        clock.script(t1.plusSeconds(10));
        assertThat(buy(season, 1).getStatus()).isEqualTo(201);
        clock.script(t1.plusSeconds(10));
        assertThat(code(change(mvc, season, 1, B))).isEqualTo("EVENT_TIME_CONFLICT");
    }

    @Test
    void 이전_턴의_늦은_시각은_다음_턴의_주문과_변경을_막지_않는다() throws Exception {
        long season = seasonAtTurnOne();
        Instant late = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        clock.script(late);
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        clock.script(late.plusSeconds(1));
        assertThat(buy(season, 1).getStatus()).isEqualTo(201);

        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        // 2턴의 이벤트는 1턴보다 이른 시각(지금)이어도 된다
        assertThat(buy(season, 2).getStatus()).isEqualTo(201);
        assertThat(change(mvc, season, 2, C).getStatus()).isEqualTo(201);
        assertThat(createdAt("formation_change", "turn_no = 2", season)).isBefore(late);
    }

    @Test
    void 시각이_겹치는_상태에서도_잔액_보유_부족은_422가_먼저고_행과_번호표는_그대로다() throws Exception {
        long season = seasonAtTurnOne();
        Instant t1 = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        clock.script(t1);
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        long asset = assetId(jdbc, "069500");

        // 같은 턴 앞선 변경과 시각이 같도록 맞춘 상태 — 시각 확정이 검증 앞에 있으면 409 가 먼저 나온다(오류 우선순위 회귀 방지)
        Map<String, Object> before = state(jdbc, season);
        long identity = orderIdentity();
        clock.script(t1);
        MockHttpServletResponse cash = orderResponse(season, "BUY", asset, 1_000_000);
        clock.reset();
        assertThat(cash.getStatus()).isEqualTo(422);
        assertThat(code(cash)).isEqualTo("INSUFFICIENT_CASH");

        clock.script(t1);
        MockHttpServletResponse holding = orderResponse(season, "SELL", asset, 1_000_000);
        clock.reset();
        assertThat(holding.getStatus()).isEqualTo(422);
        assertThat(code(holding)).isEqualTo("INSUFFICIENT_HOLDING");

        assertThat(state(jdbc, season)).isEqualTo(before);
        assertThat(orderIdentity()).isEqualTo(identity);
    }

    private MockHttpServletResponse orderResponse(long season, String side, long asset, int quantity) {
        String b = "{\"expectedTurnNo\": 1, \"side\": \"" + side + "\", \"assetId\": " + asset + ", \"quantity\": " + quantity + "}";
        return mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(b).exchange().getResponse();
    }

    private long orderIdentity() {
        return jdbc.queryForObject("SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('orders', 'id')), 0)",
                Long.class);
    }
}

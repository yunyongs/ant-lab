package antlab.api.formation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전술 변경 API 시험 공용 도구. 시즌은 고정 회원의 12턴 경로, 시작 포메이션은 (10, 30, 30, 30).
 * 시험은 실제 턴 전환으로 1턴을 만든다 — 대사 17개가 일치하는 상태에서 전술을 바꾼다.
 */
final class FormationApiSupport {

    static final String[] SCRIPTS = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql", "/fixtures/turn_returns.sql"};

    /** 시작과 다른 포메이션 넷(서로 다르고 합 100, 골키퍼 ≥ 1). */
    static final int[] A = {10, 40, 30, 20};
    static final int[] B = {20, 30, 30, 20};
    static final int[] C = {15, 25, 30, 30};
    static final int[] D = {10, 30, 40, 20};

    private FormationApiSupport() {
    }

    /** 요청 본문. {@code reasonJson} 은 JSON 조각 그대로(따옴표 포함) — null 이면 필드를 뺀다. */
    static String body(Object turn, int[] f, String reasonJson) {
        return "{\"expectedTurnNo\": " + turn + ", \"formation\": " + formation(f) + (reasonJson == null ? ""
                : ", \"reason\": " + reasonJson) + "}";
    }

    static String formation(int[] f) {
        return "{\"goalkeeper\": " + f[0] + ", \"defender\": " + f[1] + ", \"midfielder\": " + f[2]
                + ", \"forward\": " + f[3] + "}";
    }

    static MockHttpServletResponse change(MockMvcTester mvc, long seasonId, String body) {
        return mvc.post().uri("/api/v1/seasons/" + seasonId + "/formation-changes")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
    }

    static MockHttpServletResponse change(MockMvcTester mvc, long seasonId, int turn, int[] f) {
        return change(mvc, seasonId, body(turn, f, null));
    }

    static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    static String code(MockHttpServletResponse r) throws Exception {
        return json(r).get("code").asString();
    }

    /** 시즌의 현재 포메이션을 "gk/df/mf/fw" 로. */
    static String current(JdbcTemplate jdbc, long season) {
        return jdbc.queryForObject("""
                SELECT current_goalkeeper || '/' || current_defender || '/' || current_midfielder || '/' || current_forward
                FROM season WHERE id = ?""", String.class, season);
    }

    static String text(int[] f) {
        return f[0] + "/" + f[1] + "/" + f[2] + "/" + f[3];
    }

    static long changeCount(JdbcTemplate jdbc, long season) {
        return jdbc.queryForObject("SELECT count(*) FROM formation_change WHERE season_id = ?", Long.class, season);
    }

    /** 변경 이력의 한 줄 요약(번호 순) — 거절된 요청이 아무것도 바꾸지 않았는지 비교한다. */
    static Map<String, Object> state(JdbcTemplate jdbc, long season) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changes", jdbc.queryForObject("""
                SELECT coalesce(string_agg(id || ':' || turn_no || ':' || before_goalkeeper || '/' || before_defender || '/'
                    || before_midfielder || '/' || before_forward || '>' || after_goalkeeper || '/' || after_defender || '/'
                    || after_midfielder || '/' || after_forward, ',' ORDER BY id), '') FROM formation_change WHERE season_id = ?""",
                String.class, season));
        out.put("season", jdbc.queryForObject("SELECT status || ':' || current_turn_no || ':' || version FROM season WHERE id = ?",
                String.class, season));
        out.put("current", current(jdbc, season));
        out.put("orders", jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season));
        out.put("identity_formation_change", jdbc.queryForObject(
                "SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('formation_change', 'id')), 0)", Long.class));
        return out;
    }
}

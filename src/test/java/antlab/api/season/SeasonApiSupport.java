package antlab.api.season;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 시즌 API 시험 공용 도구: 요청 보내기, 6개 표 행 수, 고정 회원 시즌 번호. */
final class SeasonApiSupport {

    static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");
    static final String VALID_BODY = """
            {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}}""";
    static final List<String> SIX_TABLES =
            List.of("member", "season", "season_path", "cash_account", "cash_ledger", "turn_price");

    private SeasonApiSupport() {
    }

    static MockHttpServletResponse post(MockMvcTester mvc, String body) {
        return mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange().getResponse();
    }

    static MockHttpServletResponse get(MockMvcTester mvc, String uri) {
        return mvc.get().uri(uri).exchange().getResponse();
    }

    static String body(String riskLimit, String formation, String pathLength) {
        StringBuilder sb = new StringBuilder("{\"riskLimit\": ").append(riskLimit).append(", \"formation\": ")
                .append(formation);
        if (pathLength != null) {
            sb.append(", \"pathLength\": ").append(pathLength);
        }
        return sb.append("}").toString();
    }

    static String formation(String gk, String df, String mf, String fw) {
        return "{\"goalkeeper\": " + gk + ", \"defender\": " + df + ", \"midfielder\": " + mf + ", \"forward\": " + fw + "}";
    }

    /** 6개 표의 행 수(표 이름 → 건수). */
    static Map<String, Long> counts(JdbcTemplate jdbc) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String table : SIX_TABLES) {
            out.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return out;
    }
}

package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * 턴 전환 1회의 SQL 문 수를 범주별로 세어 출력한다. 절대값은 단언하지 않는다(P0 실패 조건 아님) —
 * 풀 크기·규칙 수에 비례하고, 보유 종목 수와는 무관함만 단언한다(보유 1종목 시즌 = 보유 3종목 시즌).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceQueryCountTest {

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

    private void buy(long season, String ticker, int qty, String position) {
        String body = "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, ticker)
                + ", \"quantity\": " + qty + ", \"declaredPosition\": \"" + position + "\"}";
        assertThat(mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse().getStatus()).isEqualTo(201);
    }

    private static String category(String sql) {
        String s = sql.toLowerCase(Locale.ROOT);
        if (s.contains("for no key update") || s.contains("for update")) {
            return "잠금 조회";
        } else if (s.startsWith("insert into turn_price")) {
            return "turn_price INSERT";
        } else if (s.startsWith("insert into turn ")) {
            return "turn INSERT";
        } else if (s.startsWith("insert into reconciliation_log")) {
            return "reconciliation_log INSERT";
        } else if (s.startsWith("update")) {
            return "UPDATE";
        } else if (s.contains("transaction_isolation") || s.contains("recon_") || s.contains("jsonb_build_object")) {
            return "대사 규칙·격리";
        }
        return "그 밖의 조회";
    }

    private Map<String, Integer> measure(long season) throws Exception {
        SqlRecorder.start();
        MockHttpServletResponse r = advance(mvc, season, 0);
        List<String> sqls = SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(201);
        Map<String, Integer> counts = new LinkedHashMap<>();
        sqls.forEach(q -> counts.merge(category(q), 1, Integer::sum));
        counts.put("합계", sqls.size());
        return counts;
    }

    @Test
    void 문장_수는_보유_종목_수와_무관하다() throws Exception {
        long one = startSeason();
        buy(one, "000660", 10, "FORWARD_BREAKOUT");
        long three = startSeason();
        buy(three, "000660", 10, "FORWARD_BREAKOUT");
        buy(three, "069500", 100, "MIDFIELDER");
        buy(three, "SPY", 5, "DEFENDER");

        Map<String, Integer> a = measure(one);
        Map<String, Integer> b = measure(three);
        System.out.println("[X7] 보유 1종목 전환 문장 수 = " + a);
        System.out.println("[X7] 보유 3종목 전환 문장 수 = " + b);
        assertThat(a.get("합계")).isEqualTo(b.get("합계"));
        assertThat(a).isEqualTo(b);
    }

    /**
     * 2턴 이후의 전환은 직전 턴 총액을 한 번 읽어(턴 결과 조립용) 1턴 전환보다 정확히 1문장 많다 — 그 문장도 보유 종목 수와 무관하다.
     * 실측(P0-35, 풀 4종목 합성 시즌): 1턴 전환 52문장, 2턴 이후 53문장.
     */
    @Test
    void 이턴_이후_전환은_직전_총액_조회_한_문장이_더해지고_보유_종목_수와_무관하다() throws Exception {
        long one = startSeason();
        buy(one, "000660", 10, "FORWARD_BREAKOUT");
        long three = startSeason();
        buy(three, "000660", 10, "FORWARD_BREAKOUT");
        buy(three, "069500", 100, "MIDFIELDER");
        buy(three, "SPY", 5, "DEFENDER");
        Map<String, Integer> firstOne = measure(one);
        Map<String, Integer> firstThree = measure(three);

        SqlRecorder.start();
        MockHttpServletResponse r1 = advance(mvc, one, 1);
        List<String> second1 = SqlRecorder.stop();
        SqlRecorder.start();
        MockHttpServletResponse r3 = advance(mvc, three, 1);
        List<String> second3 = SqlRecorder.stop();
        assertThat(r1.getStatus()).isEqualTo(201);
        assertThat(r3.getStatus()).isEqualTo(201);
        System.out.println("[X7] 2턴 전환 문장 수 (1종목) = " + second1.size() + ", (3종목) = " + second3.size()
                + ", 1턴 전환 = " + firstOne.get("합계"));
        assertThat(second1).hasSameSizeAs(second3);
        assertThat(firstOne.get("합계")).isEqualTo(firstThree.get("합계"));
        assertThat(second1.size()).isEqualTo(firstOne.get("합계") + 1);
    }
}

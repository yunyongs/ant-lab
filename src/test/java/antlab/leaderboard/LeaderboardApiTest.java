package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.detection.DetectionImportService;
import antlab.support.DefaultBearerConfig;
import antlab.support.DetectionFixtures;
import antlab.support.TestTokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The leaderboard over HTTP: status codes, the serialised body's privacy and the viewer's own block per token. */
class LeaderboardApiTest extends LeaderboardTestBase {

    static final String PATH = "/api/v1/leaderboards/manager";
    static final Set<String> FORBIDDEN_KEYS = Set.of("email", "memberId", "agentId", "seasonId", "ownerMemberId", "leagueId",
            "referenceKey", "id", "createdAt", "finishedAt", "adoptedAt", "baseDate");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    TestTokens tokens;
    @Autowired
    DetectionImportService detections;

    private MockHttpServletResponse get(String query, Long viewer) {
        var request = mvc.get().uri(PATH + query);
        if (viewer != null) {
            request = request.header("Authorization", "Bearer " + tokens.user(viewer));
        }
        return request.exchange().getResponse();
    }

    private JsonNode body(String query, long viewer) throws Exception {
        MockHttpServletResponse r = get(query, viewer);
        assertThat(r.getStatus()).isEqualTo(200);
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            for (var e : node.properties()) {
                out.add(e.getKey());
                out.addAll(keys(e.getValue()));
            }
        } else if (node.isArray()) {
            node.forEach(n -> out.addAll(keys(n)));
        }
        return out;
    }

    private static JsonNode rowNamed(JsonNode body, String name) {
        for (JsonNode r : body.get("rows")) {
            if (r.get("displayName").asString().equals(name)) {
                return r;
            }
        }
        throw new AssertionError("no row " + name);
    }

    @Test
    void 로그인한_회원은_200_토큰이_없으면_401_kind가_틀리면_400() throws Exception {
        assertThat(mvc.get().uri(PATH).exchange().getResponse().getStatus()).isEqualTo(200);
        var anonymous = mvc.get().uri(PATH).requestAttr(DefaultBearerConfig.ANONYMOUS, true).exchange().getResponse();
        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(JsonMapper.shared().readTree(anonymous.getContentAsString()).get("code").asString())
                .isEqualTo("UNAUTHENTICATED");
        var bad = mvc.get().uri(PATH + "?kind=ROBOT").exchange().getResponse();
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(bad.getContentType()).startsWith("application/problem+json");
        assertThat(JsonMapper.shared().readTree(bad.getContentAsString()).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(bad.getContentAsString()).doesNotContain("ROBOT", "LeaderboardKind", "antlab.");
    }

    @Test
    void 본문에는_식별자와_날짜가_없고_소유한_에이전트만_ownedByMe다() throws Exception {
        detectionTested();
        long a = human("lb-api-a");
        counted(a, P.flat("50"));
        counted(a, P.flat("50"));
        counted(a, P.flat("50"));
        long b = human("lb-api-b");
        long mineAgent = agent(a, "Mine", "fake/model-1");
        long theirs = agent(b, "Theirs", "fake/lb-theirs");
        for (int i = 0; i < 5; i++) {
            countedAgent(mineAgent, P.flat("60"), 1, null);
            countedAgent(theirs, P.flat("40"), 1, 2);
        }

        String raw = get("", a).getContentAsString();
        JsonNode body = JsonMapper.shared().readTree(raw);
        assertThat(keys(body)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(raw).doesNotContain("owner-typed-name", "@example.test", "a".repeat(64));
        // the only dates are the detection run times
        String withoutRunAt = raw.replaceAll("\"runAt\":\"[^\"]*\"", "\"runAt\":\"\"");
        assertThat(withoutRunAt).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        assertThat(raw).containsPattern("\"runAt\":\"\\d{4}-\\d{2}-\\d{2}T");

        assertThat(rowNamed(body, "Mine · fake/model-1").get("ownedByMe").asBoolean()).isTrue();
        assertThat(rowNamed(body, "Theirs · fake/lb-theirs").get("ownedByMe").asBoolean()).isFalse();
        assertThat(rowNamed(body, "lb-api-a").get("ownedByMe").asBoolean()).isFalse();
        assertThat(rowNamed(body, "lb-api-a").get("me").asBoolean()).isTrue();
        assertThat(rowNamed(body, "lb-api-a").get("agent").isNull()).isTrue();
        assertThat(rowNamed(body, "Theirs · fake/lb-theirs").get("agent").get("outlook").isNull()).isTrue();
        assertThat(rowNamed(body, "Mine · fake/model-1").get("agent").get("detection").get("windows").get(0)
                .get("metrics").get(0).get("accuracy").asString()).isEqualTo("0.01020408");
        assertThat(body.get("formula").get("weights").get("return").asString()).isEqualTo("0.30");
        assertThat(rowNamed(body, "lb-api-a").get("l").asString()).isEqualTo("50.00");
        assertThat(rowNamed(body, "lb-api-a").get("meanMultiple").asString()).isEqualTo("1.00000000");

        JsonNode asB = body("", b);
        assertThat(rowNamed(asB, "Mine · fake/model-1").get("ownedByMe").asBoolean()).isFalse();
        assertThat(rowNamed(asB, "Theirs · fake/lb-theirs").get("ownedByMe").asBoolean()).isTrue();
    }

    @Test
    void 보는_사람마다_자기_시즌만_받고_열린_리그_자리는_숨는다() throws Exception {
        long a = human("lb-me-a");
        counted(a, new P("60", "60", "60", "60", "1.10000000", PROCESS_VERSION));
        excluded(a, "NOT_STARTED");
        long seat = counted(a, P.flat("90"));
        long league = league(a, seat);
        long b = human("lb-me-b");
        counted(b, P.flat("10"));
        long c = human("lb-me-c");

        JsonNode me = body("?kind=AGENT", a).get("me");
        assertThat(me.get("kind").asString()).isEqualTo("HUMAN");
        assertThat(me.get("counted").asInt()).isEqualTo(1);
        assertThat(me.get("minimum").asInt()).isEqualTo(3);
        assertThat(me.get("ranked").asBoolean()).isFalse();
        assertThat(me.get("rank").isNull()).isTrue();
        assertThat(me.get("l").isNull()).isTrue();
        assertThat(me.get("counts").get("awaitingLeagueClose").asInt()).isEqualTo(1);
        JsonNode seasons = me.get("seasons");
        assertThat(seasons.size()).isEqualTo(3);
        assertThat(seasons.get(0).get("seasonNo").asInt()).isEqualTo(3);
        assertThat(seasons.get(0).get("excluded").asString()).isEqualTo("AWAITING_LEAGUE_CLOSE");
        assertThat(seasons.get(0).get("s").isNull()).isTrue();
        assertThat(seasons.get(0).get("parts").isNull()).isTrue();
        assertThat(seasons.get(0).get("multiple").isNull()).isTrue();
        assertThat(seasons.get(1).get("excluded").asString()).isEqualTo("NOT_STARTED");
        JsonNode used = seasons.get(2);
        assertThat(used.get("seasonNo").asInt()).isEqualTo(1);
        assertThat(used.get("excluded").isNull()).isTrue();
        assertThat(used.get("used").asBoolean()).isTrue();
        assertThat(used.get("s").asString()).isEqualTo("60.00");
        assertThat(used.get("parts").get("return").asString()).isEqualTo("60.00000000");
        assertThat(used.get("parts").get("process").asString()).isEqualTo("60.00");
        assertThat(used.get("processScoreVersion").asString()).isEqualTo(PROCESS_VERSION);
        assertThat(used.get("multiple").asString()).isEqualTo("1.10000000");

        JsonNode asB = body("", b).get("me");
        assertThat(asB.get("seasons").size()).isEqualTo(1);
        assertThat(asB.get("seasons").get(0).get("s").asString()).isEqualTo("10.00");
        assertThat(body("", c).get("me").isNull()).isTrue();

        closeLeague(league);
        counted(a, P.flat("30"));
        JsonNode humans = body("?kind=HUMAN", a);
        assertThat(humans.get("me").get("ranked").asBoolean()).isTrue();
        assertThat(humans.get("me").get("rank").asInt()).isEqualTo(rowNamed(humans, "lb-me-a").get("rank").asInt());
        assertThat(humans.get("me").get("l").asString()).isEqualTo(rowNamed(humans, "lb-me-a").get("l").asString());
        assertThat(body("?kind=AGENT", a).get("me").get("rank").isNull()).isTrue();
    }

    private static List<String> seasonLines(JsonNode me) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : me.get("seasons")) {
            out.add(s.get("seasonNo").asInt() + " " + (s.get("s").isNull() ? "-" : s.get("s").asString()) + " "
                    + (s.get("excluded").isNull() ? "" : s.get("excluded").asString()) + " " + s.get("used").asBoolean());
        }
        return out;
    }

    @Test
    void 최근_열_시즌은_시즌_번호가_아니라_끝난_시각으로_고르고_같은_시각이면_번호가_큰_쪽이_새것이다() throws Exception {
        long a = human("lb-order");
        String[] values = {"95", "10", "20", "30", "40", "50", "60", "70", "80", "5", "45"};
        long[] ids = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            ids[i] = counted(a, P.flat(values[i]));
        }
        // the lowest id finishes last; the two highest ids share the boundary finish time
        for (int i = 0; i < 9; i++) {
            finishAt(ids[i], 100 - i);
        }
        finishAt(ids[9], 91);
        finishAt(ids[10], 91);

        JsonNode body = body("?kind=HUMAN", a);
        JsonNode r = rowNamed(body, "lb-order");
        assertThat(r.get("seasonsUsed").asInt()).isEqualTo(10);
        BigDecimal expected = CareerScore.of(Stream.of("95", "10", "20", "30", "40", "50", "60", "70", "80", "45")
                .map(BigDecimal::new).toList()).l();
        assertThat(r.get("l").asString()).isEqualTo(expected.toPlainString());
        assertThat(expected).isNotEqualByComparingTo(CareerScore.of(Stream.of("10", "20", "30", "40", "50", "60", "70",
                "80", "5", "45").map(BigDecimal::new).toList()).l());
        assertThat(expected).isNotEqualByComparingTo(CareerScore.of(Stream.of("95", "10", "20", "30", "40", "50", "60",
                "70", "80", "5").map(BigDecimal::new).toList()).l());
        assertThat(seasonLines(body.get("me"))).containsExactly("11 95.00  true", "10 10.00  true", "9 20.00  true",
                "8 30.00  true", "7 40.00  true", "6 50.00  true", "5 60.00  true", "4 70.00  true", "3 80.00  true",
                "2 45.00  true", "1 5.00  false");
    }

    @Test
    void 열_시즌_뒤의_제외_시즌은_쓴_시즌_수와_L을_바꾸지_않는다() throws Exception {
        long a = human("lb-tail");
        String[] values = {"10", "20", "30", "40", "50", "60", "70", "80", "90", "100"};
        for (String v : values) {
            counted(a, P.flat(v));
        }
        long seat = counted(a, P.flat("0"));
        league(a, seat);
        season(a, "FINISHED");
        excluded(a, "NO_REFERENCE");

        JsonNode body = body("", a);
        JsonNode r = rowNamed(body, "lb-tail");
        assertThat(r.get("seasonsUsed").asInt()).isEqualTo(10);
        assertThat(r.get("l").asString()).isEqualTo(
                CareerScore.of(Stream.of(values).map(BigDecimal::new).toList()).l().toPlainString());
        JsonNode counts = r.get("counts");
        assertThat(List.of(counts.get("counted").asInt(), counts.get("awaitingLeagueClose").asInt(),
                counts.get("pending").asInt(), counts.get("noReference").asInt())).containsExactly(10, 1, 1, 1);
        assertThat(seasonLines(body.get("me"))).containsExactly("13 - NO_REFERENCE false", "12 - PENDING false",
                "11 - AWAITING_LEAGUE_CLOSE false", "10 100.00  true", "9 90.00  true", "8 80.00  true",
                "7 70.00  true", "6 60.00  true", "5 50.00  true", "4 40.00  true", "3 30.00  true", "2 20.00  true",
                "1 10.00  true");
    }

    @Test
    void 시즌은_있지만_집계가_없으면_me는_영으로_채운다() throws Exception {
        long running = human("lb-zero-running");
        season(running, "IN_PROGRESS");
        long out = human("lb-zero-out");
        excluded(out, "NOT_STARTED");

        JsonNode me = body("", running).get("me");
        assertThat(me.isNull()).isFalse();
        assertThat(me.get("counted").asInt()).isZero();
        assertThat(me.get("ranked").asBoolean()).isFalse();
        assertThat(me.get("rank").isNull()).isTrue();
        assertThat(me.get("l").isNull()).isTrue();
        assertThat(me.get("counts").get("unfinished").asInt()).isEqualTo(1);
        assertThat(sum(me.get("counts"))).isEqualTo(1);
        assertThat(me.get("seasons").size()).isZero();

        JsonNode outMe = body("", out).get("me");
        assertThat(outMe.get("counted").asInt()).isZero();
        assertThat(outMe.get("ranked").asBoolean()).isFalse();
        assertThat(outMe.get("counts").get("notStarted").asInt()).isEqualTo(1);
        assertThat(sum(outMe.get("counts"))).isEqualTo(1);
        assertThat(seasonLines(outMe)).containsExactly("1 - NOT_STARTED false");
        assertThat(outMe.get("seasons").get(0).get("parts").isNull()).isTrue();
    }

    private static int sum(JsonNode counts) {
        int total = 0;
        for (var e : counts.properties()) {
            total += e.getValue().asInt();
        }
        return total;
    }

    private void detectionTested() {
        jdbc.update("DELETE FROM detection_metric");
        jdbc.update("DELETE FROM detection_test");
        detections.importSummary(JsonMapper.shared().readTree(DetectionFixtures.summary("c".repeat(64))));
    }
}

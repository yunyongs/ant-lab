package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.report.ReportApiSupport;
import antlab.api.review.ReviewApiSupport;
import antlab.support.E2eHttp;
import antlab.support.E2eHttp.Reply;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TeamBuyGoldenSeason;
import antlab.support.TestTokens;
import antlab.scoring.SeasonReportDto;
import antlab.season.SeedGenerator;
import java.math.BigDecimal;
import java.lang.reflect.RecordComponent;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * One season played through HTTP alone, on the real sample (P0-40). Runs only when the sample CSVs exist
 * ({@code ANTLAB_GOLDEN_SAMPLE_DIR} or {@code db/sample}); a harness container holds the API database.
 *
 * <p>The client acts like a player: it reads {@code GET /pool} and {@code GET /prices} to build an 8-line team, then advances
 * 12 turns (partial sell, formation change and a buy bound to it at turn 5, full sell at turn 8) and reads the report and the
 * review. Assertions are shapes and counts; values stay with the P0-30..39 goldens. The steps share one season, so the
 * methods are ordered.
 */
@Tag("sql-golden")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@ActiveProfiles({"test", "local"})
@Import(TestTokens.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("sampleAvailable")
class OneSeasonE2ETest {

    /** Long enough that its digits cannot appear in a response by chance. */
    private static final long SEED = 4815162342L;
    private static final int TURNS = 12;
    private static final String CHANGE_REASON = "방어를 줄이고 공격을 늘린다";
    private static final String CHANGE_BODY = "{\"expectedTurnNo\": 5, \"formation\": {\"goalkeeper\": 10,"
            + " \"defender\": 20, \"midfielder\": 30, \"forward\": 40}, \"reason\": \"" + CHANGE_REASON + "\"}";

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "one_season_e2e");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedSeed {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> SEED;
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    @Autowired
    TestTokens tokens;

    /** The dev gate header plus an ADMIN token (replaces the default user token for the call). */
    private Map<String, String> dev() {
        return Map.of("X-Antlab-Dev", "true", "Authorization", "Bearer " + tokens.admin(TestTokens.DEFAULT_SUBJECT));
    }

    private E2eHttp http;
    private long season;
    private int finishedAtReply;
    private JsonNode pool;
    private final Map<Long, String> position = new LinkedHashMap<>();

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        harness.execute(apiDb, SqlGoldenScenario.apiDbFullLoad());
        http = new E2eHttp(port, Map.of("Authorization", "Bearer " + tokens.user(TestTokens.DEFAULT_SUBJECT)));
    }

    @AfterAll
    void close() throws Exception {
        golden.close();
    }

    // ---------------------------------------------------------------- play

    @Test
    @Order(1)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 시즌을_시작하고_풀과_가격만_보고_8줄_팀을_산다() {
        Reply start = http.post("/api/v1/seasons", "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 30, \"midfielder\": 30, \"forward\": 30}, \"pathLength\": " + TURNS + "}");
        assertThat(start.status()).as(start.body()).isEqualTo(201);
        season = start.json().get("id").asLong();
        assertThat(start.json().get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(start.json().get("currentTurnNo").asInt()).isZero();
        assertThat(start.json().get("pathLength").asInt()).isEqualTo(TURNS);
        BigDecimal cash = money(start.json().get("cashBalance"));

        Reply poolReply = http.get("/api/v1/pool");
        assertThat(poolReply.status()).isEqualTo(200);
        pool = poolReply.json().get("assets");
        pool.forEach(a -> position.put(a.get("assetId").asLong(), a.get("systemPosition").asString()));
        Reply prices = http.get(url("/prices?turnNo=0"));
        assertThat(prices.status()).isEqualTo(200);
        Map<Long, BigDecimal> price = prices(prices);

        // 8 lines: 1 goalkeeper, 2 defenders, 2 midfielders, 3 forwards; 90% of each line's formation share is spent
        List<long[]> lines = new ArrayList<>();
        lines.addAll(pick("GOALKEEPER", 1, 10));
        lines.addAll(pick("DEFENDER", 2, 15));
        lines.addAll(pick("MIDFIELDER", 2, 15));
        lines.addAll(pickForwards(3, 10));
        assertThat(lines).as("team lines").hasSize(8);
        for (long[] line : lines) {
            long assetId = line[0];
            BigDecimal budget = cash.multiply(BigDecimal.valueOf(line[1])).movePointLeft(2).multiply(new BigDecimal("0.9"));
            int quantity = budget.divide(price.get(assetId), 0, RoundingMode.DOWN).intValueExact();
            assertThat(quantity).as("affordable quantity of " + assetId).isGreaterThanOrEqualTo(1);
            Reply order = http.post(url("/orders"), buyBody(0, assetId, quantity, position.get(assetId)));
            assertThat(order.status()).as(order.body()).isEqualTo(201);
        }
        Reply holdings = http.get(url("/holdings"));
        assertThat(holdings.status()).isEqualTo(200);
        assertThat(holdings.json().get("holdings").size()).isEqualTo(8);
        assertThat(money(holdings.json().get("cashBalance")).signum()).isGreaterThanOrEqualTo(0);

        // the turn-0 manual check (S21 of the SQL run): 90's negative cases N2 and N6 need an earlier manual bundle
        Reply manual = http.post("/api/v1/dev/seasons/" + season + "/reconciliation", "{}", dev());
        assertThat(manual.status()).as(manual.body()).isEqualTo(200);
        assertThat(manual.json().get("mismatchRules").asInt()).isZero();
    }

    @Test
    @Order(2)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 세_번째_턴까지_넘긴다() {
        advanceTo(3);
    }

    @Test
    @Order(3)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 시즌_중_거절은_문제_본문이고_상태를_바꾸지_않는다() {
        Map<String, Object> before = state();
        long held = firstHeldAsset();

        Reply overCash = http.post(url("/orders"), buyBody(3, held, 2_000_000_000, position.get(held)));
        expectProblem(overCash, 422, "INSUFFICIENT_CASH");
        expectProblem(http.post(url("/turns"), "{\"expectedTurnNo\": 2}"), 409, "TURN_MISMATCH");
        expectProblem(http.get(url("/prices?turnNo=4")), 409, "TURN_NOT_REACHED");
        expectProblem(http.get(url("/report")), 409, "SEASON_NOT_FINISHED");
        expectProblem(http.get(url("/review")), 409, "SEASON_NOT_FINISHED");

        assertThat(state()).as("rejections change nothing").isEqualTo(before);
    }

    @Test
    @Order(4)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 다섯_번째_턴에서_부분_매도_전술_변경_묶인_매수를_한다() {
        advanceTo(5);
        JsonNode held = holdings();
        JsonNode sellFrom = null;
        for (JsonNode h : held) {
            if ("DEFENDER".equals(h.get("declaredPosition").asString()) && h.get("quantity").asInt() >= 2) {
                sellFrom = h;
                break;
            }
        }
        assertThat(sellFrom).as("a defender line with at least 2 units to sell part of").isNotNull();
        int half = sellFrom.get("quantity").asInt() / 2;
        Reply sell = http.post(url("/orders"), "{\"expectedTurnNo\": 5, \"side\": \"SELL\", \"assetId\": "
                + sellFrom.get("assetId").asLong() + ", \"quantity\": " + half + "}");
        assertThat(sell.status()).as(sell.body()).isEqualTo(201);
        assertThat(sell.json().get("formationChangeId").isNull()).as("before the change").isTrue();

        Reply change = http.post(url("/formation-changes"), CHANGE_BODY);
        assertThat(change.status()).as(change.body()).isEqualTo(201);

        // a pool asset not held yet, bought after the change: the order is bound to it
        List<Long> heldIds = new ArrayList<>();
        held.forEach(h -> heldIds.add(h.get("assetId").asLong()));
        long fresh = -1;
        for (JsonNode a : pool) {
            long id = a.get("assetId").asLong();
            if (!heldIds.contains(id) && a.get("systemPosition").asString().startsWith("FORWARD")) {
                fresh = id;
                break;
            }
        }
        assertThat(fresh).as("a forward not held yet").isPositive();
        Reply buy = http.post(url("/orders"), buyBody(5, fresh, 1, position.get(fresh)));
        assertThat(buy.status()).as(buy.body()).isEqualTo(201);
        assertThat(buy.json().get("formationChangeId").isNull()).as("bound to the change").isFalse();
        List<Long> changeIds = jdbc.queryForList("SELECT id FROM formation_change WHERE season_id = ?", Long.class, season);
        assertThat(changeIds).as("the season's single formation change").hasSize(1);
        assertThat(buy.json().get("formationChangeId").asLong()).isEqualTo(changeIds.get(0));
    }

    @Test
    @Order(5)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 여덟_번째_턴에서_한_종목을_전량_매도한다() {
        advanceTo(8);
        JsonNode sellAll = null;
        for (JsonNode h : holdings()) {
            if ("MIDFIELDER".equals(h.get("declaredPosition").asString())) {
                sellAll = h;
                break;
            }
        }
        assertThat(sellAll).as("a midfielder line").isNotNull();
        Reply sell = http.post(url("/orders"), "{\"expectedTurnNo\": 8, \"side\": \"SELL\", \"assetId\": "
                + sellAll.get("assetId").asLong() + ", \"quantity\": " + sellAll.get("quantity").asInt() + "}");
        assertThat(sell.status()).as(sell.body()).isEqualTo(201);
    }

    @Test
    @Order(6)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 끝까지_넘기면_끝난_시즌이다() {
        advanceTo(TURNS);
        Reply one = http.get("/api/v1/seasons/" + season);
        assertThat(one.json().get("status").asString()).isEqualTo("FINISHED");
        assertThat(one.json().get("currentTurnNo").asInt()).isEqualTo(TURNS);
        finishedAtReply = http.captured().size();
    }

    // ---------------------------------------------------------------- checks

    @Test
    @Order(7)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 성적표와_복기의_모양() {
        Reply report = http.get(url("/report"));
        assertThat(report.status()).as(report.body()).isEqualTo(200);
        JsonNode r = report.json();
        assertThat(r.size()).as("report keys").isEqualTo(37);
        List<String> names = new ArrayList<>();
        r.propertyNames().forEach(names::add);
        assertThat(names).as("report keys are the DTO's components").containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(SeasonReportDto.class.getRecordComponents()).map(RecordComponent::getName).toList());
        Set<String> typed = new java.util.TreeSet<>(ReportApiSupport.MONEY);
        typed.addAll(ReportApiSupport.RATIO);
        assertThat(names).as("money and ratio keys are present").containsAll(typed);
        assertThat(r.get("isFinal").asBoolean()).isTrue();
        assertThat(r.get("formationChanges").asInt()).isGreaterThanOrEqualTo(1);
        for (String key : ReportApiSupport.MONEY) {
            JsonNode v = r.get(key);
            assertThat(v.isNull()).as(key + " is not null").isFalse();
            assertThat(v.isString()).as(key + " is a JSON string").isTrue();
            assertThat(v.asString()).as(key).matches(ReportApiSupport.MONEY_FORMAT);
        }
        for (String key : ReportApiSupport.RATIO) {
            JsonNode v = r.get(key);
            if (!v.isNull()) {
                assertThat(v.isString()).as(key + " is a JSON string").isTrue();
                assertThat(v.asString()).as(key).matches(ReportApiSupport.RATIO_FORMAT);
            }
        }

        Reply review = http.get(url("/review"));
        assertThat(review.status()).as(review.body()).isEqualTo(200);
        JsonNode body = review.json();
        JsonNode turns = body.get("turns");
        Map<Integer, List<Map<String, Object>>> oracle = ReviewApiSupport.oracle(jdbc, season);
        assertThat(turns.size()).isEqualTo(TURNS).isEqualTo(oracle.size());
        assertThat(body.get("seasonStatus").asString()).isEqualTo("FINISHED");
        assertThat(body.get("pathLength").asInt()).isEqualTo(TURNS);
        assertThat(body.get("abandonedTurnNo").isNull()).isTrue();
        assertThat(body.get("seed").asString()).isEqualTo(Long.toString(SEED));
        JsonNode changes = body.get("formationChanges");
        assertThat(changes.size()).as("the one change the test sent").isEqualTo(1);
        JsonNode change = changes.get(0);
        assertThat(change.get("turnNo").asInt()).isEqualTo(5);
        assertThat(formationOf(change.get("before"))).isEqualTo(List.of(10, 30, 30, 30));
        assertThat(formationOf(change.get("after"))).isEqualTo(List.of(10, 20, 30, 40));
        assertThat(change.get("reason").asString()).isEqualTo(CHANGE_REASON);
        int turnNo = 1;
        for (JsonNode turn : turns) {
            assertThat(turn.get("turnNo").asInt()).isEqualTo(turnNo);
            List<Map<String, Object>> rows = oracle.get(turnNo);
            Map<String, Object> first = rows.get(0);
            assertThat(turn.get("yearMonth").asString()).isEqualTo(first.get("year_month"));
            assertThat(text(turn.get("eventSummary"))).isEqualTo(text(first.get("event_summary")));
            List<Map<String, Object>> eventRows = rows.stream().filter(row -> row.get("title") != null).toList();
            JsonNode events = turn.get("events");
            assertThat(events.size()).as("events of turn " + turnNo).isEqualTo(eventRows.size());
            for (int i = 0; i < eventRows.size(); i++) {
                Map<String, Object> row = eventRows.get(i);
                JsonNode e = events.get(i);
                assertThat(e.get("category").asString()).isEqualTo(row.get("category"));
                assertThat(e.get("eventType").asString()).isEqualTo(row.get("event_type"));
                assertThat(e.get("phase").asString()).isEqualTo(row.get("phase"));
                assertThat(e.get("title").asString()).isEqualTo(row.get("title"));
                assertThat(text(e.get("hindsight"))).isEqualTo(text(row.get("hindsight")));
                assertThat(e.get("source").asString()).isEqualTo(row.get("source"));
            }
            turnNo++;
        }
        assertThat(oracle.values().stream().anyMatch(rows -> rows.get(0).get("title") != null))
                .as("the real sample puts at least one event on the path").isTrue();
    }

    private static List<Integer> formationOf(JsonNode f) {
        return List.of(f.get("goalkeeper").asInt(), f.get("defender").asInt(), f.get("midfielder").asInt(),
                f.get("forward").asInt());
    }

    @Test
    @Order(8)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 턴마다_대사_17개가_MATCH다() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT turn_no, attempt_no, count(*) AS n, count(*) FILTER (WHERE status = 'MATCH') AS matched
                FROM reconciliation_log WHERE season_id = ? GROUP BY turn_no, attempt_no ORDER BY turn_no, attempt_no""", season);
        assertThat(rows).as("the turn-0 manual bundle and one attempt per turn, nothing else yet").hasSize(TURNS + 1);
        Map<String, Object> manual = rows.get(0);
        assertThat(((Number) manual.get("turn_no")).intValue()).isZero();
        assertThat(((Number) manual.get("n")).intValue()).isEqualTo(15);
        assertThat(((Number) manual.get("matched")).intValue()).isEqualTo(15);
        int turn = 1;
        for (Map<String, Object> row : rows.subList(1, rows.size())) {
            assertThat(((Number) row.get("turn_no")).intValue()).isEqualTo(turn++);
            assertThat(((Number) row.get("n")).intValue()).isEqualTo(17);
            assertThat(((Number) row.get("matched")).intValue()).isEqualTo(17);
        }
    }

    @Test
    @Order(9)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void SQL_최종_판정_90이_PASS다() throws Exception {
        Reply manual = http.post("/api/v1/dev/seasons/" + season + "/reconciliation", "{}", dev());
        assertThat(manual.status()).as(manual.body()).isEqualTo(200);
        assertThat(manual.json().get("rules").size()).isEqualTo(15);
        assertThat(manual.json().get("mismatchRules").asInt()).isZero();

        SqlGoldenHarness.ScriptResult verdict = harness.run(apiDb, SqlGoldenScenario.fullRunCheck());
        assertThat(verdict.process().exitCode()).as("90 stderr: " + tail(verdict.process().stderr())
                + " / stdout: " + tail(verdict.process().stdout())).isZero();
        harness.assertCheckPassed(verdict, "p0_21_check");
    }

    @Test
    @Order(10)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 시즌_중_응답에_실제_달과_시드가_없다() {
        List<String> months = jdbc.queryForList("""
                SELECT to_char(mb.month_start, 'YYYY-MM') FROM season_path sp JOIN month_block mb ON mb.id = sp.month_block_id
                WHERE sp.season_id = ?""", String.class, season);
        List<Long> blocks = jdbc.queryForList("SELECT month_block_id FROM season_path WHERE season_id = ?", Long.class, season);
        assertThat(months).hasSize(TURNS);
        // event titles are real names too; titles under 4 characters are skipped (too likely to match common words)
        List<String> titles = ReviewApiSupport.oracle(jdbc, season).values().stream().flatMap(List::stream)
                .map(row -> (String) row.get("title")).filter(t -> t != null && t.length() >= 4).distinct().toList();
        assertThat(titles).as("the path carries event titles to hide").isNotEmpty();
        List<Reply> inSeason = http.captured().subList(0, finishedAtReply);
        assertThat(inSeason.size()).isGreaterThan(40);
        for (Reply reply : inSeason) {
            assertThat(reply.body()).as(reply.toString()).doesNotContain(Long.toString(SEED))
                    .doesNotContain("month_block").doesNotContain("monthBlock");
            for (String month : months) {
                assertThat(reply.body()).as(reply + " month " + month).doesNotContain(month);
            }
            for (String title : titles) {
                assertThat(reply.body()).as(reply + " event title " + title).doesNotContain(title);
            }
        }
        assertThat(blocks).as("the path exists to hide").hasSize(TURNS);
    }

    // ---------------------------------------------------------------- helpers

    private static String tail(String text) {
        return text.length() <= 1500 ? text : text.substring(text.length() - 1500);
    }

    private String url(String suffix) {
        return "/api/v1/seasons/" + season + suffix;
    }

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String text(JsonNode node) {
        return node.isNull() ? null : node.asString();
    }

    private static String buyBody(int turn, long assetId, int quantity, String declared) {
        return "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + assetId + ", \"quantity\": " + quantity
                + ", \"declaredPosition\": \"" + declared + "\"}";
    }

    private static Map<Long, BigDecimal> prices(Reply reply) {
        Map<Long, BigDecimal> out = new TreeMap<>();
        reply.json().get("prices").forEach(p -> out.put(p.get("assetId").asLong(), money(p.get("price"))));
        return out;
    }

    /** The first {@code count} pool assets (asset id order) whose system position is {@code systemPosition}; share is a percent. */
    private List<long[]> pick(String systemPosition, int count, int percent) {
        List<long[]> out = new ArrayList<>();
        for (JsonNode a : pool) {
            if (out.size() < count && systemPosition.equals(a.get("systemPosition").asString())) {
                out.add(new long[] {a.get("assetId").asLong(), percent});
            }
        }
        assertThat(out).as("pool has " + count + " " + systemPosition).hasSize(count);
        return out;
    }

    /** One of each forward kind first, then any other forward, up to {@code count}. */
    private List<long[]> pickForwards(int count, int percent) {
        List<long[]> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (JsonNode a : pool) {
            String kind = a.get("systemPosition").asString();
            if (out.size() < count && kind.startsWith("FORWARD") && !seen.contains(kind)) {
                seen.add(kind);
                out.add(new long[] {a.get("assetId").asLong(), percent});
            }
        }
        for (JsonNode a : pool) {
            long id = a.get("assetId").asLong();
            if (out.size() < count && a.get("systemPosition").asString().startsWith("FORWARD")
                    && out.stream().noneMatch(l -> l[0] == id)) {
                out.add(new long[] {id, percent});
            }
        }
        assertThat(out).as("pool has " + count + " forwards").hasSize(count);
        return out;
    }

    private JsonNode holdings() {
        Reply r = http.get(url("/holdings"));
        assertThat(r.status()).isEqualTo(200);
        return r.json().get("holdings");
    }

    private long firstHeldAsset() {
        return holdings().get(0).get("assetId").asLong();
    }

    private int currentTurn() {
        return http.get("/api/v1/seasons/" + season).json().get("currentTurnNo").asInt();
    }

    /** Advances one turn at a time until the season's current turn is {@code target}; every step must be a 201. */
    private void advanceTo(int target) {
        for (int turn = currentTurn(); turn < target; turn++) {
            Reply r = http.post(url("/turns"), "{\"expectedTurnNo\": " + turn + "}");
            assertThat(r.status()).as("advance from " + turn + ": " + r.body()).isEqualTo(201);
            assertThat(r.json().get("turnNo").asInt()).isEqualTo(turn + 1);
        }
        assertThat(currentTurn()).isEqualTo(target);
    }

    private static void expectProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.toString() + " " + reply.body()).isEqualTo(status);
        assertThat(reply.isProblem()).as(reply + " is application/problem+json").isTrue();
        assertThat(reply.code()).as(reply.toString()).isEqualTo(code);
        assertThat(reply.json().get("status").asInt()).as(reply + " body status").isEqualTo(status);
        assertThat(reply.json().get("detail").asString()).as(reply + " detail").isNotBlank();
        assertThat(reply.json().get("instance").asString()).as(reply + " instance").isNotBlank();
    }

    /** Row counts of every table that hangs on the season, plus the season and account rows. */
    private Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String table : new String[] {"turn", "turn_price", "reconciliation_log", "holding", "cash_ledger", "lineup",
                "orders", "formation_change"}) {
            out.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class, season));
        }
        out.put("execution", jdbc.queryForObject(
                "SELECT count(*) FROM execution WHERE order_id IN (SELECT id FROM orders WHERE season_id = ?)", Long.class, season));
        out.put("season", jdbc.queryForObject(
                "SELECT status || ':' || current_turn_no || ':' || version FROM season WHERE id = ?", String.class, season));
        out.put("account", jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, season));
        return out;
    }
}

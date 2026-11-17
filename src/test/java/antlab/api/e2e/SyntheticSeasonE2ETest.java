package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.report.AnalysisAssertions;
import antlab.api.report.ReportApiSupport;
import antlab.scoring.RepresentativeTable;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.season.SeedGenerator;
import antlab.support.E2eHttp;
import antlab.support.E2eHttp.Reply;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.PsqlScriptRunner;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * One whole season over a real port on the tracked fake fixture ({@code fixtures/synthetic/season_fake.sql}). Always on: it
 * needs Docker for the PostgreSQL container but no sample files, so it runs in CI where the SQL golden tier is skipped.
 *
 * <p>The client signs up, builds an 8-line team, advances 12 turns (a partial sell and a formation change with a bound buy at
 * turn 5) and reads the report and the review. A few prices and the cash after the first buy are computed by hand below;
 * the reconciliation log must hold 17 MATCH rows for every turn. The steps share one season, so the methods are ordered.
 * The fixture is loaded once and never removed: the context has its own database in the shared container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyntheticSeasonE2ETest {

    private static final long SEED = 4815162342L;
    private static final int TURNS = 12;
    private static final String EMAIL = "synthetic@example.invalid";
    private static final String PASSWORD = "synthetic-season-pass";
    private static final String CHANGE_REASON = "FAKE reason: less defence, more attack";

    /** Team lines: ticker, quantity, declared position. One goalkeeper, two defenders, two midfielders, three forwards. */
    private static final String[][] TEAM = {
        {"ZZ0001", "300", "GOALKEEPER"},
        {"ZZ0003", "150", "DEFENDER"},
        {"ZZUS04", "30", "DEFENDER"},
        {"ZZ0005", "60", "MIDFIELDER"},
        {"ZZUS01", "20", "MIDFIELDER"},
        {"ZZ0006", "75", "FORWARD_TARGET"},
        {"ZZUS02", "10", "FORWARD_BREAKOUT"},
        {"ZZUS03", "40", "FORWARD_DEFENSIVE"},
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedSeed {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> SEED;
        }
    }

    /**
     * The shipped table names real tickers that the fake fixture does not hold. This one keeps the market pair (the fixture holds
     * its two fake proxies) and points the bond, gold and growth slots at fake assets, so all four baselines can be built. The
     * bond candidate list has an absent first entry and a US second one, which makes the bond leg a substitute.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeRepresentatives {
        @Bean
        @Primary
        RepresentativeTable fakeRepresentativeTable() {
            return new RepresentativeTable("rep-v1", new AssetKey("KR", "069500"), new AssetKey("US", "SPY"),
                    List.of(new AssetKey("KR", "ZZ0099"), new AssetKey("US", "ZZUS04")), new AssetKey("KR", "ZZ0002"),
                    new AssetKey("US", "ZZUS02"));
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    PostgreSQLContainer container;

    private E2eHttp http;
    private long season;
    private final Map<String, Long> assetId = new LinkedHashMap<>();

    @BeforeAll
    void prepare() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/synthetic/season_fake.sql")).execute(dataSource);
    }

    // ---------------------------------------------------------------- play

    @Test
    @Order(1)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 가입하고_로그인한다() {
        Reply signup = new E2eHttp(port).post("/api/v1/auth/signup",
                "{\"email\": \"" + EMAIL + "\", \"name\": \"FAKE Manager\", \"password\": \"" + PASSWORD + "\"}");
        assertThat(signup.status()).as(signup.body()).isEqualTo(201);
        Reply login = new E2eHttp(port).post("/api/v1/auth/login",
                "{\"email\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}");
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        http = new E2eHttp(port, Map.of("Authorization", "Bearer " + login.json().get("accessToken").asString()));
    }

    @Test
    @Order(2)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 풀은_가짜_종목_열두_개이고_모든_포지션을_갖는다() {
        Reply pool = http.get("/api/v1/pool");
        assertThat(pool.status()).as(pool.body()).isEqualTo(200);
        JsonNode assets = pool.json().get("assets");
        assertThat(assets.size()).isEqualTo(12);
        Set<String> positions = new TreeSet<>();
        Set<String> markets = new TreeSet<>();
        for (JsonNode a : assets) {
            assetId.put(a.get("ticker").asString(), a.get("assetId").asLong());
            positions.add(a.get("systemPosition").asString());
            markets.add(a.get("market").asString());
            assertThat(a.get("name").asString()).startsWith("FAKE ");
        }
        assertThat(positions).containsExactlyInAnyOrder("GOALKEEPER", "DEFENDER", "MIDFIELDER", "FORWARD_TARGET",
                "FORWARD_BREAKOUT", "FORWARD_DEFENSIVE");
        assertThat(markets).containsExactlyInAnyOrder("KR", "US");
    }

    @Test
    @Order(3)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 시즌을_시작하고_8줄_팀을_산다() {
        Reply start = http.post("/api/v1/seasons", "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 30, \"midfielder\": 30, \"forward\": 30}, \"pathLength\": " + TURNS + "}");
        assertThat(start.status()).as(start.body()).isEqualTo(201);
        season = start.json().get("id").asLong();
        assertThat(start.json().get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(start.json().get("currentTurnNo").asInt()).isZero();
        assertThat(money(start.json().get("cashBalance"))).isEqualByComparingTo("30000000");

        // Turn-0 prices by hand: base price x base fx rate.
        Map<String, BigDecimal> turn0 = prices(0);
        assertThat(turn0.get("ZZ0001")).isEqualByComparingTo("10000");
        assertThat(turn0.get("ZZUS01")).isEqualByComparingTo("130000");

        boolean first = true;
        for (String[] line : TEAM) {
            Reply order = http.post(url("/orders"), buyBody(0, assetId.get(line[0]), Integer.parseInt(line[1]), line[2]));
            assertThat(order.status()).as(line[0] + " " + order.body()).isEqualTo(201);
            if (first) {
                // 300 x 10000 = 3,000,000; fee 0.015% = 450; cash 30,000,000 - 3,000,000 - 450
                assertThat(money(order.json().get("notional"))).isEqualByComparingTo("3000000");
                assertThat(money(order.json().get("fee"))).isEqualByComparingTo("450");
                assertThat(money(order.json().get("cashBalance"))).isEqualByComparingTo("26999550");
                first = false;
            }
        }
        Reply holdings = http.get(url("/holdings"));
        assertThat(holdings.status()).isEqualTo(200);
        assertThat(holdings.json().get("holdings").size()).isEqualTo(TEAM.length);
        assertThat(money(holdings.json().get("cashBalance")).signum()).isPositive();
    }

    @Test
    @Order(4)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 두_번째_턴까지_넘기면_손으로_계산한_가격과_같다() {
        advanceTo(2);
        // KR, no fx: 10000 x 1.05 = 10500; x 0.97 = 10185
        // US: 130000 x (1.02 x 1.01) = 133926; x (0.99 x 0.98) -> 130000 x 1.0302 x 0.9702 = 129935.0052, stored 129935
        Map<String, BigDecimal> turn1 = prices(1);
        Map<String, BigDecimal> turn2 = prices(2);
        assertThat(turn1.get("ZZ0001")).isEqualByComparingTo("10500");
        assertThat(turn2.get("ZZ0001")).isEqualByComparingTo("10185");
        assertThat(turn1.get("ZZUS01")).isEqualByComparingTo("133926");
        assertThat(turn2.get("ZZUS01")).isEqualByComparingTo("129935");
    }

    @Test
    @Order(5)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 시즌_중_거절은_문제_본문이고_상태를_바꾸지_않는다() {
        Map<String, Object> before = state();
        Reply overCash = http.post(url("/orders"), buyBody(2, assetId.get("ZZ0001"), 2_000_000_000, "GOALKEEPER"));
        expectProblem(overCash, 422, "INSUFFICIENT_CASH");
        expectProblem(http.post(url("/turns"), "{\"expectedTurnNo\": 1}"), 409, "TURN_MISMATCH");
        expectProblem(http.get(url("/prices?turnNo=3")), 409, "TURN_NOT_REACHED");
        expectProblem(http.get(url("/report")), 409, "SEASON_NOT_FINISHED");
        expectProblem(http.get(url("/report/analysis")), 409, "SEASON_NOT_FINISHED");
        expectProblem(http.get(url("/review")), 409, "SEASON_NOT_FINISHED");
        assertThat(state()).as("rejections change nothing").isEqualTo(before);
    }

    @Test
    @Order(6)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 다섯_번째_턴에서_매도_전술_변경_묶인_매수를_한다() {
        advanceTo(5);
        Reply sell = http.post(url("/orders"), "{\"expectedTurnNo\": 5, \"side\": \"SELL\", \"assetId\": "
                + assetId.get("ZZ0003") + ", \"quantity\": 50}");
        assertThat(sell.status()).as(sell.body()).isEqualTo(201);
        assertThat(sell.json().get("formationChangeId").isNull()).as("before the change").isTrue();
        assertThat(sell.json().get("holdingQuantity").asInt()).isEqualTo(100);

        Reply change = http.post(url("/formation-changes"), "{\"expectedTurnNo\": 5, \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 20, \"midfielder\": 30, \"forward\": 40}, \"reason\": \"" + CHANGE_REASON + "\"}");
        assertThat(change.status()).as(change.body()).isEqualTo(201);

        Reply buy = http.post(url("/orders"), buyBody(5, assetId.get("ZZUS03"), 5, "FORWARD_DEFENSIVE"));
        assertThat(buy.status()).as(buy.body()).isEqualTo(201);
        List<Long> changeIds = jdbc.queryForList("SELECT id FROM formation_change WHERE season_id = ?", Long.class, season);
        assertThat(changeIds).as("the season's single formation change").hasSize(1);
        assertThat(buy.json().get("formationChangeId").asLong()).isEqualTo(changeIds.get(0));

        // An order that starts from a feed item shown at turn 5 or before: an event card names no asset, so any held asset fits.
        Map<String, Object> shown = jdbc.queryForMap("SELECT turn_no, seq FROM turn_feed WHERE season_id = ? AND turn_no <= 5"
                + " AND asset_id IS NULL ORDER BY turn_no DESC, seq LIMIT 1", season);
        String fed = "{\"expectedTurnNo\": 5, \"side\": \"BUY\", \"assetId\": " + assetId.get("ZZ0001") + ", \"quantity\": 1,"
                + " \"source\": {\"kind\": \"FEED\", \"feedTurnNo\": " + shown.get("turn_no") + ", \"feedSeq\": " + shown.get("seq")
                + "}}";
        Reply fedBuy = http.post(url("/orders"), fed);
        assertThat(fedBuy.status()).as(fedBuy.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ? AND source = 'FEED' AND turn_feed_id IS NOT NULL",
                Long.class, season)).isEqualTo(1);
    }

    @Test
    @Order(7)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 끝까지_넘기면_끝난_시즌이다() {
        advanceTo(TURNS);
        Reply one = http.get("/api/v1/seasons/" + season);
        assertThat(one.json().get("status").asString()).isEqualTo("FINISHED");
        assertThat(one.json().get("currentTurnNo").asInt()).isEqualTo(TURNS);
    }

    // ---------------------------------------------------------------- checks

    @Test
    @Order(8)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 성적표와_복기를_읽는다() {
        Reply report = http.get(url("/report"));
        assertThat(report.status()).as(report.body()).isEqualTo(200);
        JsonNode r = report.json();
        assertThat(r.get("isFinal").asBoolean()).isTrue();
        assertThat(money(r.get("nudgeShare"))).as("the FEED order counts as encouraged, with no UPDATE of orders").isPositive();
        assertThat(r.get("formationChanges").asInt()).isEqualTo(1);
        assertThat(money(r.get("initialCash"))).isEqualByComparingTo("30000000");
        for (String key : ReportApiSupport.MONEY) {
            assertThat(r.get(key).isString()).as(key + " is a JSON string").isTrue();
            assertThat(r.get(key).asString()).as(key).matches(ReportApiSupport.MONEY_FORMAT);
        }
        for (String key : ReportApiSupport.RATIO) {
            if (!r.get(key).isNull()) {
                assertThat(r.get(key).asString()).as(key).matches(ReportApiSupport.RATIO_FORMAT);
            }
        }

        Reply review = http.get(url("/review"));
        assertThat(review.status()).as(review.body()).isEqualTo(200);
        JsonNode body = review.json();
        assertThat(body.get("seasonStatus").asString()).isEqualTo("FINISHED");
        assertThat(body.get("pathLength").asInt()).isEqualTo(TURNS);
        assertThat(body.get("seed").asString()).isEqualTo(Long.toString(SEED));
        assertThat(body.get("turns").size()).isEqualTo(TURNS);
        assertThat(body.get("formationChanges").size()).isEqualTo(1);
        assertThat(body.get("formationChanges").get(0).get("reason").asString()).isEqualTo(CHANGE_REASON);
        int firstMonthEvents = 0;
        int turnNo = 1;
        for (JsonNode turn : body.get("turns")) {
            assertThat(turn.get("turnNo").asInt()).isEqualTo(turnNo++);
            assertThat(turn.get("yearMonth").asString()).matches("\\d{4}-\\d{2}");
            firstMonthEvents += turn.get("events").size();
        }
        assertThat(firstMonthEvents).as("the fixture puts one event on the first month of each block").isEqualTo(2);
    }

    @Test
    @Order(9)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 턴마다_대사_17개가_MATCH다() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT turn_no, attempt_no, count(*) AS n, count(*) FILTER (WHERE status = 'MATCH') AS matched
                FROM reconciliation_log WHERE season_id = ? GROUP BY turn_no, attempt_no ORDER BY turn_no, attempt_no""", season);
        assertThat(rows).as("one attempt per turn, nothing else").hasSize(TURNS);
        int turn = 1;
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("turn_no")).intValue()).isEqualTo(turn++);
            assertThat(((Number) row.get("n")).intValue()).isEqualTo(17);
            assertThat(((Number) row.get("matched")).intValue()).isEqualTo(17);
        }
    }

    @Test
    @Order(10)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 턴마다_피드가_저장되고_조회가_같은_행을_돌려준다() {
        int total = 0;
        for (int turn = 1; turn <= TURNS; turn++) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT seq, asset_id, body FROM turn_feed WHERE season_id = ? AND turn_no = ? ORDER BY seq", season, turn);
            assertThat(rows.size()).as("turn " + turn).isLessThanOrEqualTo(15);
            Reply reply = http.get(url("/turns/" + turn));
            assertThat(reply.status()).as(reply.body()).isEqualTo(200);
            JsonNode feed = reply.json().get("feed");
            assertThat(feed.size()).as("turn " + turn).isEqualTo(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                assertThat(((Number) rows.get(i).get("seq")).intValue()).as("no gap in seq").isEqualTo(i + 1);
                assertThat(feed.get(i).get("turnNo").asInt()).isEqualTo(turn);
                assertThat(feed.get(i).get("seq").asInt()).isEqualTo(i + 1);
                assertThat(feed.get(i).get("body").asString()).isEqualTo(rows.get(i).get("body"));
                Object asset = rows.get(i).get("asset_id");
                assertThat(feed.get(i).get("assetId").isNull()).isEqualTo(asset == null);
            }
            total += rows.size();
        }
        assertThat(total).as("a season of twelve fake assets always has something to say").isPositive();
        // The fixture puts one event in the first month of each block, so every such month of the path has an event card.
        Long events = jdbc.queryForObject("SELECT count(*) FROM season_path sp JOIN month_event me ON me.month_block_id = "
                + "sp.month_block_id WHERE sp.season_id = ?", Long.class, season);
        assertThat(events).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ? AND kind = 'EVENT'", Long.class, season))
                .isEqualTo(events);
    }

    /**
     * Java reader + generator = SQL on the very database the API wrote: the SQL reference ({@code db/24_turn_feed_def.sql}) runs in
     * a psql session inside the same container and {@code tf_regen_matches} must hold for every turn. Nothing is written; the
     * definitions live in the session only. It is not vacuous: stored rows must exist and at least one turn must have rows.
     */
    @Test
    @Order(11)
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void 저장된_피드는_SQL_기준_생성기가_같은_입력으로_다시_만든_것과_턴마다_같다() throws Exception {
        PsqlScriptRunner runner = new PsqlScriptRunner(container);
        for (String file : new String[] {"db/24_turn_feed_def.sql", "feed/templates.csv", "feed/variables.csv"}) {
            runner.putFile(file, Path.of(file));
        }
        String query = "SELECT count(*) AS turns, count(*) FILTER (WHERE m IS TRUE) AS matched,"
                + " count(*) FILTER (WHERE n > 0) AS turns_with_rows, coalesce(sum(n), 0) AS stored_rows FROM ("
                + "SELECT pg_temp.tf_regen_matches(" + season + ", t::smallint) AS m, (SELECT count(*) FROM turn_feed"
                + " WHERE season_id = " + season + " AND turn_no = t) AS n FROM generate_series(1, " + TURNS + ") AS t) x";
        // the API writes to this context's own database, not the container default one
        String apiDb = jdbc.queryForObject("SELECT current_database()", String.class);
        PsqlScriptRunner.Result result = runner.runDefinitionQuery(apiDb, "db/24_turn_feed_def.sql", query,
                Duration.ofMinutes(2));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        String[] row = result.stdout().strip().split("\\R")[1].split(",");
        assertThat(Integer.parseInt(row[0])).as("turns compared").isEqualTo(TURNS);
        assertThat(Integer.parseInt(row[1])).as("turns where stored = regenerated").isEqualTo(TURNS);
        assertThat(Integer.parseInt(row[2])).as("turns with at least one stored row").isPositive();
        assertThat(Integer.parseInt(row[3])).as("stored rows").isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM turn_feed WHERE season_id = ?", Integer.class, season));
        System.out.println("[sql-parity] e2e season turns=" + row[0] + " matched=" + row[1] + " turnsWithRows=" + row[2]
                + " storedRows=" + row[3]);
    }

    // ---------------------------------------------------------------- helpers

    private String url(String suffix) {
        return "/api/v1/seasons/" + season + suffix;
    }

    /**
     * All four baselines on the fake table, after the report. The market baseline and the trading amount are computed by
     * the report in another class, so equality with them is the check; the won identity of the three-way split is exact.
     */
    @Test
    @Order(12)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 분석은_성적표와_맞고_네_기준선과_3분해가_정확히_더해진다() {
        Reply report = http.get(url("/report"));
        Reply analysis = http.get(url("/report/analysis"));
        assertThat(analysis.status()).as(analysis.body()).isEqualTo(200);
        JsonNode a = analysis.json();
        AnalysisAssertions.assertConsistent(a, report.json(), 4);
        for (JsonNode baseline : a.get("baselines")) {
            System.out.println("[M1] " + baseline.get("key").asString() + " rebalances=" + baseline.get("rebalanceCount").asInt()
                    + " forced=" + baseline.get("forcedChangeCount").asInt() + " bandNoFill="
                    + baseline.get("bandNoFillCount").asInt() + " fee=" + baseline.get("totalFee").asString());
        }
        JsonNode sixtyForty = a.get("baselines").get(1);
        assertThat(sixtyForty.get("legs").size()).isEqualTo(3);
        assertThat(sixtyForty.get("legs").get(2).get("asset").asString()).isEqualTo("US:ZZUS04");
        assertThat(sixtyForty.get("legs").get(2).get("slot").asString()).isEqualTo("BOND");
        assertThat(sixtyForty.get("bondLegSubstituted").asBoolean()).as("a US ETF stands in for the domestic bond").isTrue();
        JsonNode follow = a.get("baselines").get(2);
        JsonNode fixed = a.get("baselines").get(3);
        assertThat(follow.get("forcedChangeCount").asInt()).as("the one formation change forces one rebalance").isEqualTo(1);
        assertThat(fixed.get("forcedChangeCount").asInt()).isZero();
        assertThat(AnalysisAssertions.won(follow.get("forcedChangeFee")).signum()).as("the forced rebalance costs fees").isPositive();
        assertThat(AnalysisAssertions.won(fixed.get("forcedChangeFee")).signum()).isZero();
        assertThat(follow.get("legs").size()).isEqualTo(fixed.get("legs").size()).isEqualTo(5);
        assertThat(follow.get("turnValues").get(0).asString()).as("both formation baselines start alike")
                .isEqualTo(fixed.get("turnValues").get(0).asString());
        assertThat(a.get("discipline").get("formationChanges").asInt()).isEqualTo(1);
        assertThat(a.get("discipline").get("tradeNotional").asString()).isEqualTo(report.json().get("tradeNotional").asString());
        // The turn-5 sell of 50 shares of ZZ0003 is the only sell; it fills at that turn's price.
        BigDecimal sold = prices(5).get("ZZ0003").multiply(BigDecimal.valueOf(50));
        assertThat(new BigDecimal(a.get("discipline").get("sellNotional").asString())).isEqualByComparingTo(sold);
    }

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    private static String buyBody(int turn, long assetId, int quantity, String declared) {
        return "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + assetId + ", \"quantity\": " + quantity
                + ", \"declaredPosition\": \"" + declared + "\"}";
    }

    /** Prices of one turn by ticker. */
    private Map<String, BigDecimal> prices(int turnNo) {
        Reply reply = http.get(url("/prices?turnNo=" + turnNo));
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        Map<Long, String> tickerById = new LinkedHashMap<>();
        assetId.forEach((ticker, id) -> tickerById.put(id, ticker));
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        reply.json().get("prices").forEach(p -> out.put(tickerById.get(p.get("assetId").asLong()), money(p.get("price"))));
        assertThat(out).as("every pool asset has a price").hasSize(assetId.size());
        return out;
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
        assertThat(reply.status()).as(reply + " " + reply.body()).isEqualTo(status);
        assertThat(reply.isProblem()).as(reply + " is application/problem+json").isTrue();
        assertThat(reply.code()).as(reply.toString()).isEqualTo(code);
    }

    /** Row counts of every table that hangs on the season, plus the season and account rows. */
    private Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String table : new String[] {"turn", "turn_price", "reconciliation_log", "holding", "cash_ledger", "lineup",
                "orders", "formation_change"}) {
            out.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class, season));
        }
        out.put("season", jdbc.queryForObject(
                "SELECT status || ':' || current_turn_no || ':' || version FROM season WHERE id = ?", String.class, season));
        out.put("account", jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, season));
        return out;
    }
}

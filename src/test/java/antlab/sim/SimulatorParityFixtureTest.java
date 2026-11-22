package antlab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.SeasonSignalReader;
import antlab.scoring.TurnSignals;
import antlab.season.SeedGenerator;
import antlab.sim.ParityScript.Arena;
import antlab.sim.ParityScript.FeedRef;
import antlab.sim.ParityScript.Reply;
import antlab.sim.ParityScript.Spec;
import antlab.sim.SimMoneyVectorsTest.SplitMix64;
import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Java is the oracle of the season simulator. A seeded script plays 20 seasons over the REST API on a synthetic history
 * (Testcontainers PostgreSQL 17, a fake engine for the percentile counts) and the test writes (with
 * {@code ANTLAB_PARITY_WRITE=1}) or compares the tracked fixture {@code seasons-v1.json}: season inputs, the decisions the
 * script sent with the verdict it meant each unit to get, and everything Java stored or answered (executions, ledger,
 * holdings and lineup spans, turn prices and snapshots, the 38-field report, the score row, the percentile request).
 * The Python test replays the same seasons offline and must reproduce every value.
 *
 * <p>Seasons 3, 12 and 19 start with 100,000,000 (the shipped value); the others with the test profile's 30,000,000. The
 * two cash values need two Spring contexts, so the 100 M seasons run in the nested class and each class writes only its own
 * seasons into the shared file.
 *
 * <p>Regenerate after an intended rule change: {@code ANTLAB_PARITY_WRITE=1 ./gradlew test --tests antlab.sim.SimulatorParityFixtureTest}.
 */
@IntegrationTest
@Import(SimulatorParityFixtureTest.Seeds.Config.class)
class SimulatorParityFixtureTest {

    static final long MASTER_SEED = 0x5EED_0301_0D01L;
    static final String BASE_DATE = "2026-01-02";
    static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    static final String PERCENTILES = "/v1/percentiles";
    static final FakeEngineServer ENGINE = FakeEngineServer.start();
    static final Set<Integer> HUNDRED_MILLION = Set.of(3, 12, 19);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ENGINE::close));
    }

    /** index, path length, abandon turn (-1 = plays to the end) */
    static final int[][] PLAN = {
        {1, 12, -1}, {2, 12, 2}, {3, 12, -1}, {4, 12, -1}, {5, 12, -1}, {6, 12, -1}, {7, 60, -1}, {8, 12, -1},
        {9, 12, -1}, {10, 12, -1}, {11, 24, -1}, {12, 24, -1}, {13, 24, -1}, {14, 24, -1}, {15, 24, -1},
        {16, 24, 9}, {17, 12, -1}, {18, 24, -1}, {19, 60, -1}, {20, 24, -1}};
    static final String[] RISK_LIMITS = {"-0.10", "-0.20", "-0.30", "-0.40"};
    static final int[][] START_FORMATIONS = {{10, 30, 30, 30}, {20, 30, 30, 20}, {15, 25, 35, 25}, {30, 30, 20, 20}};

    /** The next season seed, set by the harness right before it starts a season. */
    static final class Seeds {
        final AtomicLong next = new AtomicLong();

        @TestConfiguration(proxyBeanMethods = false)
        static class Config {
            @Bean
            Seeds seeds() {
                return new Seeds();
            }

            @Bean
            @Primary
            SeedGenerator parityFixedSeed(Seeds seeds) {
                return seeds.next::get;
            }
        }
    }

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonSignalReader signals;
    @Autowired
    Seeds seeds;

    @Test
    @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    void thirtyMillionSeasonsMatchTheFixture() throws IOException {
        new Harness(mvc, jdbc, signals, seeds).run(false);
    }

    @Nested
    @TestPropertySource(properties = "antlab.season.initial-cash=100000000")
    class HundredMillion {

        @Autowired
        MockMvcTester mvc;
        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        SeasonSignalReader signals;
        @Autowired
        Seeds seeds;

        @Test
        @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
        void hundredMillionSeasonsMatchTheFixture() throws IOException {
            new Harness(mvc, jdbc, signals, seeds).run(true);
        }
    }

    // ---- the harness --------------------------------------------------------------------------------------------

    static final class Harness {

        private final MockMvcTester mvc;
        private final JdbcTemplate jdbc;
        private final SeasonSignalReader signalReader;
        private final Seeds seeds;
        private final JsonMapper mapper = JsonMapper.shared();
        private final Map<Long, String> keyById = new HashMap<>();
        private final Map<String, Long> idByKey = new HashMap<>();
        private final int pathCount = 2000;

        Harness(MockMvcTester mvc, JdbcTemplate jdbc, SeasonSignalReader signals, Seeds seeds) {
            this.mvc = mvc;
            this.jdbc = jdbc;
            this.signalReader = signals;
            this.seeds = seeds;
        }

        void run(boolean hundredMillion) throws IOException {
            new ResourceDatabasePopulator(new ClassPathResource("sim/parity/history-v1.sql"))
                    .execute(jdbc.getDataSource());
            jdbc.query("SELECT id, market || ':' || ticker AS k FROM asset", rs -> {
                keyById.put(rs.getLong("id"), rs.getString("k"));
                idByKey.put(rs.getString("k"), rs.getLong("id"));
            });
            SplitMix64 master = new SplitMix64(MASTER_SEED);
            Map<Integer, ObjectNode> mine = new TreeMap<>();
            for (int[] row : PLAN) {
                int index = row[0];
                long seed = nextLong(master);
                long scriptSeed = nextLong(master);
                if (HUNDRED_MILLION.contains(index) != hundredMillion) {
                    continue;
                }
                mine.put(index, season(new Spec(index, row[1], row[2], scriptSeed), seed));
            }
            SimFixtureWriter.writeOrCompare(SimFixtureWriter.header(MASTER_SEED, pathCount, BASE_DATE), mine);
            assertThat(mine).hasSize(hundredMillion ? HUNDRED_MILLION.size() : SimFixtureWriter.SEASONS - HUNDRED_MILLION.size());
        }

        private static long nextLong(SplitMix64 rng) {
            return rng.next();
        }

        // ---- one season ---------------------------------------------------------------------------------------

        private ObjectNode season(Spec spec, long seed) {
            seeds.next.set(seed);
            int[] start = START_FORMATIONS[spec.index() % START_FORMATIONS.length];
            String risk = RISK_LIMITS[spec.index() % RISK_LIMITS.length];
            MockHttpServletResponse started = post("/api/v1/seasons", "{\"riskLimit\":\"" + risk + "\",\"formation\":{\"goalkeeper\":"
                    + start[0] + ",\"defender\":" + start[1] + ",\"midfielder\":" + start[2] + ",\"forward\":" + start[3]
                    + "},\"pathLength\":" + spec.pathLength() + "}");
            assertThat(started.getStatus()).as("season %d start", spec.index()).isEqualTo(201);
            long seasonId = body(started).get("id").asLong();

            SeasonArena arena = new SeasonArena(seasonId, spec);
            ObjectNode decisions = new ParityScript(spec, arena).play();
            arena.finish();

            ObjectNode out = mapper.createObjectNode();
            out.put("index", spec.index());
            out.put("seed", Long.toString(seed));
            out.set("inputs", inputs(seasonId, spec));
            out.set("decisions", decisions);
            ArrayNode signalsOut = signalsOf(seasonId, spec.pathLength());
            out.set("signals", signalsOut);
            ObjectNode counts = counts(spec);
            out.set("counts", counts);
            out.set("java", java(seasonId, spec, arena, counts));
            return out;
        }

        // ---- reads (inputs) -----------------------------------------------------------------------------------

        private ObjectNode inputs(long seasonId, Spec spec) {
            ObjectNode in = mapper.createObjectNode();
            ObjectNode settings = in.putObject("settings");
            jdbc.query("SELECT * FROM season WHERE id = ?", rs -> {
                BigDecimal cash = rs.getBigDecimal("initial_cash");
                assertThat(cash.remainder(BigDecimal.valueOf(2)).signum()).as("an odd initial cash is refused by the baseline").isZero();
                assertThat(rs.getDate("base_date").toString()).as("season base date").isEqualTo(BASE_DATE);
                settings.put("initialCash", SimFixtureWriter.plain(cash));
                settings.put("feeRateKr", SimFixtureWriter.plain(rs.getBigDecimal("fee_rate_kr")));
                settings.put("feeRateUs", SimFixtureWriter.plain(rs.getBigDecimal("fee_rate_us")));
                settings.put("fxSpreadRate", SimFixtureWriter.plain(rs.getBigDecimal("fx_spread_rate")));
                settings.put("riskLimit", SimFixtureWriter.plain(rs.getBigDecimal("risk_limit")));
                ObjectNode f = settings.putObject("startFormation");
                f.put("goalkeeper", rs.getInt("start_goalkeeper"));
                f.put("defender", rs.getInt("start_defender"));
                f.put("midfielder", rs.getInt("start_midfielder"));
                f.put("forward", rs.getInt("start_forward"));
                settings.put("pathLength", rs.getInt("path_length"));
                settings.put("memberKind", "HUMAN");
                settings.putNull("singleAssetLimit");
            }, seasonId);
            ArrayNode pool = in.putArray("pool");
            jdbc.query("""
                    SELECT a.market || ':' || a.ticker AS k, a.currency, ap.base_price, ap.base_fx_rate, ap.system_position
                    FROM turn_price tp
                    JOIN asset a ON a.id = tp.asset_id
                    JOIN season s ON s.id = tp.season_id
                    JOIN asset_position ap ON ap.asset_id = a.id AND ap.base_date = s.base_date
                    WHERE tp.season_id = ? AND tp.turn_no = 0
                    ORDER BY a.id""", rs -> {
                ObjectNode a = pool.addObject();
                a.put("asset", rs.getString("k"));
                a.put("currency", rs.getString("currency"));
                a.put("basePrice", SimFixtureWriter.plain(rs.getBigDecimal("base_price")));
                a.put("baseFxRate", SimFixtureWriter.plain(rs.getBigDecimal("base_fx_rate")));
                a.put("systemPosition", rs.getString("system_position"));
            }, seasonId);
            ArrayNode path = in.putArray("path");
            Map<Integer, ObjectNode> steps = new TreeMap<>();
            for (int t = 1; t <= spec.pathLength(); t++) {
                ObjectNode step = path.addObject();
                step.put("turn", t);
                step.putObject("localReturns");
                step.putObject("fxChange");
                steps.put(t, step);
            }
            jdbc.query("""
                    SELECT sp.turn_no, a.market || ':' || a.ticker AS k, r.local_return
                    FROM season_path sp
                    JOIN asset_month_return r ON r.month_block_id = sp.month_block_id
                    JOIN asset a ON a.id = r.asset_id
                    WHERE sp.season_id = ? AND a.id IN (SELECT asset_id FROM turn_price WHERE season_id = ? AND turn_no = 0)
                    ORDER BY sp.turn_no, a.id""", rs -> {
                ((ObjectNode) steps.get(rs.getInt("turn_no")).get("localReturns")).put(rs.getString("k"),
                        SimFixtureWriter.plain(rs.getBigDecimal("local_return")));
            }, seasonId, seasonId);
            jdbc.query("""
                    SELECT sp.turn_no, f.currency, f.fx_change
                    FROM season_path sp JOIN fx_month f ON f.month_block_id = sp.month_block_id
                    WHERE sp.season_id = ? ORDER BY sp.turn_no""", rs -> {
                ((ObjectNode) steps.get(rs.getInt("turn_no")).get("fxChange")).put(rs.getString("currency"),
                        SimFixtureWriter.plain(rs.getBigDecimal("fx_change")));
            }, seasonId);
            return in;
        }

        private ArrayNode signalsOf(long seasonId, int reportTurn) {
            ArrayNode out = mapper.createArrayNode();
            for (TurnSignals s : signalReader.read(seasonId, reportTurn)) {
                ObjectNode n = out.addObject();
                n.put("turn", s.turnNo());
                n.set("up", keys(s.up()));
                n.set("down", keys(s.down()));
                n.set("topAttention", keys(s.topAttention()));
            }
            return out;
        }

        private ArrayNode keys(Set<Long> ids) {
            ArrayNode out = mapper.createArrayNode();
            new TreeSet<>(ids.stream().map(keyById::get).toList()).forEach(out::add);
            return out;
        }

        /** The fake engine's canned answer for this season: counts that depend only on the season index. */
        private ObjectNode counts(Spec spec) {
            int i = spec.index();
            ObjectNode c = mapper.createObjectNode();
            c.put("n", pathCount);
            c.putObject("endMultiple").put("below", (i * 211) % 1900).put("equal", i % 3);
            c.putObject("sharpeDiff").put("below", (i * 97 + 300) % 1800).put("equal", 1 + i % 4);
            return c;
        }

        // ---- reads (Java outcome) -----------------------------------------------------------------------------

        private ObjectNode java(long seasonId, Spec spec, SeasonArena arena, ObjectNode counts) {
            ObjectNode j = mapper.createObjectNode();
            jdbc.query("SELECT status, abandoned_turn_no, current_turn_no FROM season WHERE id = ?", rs -> {
                j.put("status", rs.getString("status"));
                int abandoned = rs.getInt("abandoned_turn_no");
                if (rs.wasNull()) {
                    j.putNull("abandonedTurn");
                } else {
                    j.put("abandonedTurn", abandoned);
                }
                assertThat(rs.getInt("current_turn_no")).isEqualTo(spec.pathLength());
            }, seasonId);
            assertThat(j.get("status").asString()).isEqualTo("FINISHED");

            Map<Long, Integer> executionOf = new HashMap<>();
            Map<Long, Integer> changeOf = new HashMap<>();
            ArrayNode changes = j.putArray("formationChanges");
            jdbc.query("""
                    SELECT id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                           after_goalkeeper, after_defender, after_midfielder, after_forward, reason
                    FROM formation_change WHERE season_id = ? ORDER BY id""", rs -> {
                changeOf.put(rs.getLong("id"), changes.size());
                ObjectNode c = changes.addObject();
                c.put("turn", rs.getInt("turn_no"));
                c.set("before", ints(rs, "before_goalkeeper", "before_defender", "before_midfielder", "before_forward"));
                c.set("after", ints(rs, "after_goalkeeper", "after_defender", "after_midfielder", "after_forward"));
                c.put("reason", rs.getString("reason"));
            }, seasonId);

            ArrayNode executions = j.putArray("executions");
            Map<Long, Integer> orderTurn = new HashMap<>();
            jdbc.query("""
                    SELECT o.id, o.turn_no, a.market || ':' || a.ticker AS k, o.side, e.quantity, e.fill_price, e.fee,
                           e.realized_pnl, o.source, tf.turn_no AS feed_turn, tf.seq AS feed_seq, o.formation_change_id
                    FROM orders o
                    JOIN execution e ON e.order_id = o.id
                    JOIN asset a ON a.id = o.asset_id
                    LEFT JOIN turn_feed tf ON tf.id = o.turn_feed_id
                    WHERE o.season_id = ? ORDER BY o.id""", rs -> {
                executionOf.put(rs.getLong("id"), executions.size());
                orderTurn.put(rs.getLong("id"), rs.getInt("turn_no"));
                ObjectNode e = executions.addObject();
                e.put("turn", rs.getInt("turn_no"));
                e.put("asset", rs.getString("k"));
                e.put("side", rs.getString("side"));
                e.put("quantity", rs.getInt("quantity"));
                e.put("price", SimFixtureWriter.plain(rs.getBigDecimal("fill_price")));
                e.put("fee", SimFixtureWriter.plain(rs.getBigDecimal("fee")));
                e.put("realizedPnl", SimFixtureWriter.plain(rs.getBigDecimal("realized_pnl")));
                e.put("source", rs.getString("source"));
                int feedTurn = rs.getInt("feed_turn");
                if (rs.wasNull()) {
                    e.putNull("feedTurn");
                    e.putNull("feedSeq");
                } else {
                    e.put("feedTurn", feedTurn);
                    e.put("feedSeq", rs.getInt("feed_seq"));
                }
                Long change = (Long) rs.getObject("formation_change_id");
                if (change == null) {
                    e.putNull("formationChange");
                } else {
                    e.put("formationChange", changeOf.get(change));
                }
            }, seasonId);

            ArrayNode ledger = j.putArray("ledger");
            jdbc.query("SELECT type, amount, balance_after, ref_order_id FROM cash_ledger WHERE season_id = ? ORDER BY id", rs -> {
                ObjectNode r = ledger.addObject();
                r.put("type", rs.getString("type"));
                r.put("amount", SimFixtureWriter.plain(rs.getBigDecimal("amount")));
                r.put("balanceAfter", SimFixtureWriter.plain(rs.getBigDecimal("balance_after")));
                Long order = (Long) rs.getObject("ref_order_id");
                r.put("turn", order == null ? 0 : orderTurn.get(order));
                if (order == null) {
                    r.putNull("execution");
                } else {
                    r.put("execution", executionOf.get(order));
                }
            }, seasonId);

            j.set("holdingsByTurn", arena.holdings);

            ArrayNode spans = j.putArray("spans");
            jdbc.query("""
                    SELECT a.market || ':' || a.ticker AS k, l.declared_position, l.start_turn, l.end_turn
                    FROM lineup l JOIN asset a ON a.id = l.asset_id WHERE l.season_id = ? ORDER BY l.id""", rs -> {
                ObjectNode s = spans.addObject();
                s.put("asset", rs.getString("k"));
                s.put("position", rs.getString("declared_position"));
                s.put("startTurn", rs.getInt("start_turn"));
                int end = rs.getInt("end_turn");
                if (rs.wasNull()) {
                    s.putNull("endTurn");
                } else {
                    s.put("endTurn", end);
                }
            }, seasonId);

            ArrayNode prices = j.putArray("turnPrices");
            jdbc.query("""
                    SELECT tp.turn_no, a.market || ':' || a.ticker AS k, tp.price
                    FROM turn_price tp JOIN asset a ON a.id = tp.asset_id
                    WHERE tp.season_id = ? ORDER BY tp.turn_no, a.id""", rs -> {
                int turn = rs.getInt("turn_no");
                while (prices.size() <= turn) {
                    prices.addObject();
                }
                ((ObjectNode) prices.get(turn)).put(rs.getString("k"), SimFixtureWriter.plain(rs.getBigDecimal("price")));
            }, seasonId);

            ArrayNode turns = j.putArray("turns");
            jdbc.query("""
                    SELECT turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value,
                           midfielder_value, forward_value, drawdown
                    FROM turn WHERE season_id = ? ORDER BY turn_no""", rs -> {
                ObjectNode t = turns.addObject();
                t.put("turn", rs.getInt("turn_no"));
                t.put("cash", SimFixtureWriter.plain(rs.getBigDecimal("cash_balance")));
                t.put("holdingsValue", SimFixtureWriter.plain(rs.getBigDecimal("holdings_value")));
                t.put("total", SimFixtureWriter.plain(rs.getBigDecimal("total_value")));
                t.put("goalkeeper", SimFixtureWriter.plain(rs.getBigDecimal("goalkeeper_value")));
                t.put("defender", SimFixtureWriter.plain(rs.getBigDecimal("defender_value")));
                t.put("midfielder", SimFixtureWriter.plain(rs.getBigDecimal("midfielder_value")));
                t.put("forward", SimFixtureWriter.plain(rs.getBigDecimal("forward_value")));
                t.put("drawdown", SimFixtureWriter.plain(rs.getBigDecimal("drawdown")));
            }, seasonId);
            assertThat(turns.size()).isEqualTo(spec.pathLength());

            MockHttpServletResponse report = get("/api/v1/seasons/" + seasonId + "/report");
            assertThat(report.getStatus()).as("report").isEqualTo(200);
            ObjectNode reportBody = (ObjectNode) body(report);
            assertThat(reportBody.get("reconTurnBundles").asInt())
                    .as("season %d: one reconciliation bundle per turn, abandoned seasons included", spec.index())
                    .isEqualTo(spec.pathLength());
            j.set("report", reportBody);

            ENGINE.reset();
            ENGINE.respond(PERCENTILES, 200, "application/json", EnginePercentileAnswers.body(pathCount, spec.pathLength(),
                    counts.get("endMultiple").get("below").asInt(), counts.get("endMultiple").get("equal").asInt(),
                    counts.get("sharpeDiff").get("below").asInt(), counts.get("sharpeDiff").get("equal").asInt()));
            MockHttpServletResponse score = get("/api/v1/seasons/" + seasonId + "/score");
            assertThat(score.getStatus()).as("score").isEqualTo(200);
            List<FakeEngineServer.Recorded> asked = ENGINE.requests().stream().filter(r -> PERCENTILES.equals(r.path())).toList();
            assertThat(asked).as("one percentile request per season").hasSize(1);
            j.set("percentileRequest", mapper.readTree(asked.get(0).body()));

            ObjectNode scoreOut = j.putObject("score");
            jdbc.query("SELECT * FROM season_score WHERE season_id = ?", rs -> {
                for (String column : new String[] {"score_version", "score", "result_score", "process_score", "sharpe",
                        "bench_sharpe", "sharpe_diff", "sharpe_diff_percentile", "luck_percentile", "risk_score",
                        "discipline_score", "diversification_score", "turnover_score", "consistency_score",
                        "formation_score", "role_score", "noise_score", "nudge_score"}) {
                    Object value = rs.getObject(column);
                    scoreOut.put(camel(column), value == null ? null : value instanceof BigDecimal d
                            ? SimFixtureWriter.plain(d) : value.toString());
                }
                scoreOut.put("processItems", rs.getInt("process_items"));
                scoreOut.put("ratingExclusion", rs.getString("rating_exclusion"));
            }, seasonId);
            return j;
        }

        private ArrayNode ints(ResultSet rs, String... columns) throws SQLException {
            ArrayNode out = mapper.createArrayNode();
            for (String column : columns) {
                out.add(rs.getInt(column));
            }
            return out;
        }

        private static String camel(String column) {
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (char c : column.toCharArray()) {
                if (c == '_') {
                    up = true;
                } else {
                    sb.append(up ? Character.toUpperCase(c) : c);
                    up = false;
                }
            }
            return sb.toString();
        }

        // ---- http ---------------------------------------------------------------------------------------------

        MockHttpServletResponse post(String uri, String json) {
            return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange().getResponse();
        }

        MockHttpServletResponse get(String uri) {
            return mvc.get().uri(uri).exchange().getResponse();
        }

        JsonNode body(MockHttpServletResponse response) {
            try {
                return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("answer is not JSON (status " + response.getStatus() + ")", e);
            }
        }

        Reply reply(MockHttpServletResponse response) {
            int status = response.getStatus();
            if (status >= 200 && status < 300) {
                return new Reply(status, "");
            }
            return new Reply(status, body(response).path("code").asString(""));
        }

        // ---- the arena of one season ----------------------------------------------------------------------------

        final class SeasonArena implements Arena {

            private final long seasonId;
            private final Spec spec;
            private final String base;
            private final List<String> pool = new ArrayList<>();
            private final List<FeedRef> feed = new ArrayList<>();
            final ArrayNode holdings = mapper.createArrayNode();
            private int current;

            SeasonArena(long seasonId, Spec spec) {
                this.seasonId = seasonId;
                this.spec = spec;
                this.base = "/api/v1/seasons/" + seasonId;
                pool.addAll(jdbc.queryForList("""
                        SELECT a.market || ':' || a.ticker FROM turn_price tp JOIN asset a ON a.id = tp.asset_id
                        WHERE tp.season_id = ? AND tp.turn_no = 0 ORDER BY a.id""", String.class, seasonId));
            }

            @Override
            public List<String> pool() {
                return pool;
            }

            @Override
            public long cash() {
                return jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, seasonId)
                        .longValueExact();
            }

            @Override
            public Map<String, Integer> held() {
                Map<String, Integer> out = new LinkedHashMap<>();
                jdbc.query("""
                        SELECT a.market || ':' || a.ticker AS k, h.quantity FROM holding h JOIN asset a ON a.id = h.asset_id
                        WHERE h.season_id = ? AND h.quantity > 0 ORDER BY a.id""", rs -> {
                    out.put(rs.getString("k"), rs.getInt("quantity"));
                }, seasonId);
                return out;
            }

            @Override
            public Map<String, String> openPositions() {
                Map<String, String> out = new LinkedHashMap<>();
                jdbc.query("""
                        SELECT a.market || ':' || a.ticker AS k, l.declared_position FROM lineup l JOIN asset a ON a.id = l.asset_id
                        WHERE l.season_id = ? AND l.end_turn IS NULL""", rs -> {
                    out.put(rs.getString("k"), rs.getString("declared_position"));
                }, seasonId);
                return out;
            }

            @Override
            public int[] formation() {
                return jdbc.queryForObject("""
                        SELECT current_goalkeeper, current_defender, current_midfielder, current_forward FROM season WHERE id = ?""",
                        (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)}, seasonId);
            }

            @Override
            public Map<String, Long> prices() {
                Map<String, Long> out = new HashMap<>();
                jdbc.query("""
                        SELECT a.market || ':' || a.ticker AS k, tp.price FROM turn_price tp JOIN asset a ON a.id = tp.asset_id
                        WHERE tp.season_id = ? AND tp.turn_no = ?""", rs -> {
                    out.put(rs.getString("k"), rs.getBigDecimal("price").longValueExact());
                }, seasonId, current);
                return out;
            }

            @Override
            public List<FeedRef> feed() {
                return feed;
            }

            @Override
            public Set<String> board() {
                Set<String> out = new TreeSet<>();
                if (current < 1) {
                    return out;
                }
                MockHttpServletResponse response = get(base + "/turns/" + current + "/ranking");
                assertThat(response.getStatus()).as("ranking of turn %d", current).isEqualTo(200);
                JsonNode board = body(response);
                for (String list : new String[] {"gainers", "losers", "attention", "risingStreaks", "fallingStreaks"}) {
                    for (JsonNode row : board.get(list)) {
                        out.add(keyById.get(row.get("assetId").asLong()));
                    }
                }
                return out;
            }

            @Override
            public Reply order(int turn, String asset, String side, int quantity, String position, ObjectNode source) {
                StringBuilder json = new StringBuilder("{\"expectedTurnNo\":" + turn + ",\"side\":\"" + side + "\",\"assetId\":"
                        + idByKey.get(asset) + ",\"quantity\":" + quantity);
                if (position != null) {
                    json.append(",\"declaredPosition\":\"").append(position).append('"');
                }
                String kind = source.get("kind").asString();
                if (!"DIRECT".equals(kind)) {
                    json.append(",\"source\":{\"kind\":\"").append(kind).append('"');
                    if (source.has("feedTurn")) {
                        json.append(",\"feedTurnNo\":").append(source.get("feedTurn").asInt()).append(",\"feedSeq\":")
                                .append(source.get("feedSeq").asInt());
                    }
                    json.append('}');
                }
                json.append('}');
                return reply(post(base + "/orders", json.toString()));
            }

            @Override
            public Reply formation(int turn, int[] shares, String reason) {
                String json = "{\"expectedTurnNo\":" + turn + ",\"formation\":{\"goalkeeper\":" + shares[0] + ",\"defender\":"
                        + shares[1] + ",\"midfielder\":" + shares[2] + ",\"forward\":" + shares[3] + "}"
                        + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
                return reply(post(base + "/formation-changes", json));
            }

            @Override
            public Reply lineup(int turn, String asset, String position) {
                return reply(post(base + "/lineup-changes", "{\"expectedTurnNo\":" + turn + ",\"assetId\":" + idByKey.get(asset)
                        + ",\"declaredPosition\":\"" + position + "\"}"));
            }

            @Override
            public void advance(int turn) {
                MockHttpServletResponse response = post(base + "/turns", "{\"expectedTurnNo\":" + turn + "}");
                assertThat(response.getStatus()).as("season %d advance from turn %d", spec.index(), turn).isEqualTo(201);
                JsonNode result = body(response);
                current = turn + 1;
                for (String list : new String[] {"feed", "alerts"}) {
                    for (JsonNode item : result.get(list)) {
                        Long assetId = item.get("assetId").isNull() ? null : item.get("assetId").asLong();
                        feed.add(new FeedRef(item.get("turnNo").asInt(), item.get("seq").asInt(),
                                assetId == null ? null : keyById.get(assetId), "alerts".equals(list)));
                    }
                }
            }

            @Override
            public void abandon(int turn) {
                MockHttpServletResponse response = post(base + "/abandon", "{\"expectedTurnNo\":" + turn + "}");
                assertThat(response.getStatus()).as("season %d abandon at turn %d", spec.index(), turn).isEqualTo(200);
                assertThat(body(response).get("status").asString()).isEqualTo("FINISHED");
                current = spec.pathLength();
            }

            @Override
            public void snapshot(int turn) {
                holdings.add(holdingsAt(turn));
            }

            /** The final holdings, after the last advance or the abandon's auto turns. */
            void finish() {
                holdings.add(holdingsAt(spec.pathLength()));
            }

            private ObjectNode holdingsAt(int turn) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("turn", turn);
                ObjectNode held = entry.putObject("holdings");
                jdbc.query("""
                        SELECT a.market || ':' || a.ticker AS k, h.quantity, h.avg_price FROM holding h JOIN asset a ON a.id = h.asset_id
                        WHERE h.season_id = ? AND h.quantity > 0 ORDER BY a.id""", rs -> {
                    ArrayNode value = held.putArray(rs.getString("k"));
                    value.add(rs.getInt("quantity"));
                    value.add(SimFixtureWriter.plain(rs.getBigDecimal("avg_price")));
                }, seasonId);
                return entry;
            }
        }
    }
}

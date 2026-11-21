package antlab.dbrole;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.CollectTestData.TestClock;
import antlab.collect.JobStatus;
import antlab.collect.PriceBasis;
import antlab.collect.RunTrigger;
import antlab.collect.ecos.EcosProperties;
import antlab.collect.ecos.EcosSeriesJob;
import antlab.collect.ecos.FakeEcosClient;
import antlab.collect.fred.FakeFredClient;
import antlab.collect.fred.FredProperties;
import antlab.collect.fred.FredSeriesJob;
import antlab.collect.series.SeriesCatalog;
import antlab.collect.kis.DailyClose;
import antlab.collect.kis.KisExchange;
import antlab.collect.kis.KisQuoteClient;
import antlab.collect.price.KisDailyPriceJob;
import antlab.collect.price.KisTargetCatalog;
import antlab.collect.price.PriceJobProperties;
import antlab.collect.price.TargetFilter;
import antlab.support.E2eHttp;
import antlab.support.E2eHttp.Reply;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * One whole season, an agent and a collector run through the application on the least-privilege role (done-when 3). Only history
 * and the runner's owner member are loaded by the superuser: the app role may not write them (B-4). Every table the code
 * writes is written at least once, so a right missing from the classes shows up here as an error and not in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LeastPrivilegeFlowTest {

    private static final int TURNS = 12;
    private static final String EMAIL = "least-privilege@example.invalid";
    private static final String PASSWORD = "least-privilege-pass";
    private static final String RUNNER_TOKEN = "fake-runner-token-for-tests-only-0123456789abcdef";
    private static final long RUNNER_OWNER_ID = 910_001L;
    private static final Pattern COOKIE = Pattern.compile("^antlab_refresh=([A-Za-z0-9_-]*);");

    /** Ticker, quantity, declared position: one goalkeeper, two defenders, two midfielders, three forwards. */
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

    /** Tables nothing in the application writes yet: agent_reflection and agent_memory (AG-05 PR C writes them), agent_call (AG-02 part 3), season_score and rating_history (MVP-18 schema), projection (written only by a call to the engine, which this flow has none of). turn_feed is written by the turn advance since MVP-11. */
    private static final List<String> WITHOUT_WRITER = List.of("agent_call", "agent_reflection", "agent_memory", "season_score", "rating_history", "projection");

    private static AppRoleDatabase database;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database = AppRoleDatabase.register(registry, "flow");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactions;

    private E2eHttp http;
    private long season;
    private final Map<String, Long> assetId = new LinkedHashMap<>();

    private static String buyBody(int turn, long asset, int quantity, String declared) {
        return "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + asset + ", \"quantity\": " + quantity
                + ", \"declaredPosition\": \"" + declared + "\"}";
    }

    private String url(String suffix) {
        return "/api/v1/seasons/" + season + suffix;
    }

    private void advanceTo(int target) {
        int turn = http.get("/api/v1/seasons/" + season).json().get("currentTurnNo").asInt();
        for (; turn < target; turn++) {
            Reply r = http.post(url("/turns"), "{\"expectedTurnNo\": " + turn + "}");
            assertThat(r.status()).as("advance from " + turn + ": " + r.body()).isEqualTo(201);
        }
    }

    private static String cookieOf(Reply reply) {
        String header = reply.header("Set-Cookie");
        assertThat(header).as("Set-Cookie of " + reply).isNotNull();
        Matcher m = COOKIE.matcher(header);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void aWholeSeasonAnAgentAndACollectorRunPlayWithoutADeniedStatement() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/synthetic/season_fake.sql"))
                .execute(database.superuser());
        new JdbcTemplate(database.superuser()).update("""
                INSERT INTO member (id, email, name, password) OVERRIDING SYSTEM VALUE
                VALUES (?, 'runner-owner@example.invalid', 'runner owner', '!')""", RUNNER_OWNER_ID);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("antlab_app");

        playTheSeason();
        runTheAgent();
        runTheCollector();
        runTheFredCollector();
        runTheEcosCollector();

        JdbcTemplate superuser = new JdbcTemplate(database.superuser());
        List<String> empty = new ArrayList<>();
        for (String table : AppPrivilegeMatrixTest.APPEND) {
            if (superuser.queryForObject("SELECT count(*) FROM " + table, Long.class) == 0) {
                empty.add(table);
            }
        }
        for (String table : AppPrivilegeMatrixTest.MUTABLE) {
            if (superuser.queryForObject("SELECT count(*) FROM " + table, Long.class) == 0) {
                empty.add(table);
            }
        }
        assertThat(empty).as("every append and mutable table was written").containsExactlyInAnyOrderElementsOf(WITHOUT_WRITER);
        assertThat(superuser.queryForObject("SELECT count(*) FROM season WHERE status = 'FINISHED'", Long.class)).isOne();
        assertThat(superuser.queryForObject("SELECT count(*) FROM reconciliation_log WHERE status <> 'MATCH'", Long.class)).isZero();
        assertThat(superuser.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = ? AND usename <> 'antlab_app' "
                + "AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND usename <> ?", Long.class, database.name(),
                database.server().container().getUsername())).as("no other role works in the app database").isZero();
    }

    private void playTheSeason() {
        Reply signup = new E2eHttp(port).post("/api/v1/auth/signup",
                "{\"email\": \"" + EMAIL + "\", \"name\": \"FAKE Manager\", \"password\": \"" + PASSWORD + "\"}");
        assertThat(signup.status()).as("signup " + signup.body()).isEqualTo(201);
        Reply login = new E2eHttp(port).post("/api/v1/auth/login", "{\"email\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}");
        assertThat(login.status()).as("login " + login.body()).isEqualTo(200);
        String cookie = cookieOf(login);

        Reply refreshed = new E2eHttp(port).send(HttpMethod.POST, "/api/v1/auth/refresh", null, null, "application/json",
                Map.of("Cookie", "antlab_refresh=" + cookie));
        assertThat(refreshed.status()).as("refresh " + refreshed.body()).isEqualTo(200);
        String rotated = cookieOf(refreshed);
        http = new E2eHttp(port, Map.of("Authorization", "Bearer " + refreshed.json().get("accessToken").asString()));

        Reply pool = http.get("/api/v1/pool");
        assertThat(pool.status()).as("pool " + pool.body()).isEqualTo(200);
        for (JsonNode a : pool.json().get("assets")) {
            assetId.put(a.get("ticker").asString(), a.get("assetId").asLong());
        }

        Reply start = http.post("/api/v1/seasons", "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 30, \"midfielder\": 30, \"forward\": 30}, \"pathLength\": " + TURNS + "}");
        assertThat(start.status()).as("season start " + start.body()).isEqualTo(201);
        season = start.json().get("id").asLong();
        for (String[] line : TEAM) {
            Reply order = http.post(url("/orders"), buyBody(0, assetId.get(line[0]), Integer.parseInt(line[1]), line[2]));
            assertThat(order.status()).as("buy " + line[0] + " " + order.body()).isEqualTo(201);
        }

        advanceTo(5);
        Reply sell = http.post(url("/orders"), "{\"expectedTurnNo\": 5, \"side\": \"SELL\", \"assetId\": "
                + assetId.get("ZZ0003") + ", \"quantity\": 50}");
        assertThat(sell.status()).as("partial sell " + sell.body()).isEqualTo(201);
        Reply change = http.post(url("/formation-changes"), "{\"expectedTurnNo\": 5, \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 20, \"midfielder\": 30, \"forward\": 40}, \"reason\": \"FAKE reason\"}");
        assertThat(change.status()).as("formation change " + change.body()).isEqualTo(201);
        Reply bound = http.post(url("/orders"), buyBody(5, assetId.get("ZZUS03"), 5, "FORWARD_DEFENSIVE"));
        assertThat(bound.status()).as("bound buy " + bound.body()).isEqualTo(201);

        advanceTo(11);
        Reply holdings = http.get(url("/holdings"));
        assertThat(holdings.status()).as("holdings " + holdings.body()).isEqualTo(200);
        for (JsonNode h : holdings.json().get("holdings")) {
            Reply out = http.post(url("/orders"), "{\"expectedTurnNo\": 11, \"side\": \"SELL\", \"assetId\": "
                    + h.get("assetId").asLong() + ", \"quantity\": " + h.get("quantity").asInt() + "}");
            assertThat(out.status()).as("sell all " + out.body()).isEqualTo(201);
        }
        assertThat(http.get(url("/holdings")).json().get("holdings")).as("everything is sold").isEmpty();

        advanceTo(TURNS);
        assertThat(http.get("/api/v1/seasons/" + season).json().get("status").asString()).isEqualTo("FINISHED");
        assertThat(http.get(url("/report")).status()).isEqualTo(200);
        assertThat(http.get(url("/review")).status()).isEqualTo(200);

        Reply logout = new E2eHttp(port).send(HttpMethod.POST, "/api/v1/auth/logout", null, null, "application/json",
                Map.of("Cookie", "antlab_refresh=" + rotated));
        assertThat(logout.status()).as("logout").isEqualTo(204);
    }

    private void runTheAgent() {
        Map<String, String> runner = Map.of("X-Antlab-Runner-Token", RUNNER_TOKEN);
        E2eHttp anonymous = new E2eHttp(port);
        Reply register = anonymous.post("/internal/agent-runner/v1/agents", "{\"personaKey\": \"value-investor\","
                + " \"modelRoute\": \"OPENROUTER\", \"modelId\": \"fake/model-1\", \"displayName\": \"FAKE agent\"}", runner);
        assertThat(register.status()).as("agent register " + register.body()).isEqualTo(201);
        long agent = register.json().get("agentId").asLong();
        Reply start = anonymous.post("/internal/agent-runner/v1/agents/" + agent + "/seasons", """
                {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
                 "config": {"personaVersion": 1, "personaSha256": "%s", "personaDisplayName": "Value", "modelRequested": "fake/model-1",
                   "temperature": 0.7, "maxTokens": 2000, "turnTimeoutMs": 60000, "promptTemplateVersion": "p1",
                   "runnerVersion": "r1", "budgetCalls": 100, "budgetUsd": 1.5}}""".formatted("a".repeat(64)), runner);
        assertThat(start.status()).as("agent season start " + start.body()).isEqualTo(201);
        Reply view = anonymous.send(HttpMethod.GET, "/internal/agent-runner/v1/agents/" + agent + "/seasons/"
                + start.json().get("id").asLong() + "/turn-view", null, null, "application/json", runner);
        assertThat(view.status()).as("agent turn view " + view.body()).isEqualTo(200);
        // a league is an insert plus a close-column update, both as the app role; an entry locks the row and appends a seat
        Reply seated = anonymous.post("/internal/agent-runner/v1/leagues", "{\"name\": \"FAKE seat league\"}", runner);
        assertThat(seated.status()).as("league create " + seated.body()).isEqualTo(201);
        long seatedId = seated.json().get("id").asLong();
        Reply entered = anonymous.post("/internal/agent-runner/v1/agents/" + agent + "/seasons", """
                {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
                 "leagueId": %d,
                 "config": {"personaVersion": 1, "personaSha256": "%s", "personaDisplayName": "Value", "modelRequested": "fake/model-1",
                   "temperature": 0.7, "maxTokens": 2000, "turnTimeoutMs": 60000, "promptTemplateVersion": "p1",
                   "runnerVersion": "r1", "budgetCalls": 100, "budgetUsd": 1.5}}""".formatted(seatedId, "a".repeat(64)), runner);
        assertThat(entered.status()).as("league entry " + entered.body()).isEqualTo(201);
        assertThat(entered.json().get("leagueId").asLong()).isEqualTo(seatedId);
        Reply league = anonymous.post("/internal/agent-runner/v1/leagues", "{\"name\": \"FAKE league\"}", runner);
        assertThat(league.status()).as("league create " + league.body()).isEqualTo(201);
        Reply closed = anonymous.post("/internal/agent-runner/v1/leagues/" + league.json().get("id").asLong() + "/close", "", runner);
        assertThat(closed.status()).as("league close " + closed.body()).isEqualTo(200);
    }

    private void runTheCollector() {
        KisQuoteClient fake = new KisQuoteClient() {
            @Override
            public List<DailyClose> domesticDaily(String ticker, LocalDate from, LocalDate to, PriceBasis basis) {
                return List.of(new DailyClose(LocalDate.parse("2026-01-09"), new BigDecimal("10100")),
                        new DailyClose(LocalDate.parse("2026-01-12"), new BigDecimal("10200")));
            }

            @Override
            public List<DailyClose> overseasDaily(String ticker, KisExchange exchange, LocalDate from, LocalDate to) {
                throw new IllegalStateException("not asked");
            }

            @Override
            public int requestsMade() {
                return 1;
            }
        };
        KisDailyPriceJob job = KisDailyPriceJob.create(() -> fake,
                KisTargetCatalog.fromCsv("market,ticker,exchange_code,price_basis\nKR,ZZ0001,,ADJUSTED\n"), jdbc, transactions,
                new TestClock(Instant.parse("2026-01-13T07:30:00Z")), new PriceJobProperties(10, 30, Duration.ofHours(2)));

        var summary = job.run(RunTrigger.MANUAL, TargetFilter.all());

        assertThat(summary.status()).as(summary.toString()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).isEqualTo(2);
    }
    private void runTheFredCollector() {
        FakeFredClient fake = new FakeFredClient()
                .answer("DGS2", FakeFredClient.obs("2026-01-09", "3.6", "2026-01-13", "2026-01-13"),
                        FakeFredClient.obs("2026-01-12", "3.7", "2026-01-13", "2026-01-13"))
                .answer("CPILFESL", FakeFredClient.obs("2025-12-01", "330.1", "2026-01-13", "2026-02-12"),
                        FakeFredClient.obs("2025-12-01", "330.2", "2026-02-13", "9999-12-31"));
        FredSeriesJob job = FredSeriesJob.create(() -> fake, SeriesCatalog.fromCsv("""
                code,source,source_series_id,unit,frequency,release_lag_days,revisable
                US_DGS2,FRED,DGS2,PERCENT,DAILY,1,false
                US_CPILFESL,FRED,CPILFESL,INDEX,MONTHLY,10,true
                """), jdbc, transactions, new TestClock(Instant.parse("2026-01-13T08:30:00Z")),
                new FredProperties(true, "FAKE-FRED-KEY-0001", 1, 30, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2)));

        var summary = job.run(RunTrigger.MANUAL, Set.of());

        assertThat(summary.status()).as(summary.toString()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).isEqualTo(4);
        assertThat(summary.revisedCount()).isEqualTo(1);
    }

    private void runTheEcosCollector() {
        FakeEcosClient fake = new FakeEcosClient()
                .answer("817Y002/010200000", FakeEcosClient.obs("2026-01-09", "2.7"), FakeEcosClient.obs("2026-01-12", "2.8"))
                .answer("901Y009/0", FakeEcosClient.obs("2025-12-01", "117.0"));
        EcosSeriesJob job = EcosSeriesJob.create(() -> fake, SeriesCatalog.fromCsv("""
                code,source,source_series_id,unit,frequency,release_lag_days,revisable
                KR_KTB3Y,ECOS,817Y002/010200000,PERCENT,DAILY,0,false
                KR_CPI,ECOS,901Y009/0,INDEX,MONTHLY,7,true
                """), jdbc, transactions, new TestClock(Instant.parse("2026-01-13T09:30:00Z")),
                new EcosProperties(true, "FAKEECOSKEY0000001", 1, 20, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2)));

        var summary = job.run(RunTrigger.MANUAL, Set.of());

        assertThat(summary.status()).as(summary.toString()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.insertedCount()).isEqualTo(3);
    }
}

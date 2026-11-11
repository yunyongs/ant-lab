package antlab.api.order;

import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.place;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.DeferredPathSource;
import antlab.support.FixedPathSource;
import antlab.support.GoldenFixtureSource;
import antlab.support.GoldenRowQueries;
import antlab.support.GoldenRowQueries.GoldenTable;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.RowSetDiff;
import antlab.support.SeasonReplayInput;
import antlab.support.SeasonReplayInput.PlaceOrder;
import antlab.support.SeasonReplayInput.ReplayAction;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 팀 구성 골든 — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 *
 * <p>① SQL(db/20 → db/21)이 만든 0턴 주문을 같은 순서·종목·수량·선언 포지션으로 API 에 보내면 주문·체결·보유·원장·계좌·라인업
 * 여섯 표의 정규화 행이 같다. ② API DB 에서 db/22 의 {@code recon_rules(시즌, 0)} 15개가 모두 MATCH 이고
 * db/21 검증(p0_15_check)이 통과한다.
 *
 * <p>하네스 컨테이너를 가리켜야 하므로 {@code @IntegrationTest}(공유 컨테이너)를 쓰지 않고 자체 문맥을 띄운다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid",
        "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class BuyOrderGoldenTest {

    private static final long GOLDEN_SEED = 14L;
    private static final GoldenFixtureSource SOURCE = GoldenFixtureSource.fromEnvironment();

    private static PostgreSQLContainer container;
    private static SqlGoldenHarness harness;
    private static DatabaseHandle apiDb;

    /** 표본이 없으면 false(건너뜀), 지정했는데 파일이 없으면 AssertionError 로 실패한다. */
    static boolean sampleAvailable() {
        GoldenFixtureSource.FixtureAvailability availability = SOURCE.inspect();
        if (availability.explicit() && !availability.isComplete()) {
            availability.requireOrSkip();
        }
        if (!availability.isComplete()) {
            System.out.println("[sql-golden] 건너뜀 — " + availability.skipMessage());
        }
        return availability.isComplete();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, SOURCE);
        harness.stageInputs();
        apiDb = harness.createSqlDatabase("team_buy_api");
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + apiDb.name());
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GoldenBeans {
        @Bean
        @Primary
        DeferredPathSource deferredPathSource() {
            return new DeferredPathSource();
        }

        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> GOLDEN_SEED;
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DeferredPathSource deferred;

    @Autowired
    MonthBlockRepository monthBlocks;

    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;
    private int replayed;

    @BeforeAll
    void prepare() throws Exception {
        // API DB: Flyway 가 스키마를 만든 뒤 표본 적재(10 → 12)
        harness.execute(apiDb, SqlGoldenScenario.apiDbLoad());
        // SQL 기준 DB: 시즌 시작 → 0턴 팀 매수 → 검증
        sqlDb = harness.createSqlDatabase("team_buy_sql");
        harness.execute(sqlDb, SqlGoldenScenario.teamBuyGolden());
        sqlSeason = harness.practiceSeasonId(sqlDb);

        // 같은 경로·같은 시드·같은 설정으로 API 시즌을 시작한다
        RowSet path = harness.readRows(sqlDb, GoldenRowQueries.seasonPath().query(), sqlSeason);
        List<LocalDate> months = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            months.add((LocalDate) path.get(i, "month_start"));
        }
        deferred.set(new FixedPathSource("p0-sql", months, monthBlocks));
        MockHttpServletResponse started = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": " + SqlGoldenScenario.PATH_LENGTH + "}")
                .exchange().getResponse();
        assertThat(started.getStatus()).isEqualTo(201);
        apiSeason = harness.practiceSeasonId(apiDb);

        replayed = replayOrders();
    }

    /** SQL 의 0턴 주문을 (턴, 주문 번호) 순서로 API 에 보낸다. 선언 포지션은 SQL 라인업(시작 턴 0), 종목 번호는 풀 응답에서 읽는다. */
    private int replayOrders() throws Exception {
        SeasonReplayInput input = harness.captureReplayInput(sqlDb, sqlSeason);
        RowSet lineup = harness.readRows(sqlDb, GoldenRowQueries.lineup().query(), sqlSeason);
        Map<String, String> declared = new HashMap<>();
        for (int i = 0; i < lineup.size(); i++) {
            assertThat(lineup.get(i, "start_turn")).isEqualTo(0L);
            declared.put(lineup.get(i, "market") + "/" + lineup.get(i, "ticker"),
                    (String) lineup.get(i, "declared_position"));
        }
        Map<String, Long> assetIds = new HashMap<>();
        JsonNode assets = JsonMapper.shared().readTree(
                mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString()).get("assets");
        assets.forEach(a -> assetIds.put(a.get("market").asString() + "/" + a.get("ticker").asString(),
                a.get("assetId").asLong()));

        int count = 0;
        for (ReplayAction replay : input.actions()) {
            if (!(replay instanceof PlaceOrder action)) {
                continue;
            }
            // 키: 턴/시장/종목/방향/순번
            String[] k = action.key().split("/");
            assertThat(k[0]).as("팀 구성은 0턴 주문뿐이다").isEqualTo("0");
            assertThat(k[3]).isEqualTo("BUY");
            String asset = k[1] + "/" + k[2];
            MockHttpServletResponse r = place(mvc, apiSeason,
                    body(0, "BUY", assetIds.get(asset), action.quantity(), declared.get(asset)));
            assertThat(r.getStatus()).as("재생 주문 " + count).isEqualTo(201);
            count++;
        }
        return count;
    }

    @AfterAll
    static void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void SQL과_API가_만든_여섯_표가_정규화_행까지_같다() throws Exception {
        List<GoldenTable> tables = List.of(GoldenRowQueries.orders(), GoldenRowQueries.execution(),
                GoldenRowQueries.holding(), GoldenRowQueries.cashLedger(), GoldenRowQueries.cashAccount(),
                GoldenRowQueries.lineup());
        assertThat(replayed).as("재생한 주문 수").isPositive();
        for (GoldenTable table : tables) {
            RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
            RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
            // 양쪽이 비어 있어 헛으로 일치하지 않게
            assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
            assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
            RowSetDiff.assertNoDifference(expected, actual, table.spec());
        }
        assertThat(harness.readRows(apiDb, GoldenRowQueries.orders().query(), apiSeason).size())
                .isEqualTo(replayed);
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API가_만든_DB의_대사_규칙_15개가_모두_일치한다() throws Exception {
        RowQuery query = new RowQuery("recon_rules",
                "SELECT rule_no, check_code, status FROM pg_temp.recon_rules(:sid, 0::smallint) ORDER BY rule_no",
                List.of(new RowColumn("rule_no", ColumnType.LONG), new RowColumn("check_code", ColumnType.TEXT),
                        new RowColumn("status", ColumnType.TEXT)));
        RowSet rules = harness.queryWithDefinitions(apiDb, SqlGoldenScenario.RECONCILE_DEFINITIONS, query, apiSeason);
        assertThat(rules.size()).isEqualTo(15);
        for (int i = 0; i < rules.size(); i++) {
            assertThat(rules.get(i, "status")).as(rules.get(i, "check_code").toString()).isEqualTo("MATCH");
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API가_만든_DB에서_팀_매수_검증이_통과한다() throws Exception {
        var result = harness.run(apiDb, SqlGoldenScenario.teamBuyCheck());
        harness.assertCheckPassed(result, "p0_15_check");
    }
}

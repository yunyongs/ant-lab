package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.body;
import static antlab.api.season.SeasonApiSupport.formation;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.PathRules;
import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.DefaultBearerConfig;
import antlab.support.DeferredPathSource;
import antlab.support.FixedPathSource;
import antlab.support.GoldenCleanup;
import antlab.support.GoldenFixtureSource;
import antlab.support.GoldenRowQueries;
import antlab.support.GoldenRowQueries.GoldenTable;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.RowSetDiff;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TestTokens;
import java.math.BigDecimal;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시즌 시작 골든 — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 *
 * <p>① 같은 설정·같은 경로·같은 시드로 SQL(db/20)과 API 가 만든 시즌 5개 표의 정규화 행이 같다.
 * ② API 가 만든 DB 에서 db/20 검증 20개가 그대로 통과한다. ③ 실제 표본 풀의 API 응답이 JDBC 조회와 같다.
 *
 * <p>하네스 컨테이너를 가리켜야 하므로 {@code @IntegrationTest}(공유 컨테이너)를 쓰지 않고 자체 문맥을 띄운다.
 * 판정은 문맥을 띄우기 전에 한다(조건 메서드).
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid",
        "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class SeasonStartGoldenTest {

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

    /** 컨테이너·API DB 는 문맥이 뜰 때 처음 만들어진다(Flyway 가 V1–V3 을 이 DB 에 돌린다). */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, SOURCE);
        harness.stageInputs();
        apiDb = harness.createSqlDatabase("season_start_api");
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

    @BeforeAll
    void prepare() throws Exception {
        // API DB: Flyway 가 스키마를 만든 뒤 표본 적재(10 → 12)
        harness.execute(apiDb, SqlGoldenScenario.apiDbLoad());
        // SQL 기준 DB: 시즌 시작까지
        sqlDb = harness.createSqlDatabase("season_start_sql");
        harness.execute(sqlDb, SqlGoldenScenario.seasonStartGolden());
        sqlSeason = harness.practiceSeasonId(sqlDb);

        // SQL 시즌의 경로(턴 순서)를 고정 경로 출처로 주고 같은 설정으로 API 를 부른다
        RowSet path = harness.readRows(sqlDb, GoldenRowQueries.seasonPath().query(), sqlSeason);
        List<LocalDate> months = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            assertThat(path.get(i, "turn_no")).isEqualTo((long) i + 1);
            months.add((LocalDate) path.get(i, "month_start"));
        }
        assertThat(months).hasSize(SqlGoldenScenario.PATH_LENGTH);
        deferred.set(new FixedPathSource("p0-sql", months, monthBlocks));

        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body("\"-0.20\"", formation("10", "30", "30", "30"),
                        String.valueOf(SqlGoldenScenario.PATH_LENGTH)))
                .exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        apiSeason = harness.practiceSeasonId(apiDb);
        assertThat(JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong()).isEqualTo(apiSeason);
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void SQL과_API가_만든_시즌의_다섯_표가_정규화_행까지_같다() throws Exception {
        List<GoldenTable> tables = List.of(GoldenRowQueries.season(), GoldenRowQueries.seasonPath(),
                GoldenRowQueries.cashAccount(), GoldenRowQueries.cashLedger(), GoldenRowQueries.turnPrice());
        for (GoldenTable table : tables) {
            RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
            RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
            // 양쪽이 비어 있어 헛으로 일치하지 않게
            assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
            assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
            RowSetDiff.assertNoDifference(expected, actual, table.spec());
        }
        // 시드·엔진 판도 제외 없이 같다(고정 시드·고정 판)
        RowSet season = harness.readRows(apiDb, GoldenRowQueries.season().query(), apiSeason);
        assertThat(season.get(0, "seed")).isEqualTo(GOLDEN_SEED);
        assertThat(season.get(0, "engine_version")).isEqualTo("p0-sql");
        assertThat(harness.readRows(apiDb, GoldenRowQueries.turnPrice().query(), apiSeason).size())
                .as("0턴 풀 전체 20종목").isEqualTo(20);
        // 경로 규칙은 고정 경로에도 성립한다
        RowSet path = harness.readRows(apiDb, GoldenRowQueries.seasonPath().query(), apiSeason);
        List<LocalDate> months = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            months.add((LocalDate) path.get(i, "month_start"));
        }
        assertThat(PathRules.violations(months, LocalDate.of(2026, 1, 2), SqlGoldenScenario.PATH_LENGTH)).isEmpty();
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API가_만든_DB에서_시즌_시작_검증_20개가_통과한다() throws Exception {
        var result = harness.run(apiDb, SqlGoldenScenario.seasonStartCheck());
        harness.assertCheckPassed(result, "p0_14_check");
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 실제_표본_풀의_API_응답은_JDBC_조회와_같다() throws Exception {
        List<RowColumn> cols = List.of(new RowColumn("market", ColumnType.TEXT), new RowColumn("ticker", ColumnType.TEXT),
                new RowColumn("system_position", ColumnType.TEXT), new RowColumn("up", ColumnType.DECIMAL),
                new RowColumn("down", ColumnType.DECIMAL), new RowColumn("vol", ColumnType.DECIMAL),
                new RowColumn("mdd", ColumnType.DECIMAL));
        RowSet db = harness.readRows(apiDb, new RowQuery("pool", """
                SELECT a.market, a.ticker, p.system_position, p.up_participation AS up, p.down_participation AS down,
                       p.volatility AS vol, p.max_drawdown AS mdd
                FROM asset_position p JOIN asset a ON a.id = p.asset_id
                WHERE p.base_date = DATE '2026-01-02'""", cols), 0L);
        JsonNode assets = JsonMapper.shared().readTree(
                mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString()).get("assets");
        assertThat(assets).hasSize(db.size()).hasSize(20);
        Map<String, JsonNode> byKey = new HashMap<>();
        assets.forEach(a -> byKey.put(a.get("market").asString() + ":" + a.get("ticker").asString(), a));
        for (int i = 0; i < db.size(); i++) {
            JsonNode a = byKey.get(db.get(i, "market") + ":" + db.get(i, "ticker"));
            assertThat(a).isNotNull();
            assertThat(a.get("systemPosition").asString()).isEqualTo(db.get(i, "system_position"));
            for (String[] pair : new String[][] {{"upParticipation", "up"}, {"downParticipation", "down"},
                    {"volatility", "vol"}, {"maxDrawdown", "mdd"}}) {
                String json = a.get(pair[0]).asString();
                assertThat(json).matches("^-?\\d+\\.\\d{8}$");
                assertThat(new BigDecimal(json)).isEqualByComparingTo((BigDecimal) db.get(i, pair[1]));
            }
        }
    }
}

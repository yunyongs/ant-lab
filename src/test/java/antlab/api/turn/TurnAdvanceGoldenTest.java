package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.code;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.DefaultBearerConfig;
import antlab.support.DeferredPathSource;
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
import antlab.support.TeamBuyGoldenSeason;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.util.List;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 턴 전환 골든 — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 * SQL 기준은 {@code TURN_GOLDEN}(20 → 21 → 23 × 10)이고, API 시즌은 같은 경로·시드·0턴 팀으로 시작해 {@code POST …/turns} 를 10번 보낸다.
 * 12개 표(시즌·경로·턴 가격·턴·계좌·보유·주문·체결·원장·라인업·턴 피드·대사 기록)의 정규화 행이 같다.
 * <ul>
 *   <li>22 수동 대사는 끼우지 않는다 — 시도 번호를 보존하려고(턴마다 17행, 시도 1).</li>
 *   <li>{@code turn_feed}(MVP-11)는 자연 키 {@code (turn_no, seq)} 로 비교한다 — 종목·그룹·사건 번호는 시장:티커·그룹 코드·사건 키로 바꾼다.
 *       API DB 에서 {@code db/24_turn_feed_check.sql} 도 돌려 저장 = 재생성과 사후 정보 차단을 확인한다.</li>
 * </ul>
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.test.default-member.email=phase0@example.invalid", "antlab.test.default-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("sampleAvailable")
class TurnAdvanceGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "turn_api");
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
            return () -> TeamBuyGoldenSeason.GOLDEN_SEED;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    DeferredPathSource deferred;
    @Autowired
    MonthBlockRepository monthBlocks;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replay(mvc, deferred, monthBlocks, SqlGoldenScenario.turnGolden(),
                "turn_golden_sql");
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        assertThat(r.orders()).isPositive();
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private long count(DatabaseHandle db, String sql, long season) throws Exception {
        RowQuery q = new RowQuery("count", sql, List.of(new RowColumn("n", ColumnType.LONG)));
        return (Long) harness.readRows(db, q, season).get(0, "n");
    }

    @Test
    @Order(1)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 열_번_전환하면_SQL_턴_골든과_열두_표가_정규화_행까지_같다() throws Exception {
        long started = System.nanoTime();
        for (int t = 0; t < SqlGoldenScenario.PATH_LENGTH; t++) {
            MockHttpServletResponse r = advance(mvc, apiSeason, t);
            assertThat(r.getStatus()).as("전환 " + t).isEqualTo(201);
        }
        System.out.println("[X4] 전환 10번 걸린 시간(ms) = " + (System.nanoTime() - started) / 1_000_000);
        for (GoldenTable table : GoldenRowQueries.allForTurnGolden()) {
            RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
            RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
            assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
            assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
            RowSetDiff.assertNoDifference(expected, actual, table.spec());
        }
    }

    @Test
    @Order(2)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 행_수와_대사_기록이_턴마다_17행_전부_MATCH_시도_1이다() throws Exception {
        long pool = count(apiDb, "SELECT count(*) AS n FROM turn_price WHERE season_id = :sid AND turn_no = 0", apiSeason);
        assertThat(count(apiDb, "SELECT count(*) AS n FROM turn_price WHERE season_id = :sid AND turn_no > 0", apiSeason))
                .isEqualTo(pool * SqlGoldenScenario.PATH_LENGTH);
        assertThat(count(apiDb, "SELECT count(*) AS n FROM turn WHERE season_id = :sid", apiSeason)).isEqualTo(10);
        assertThat(count(apiDb, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid", apiSeason)).isEqualTo(170);
        assertThat(count(apiDb, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid AND status = 'MATCH'"
                + " AND attempt_no = 1", apiSeason)).isEqualTo(170);
        assertThat(count(apiDb, "SELECT count(DISTINCT turn_no) AS n FROM reconciliation_log WHERE season_id = :sid", apiSeason))
                .isEqualTo(10);
        long sqlFeed = count(sqlDb, "SELECT count(*) AS n FROM turn_feed WHERE season_id = :sid", sqlSeason);
        assertThat(sqlFeed).isPositive();
        assertThat(count(apiDb, "SELECT count(*) AS n FROM turn_feed WHERE season_id = :sid AND kind <> 'NUDGE'", apiSeason))
                .isEqualTo(sqlFeed);
        assertThat(count(apiDb, "SELECT count(*) AS n FROM turn_feed WHERE season_id = :sid AND kind = 'NUDGE'", apiSeason))
                .as("the Java advance adds nudge alerts, the SQL reference does not").isPositive();
        assertThat(count(apiDb, "SELECT count(DISTINCT turn_no) AS n FROM turn_feed WHERE season_id = :sid", apiSeason))
                .isEqualTo(count(sqlDb, "SELECT count(DISTINCT turn_no) AS n FROM turn_feed WHERE season_id = :sid", sqlSeason));
    }

    @Test
    @Order(3)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 열_번_뒤_열한_번째_전환은_409이고_표는_그대로다() throws Exception {
        MockHttpServletResponse r = advance(mvc, apiSeason, SqlGoldenScenario.PATH_LENGTH);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("SEASON_NOT_ACTIVE");
        for (GoldenTable table : GoldenRowQueries.allForTurnGolden()) {
            RowSetDiff.assertNoDifference(harness.readRows(sqlDb, table.query(), sqlSeason),
                    harness.readRows(apiDb, table.query(), apiSeason), table.spec());
        }
    }

    @Test
    @Order(4)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API가_만든_DB에서_23_검증이_통과한다() throws Exception {
        var result = harness.run(apiDb, new SqlGoldenScenario.ScriptStep("db/23_turn_advance_check.sql",
                SqlGoldenScenario.Kind.CHECK, "p0_17_check", java.time.Duration.ofMinutes(5)));
        harness.assertCheckPassed(result, "p0_17_check");
    }

    @Test
    @Order(5)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API가_만든_DB에서_24_피드_검증이_통과한다() throws Exception {
        // db/24 is the SQL reference of the feed (p0-18:v2) and has no alerts: it expects none in the season it checks, so the Java
        // alerts of this scratch database are removed first (the feed rows, which the check is about, stay).
        var cleared = harness.runner().runSql(apiDb.name(), "DELETE FROM turn_feed WHERE kind = 'NUDGE'", java.time.Duration.ofMinutes(1));
        assertThat(cleared.exitCode()).as(cleared.stderr()).isZero();
        var result = harness.run(apiDb, new SqlGoldenScenario.ScriptStep("db/24_turn_feed_check.sql",
                SqlGoldenScenario.Kind.CHECK, "p0_18_check", java.time.Duration.ofMinutes(5)));
        harness.assertCheckPassed(result, "p0_18_check");
    }
}

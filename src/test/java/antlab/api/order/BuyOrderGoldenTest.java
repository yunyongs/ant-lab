package antlab.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
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
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

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

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "team_buy_api");
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
    private int replayed;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replay(mvc, deferred, monthBlocks);
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        replayed = r.orders();
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
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

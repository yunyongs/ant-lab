package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.code;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.CheckVerdictParser;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 롤백 너머 기록 드릴 골든 — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀). SQL 기준은
 * {@code 20 → 21 → 23 × k → 23_turn_advance_drill(-v drill=1) → 23 × 나머지} 이고, API 시즌은 같은 재생 뒤
 * <b>같은 종목</b>의 라인업 구간을 닫고(드릴 3단계와 같은 문장) 전환 → 409 → 고치기 → 전환 201 → 나머지 전환을 보낸다.
 * 11개 표의 정규화 행 차이가 0이어야 한다. 대사 로그는 시도 번호를 보존해 비교하고 {@code detail} 은 대리 키를 정규화한다
 * (종목은 자연 키, 나머지 번호는 표지). 실패 보고에는 표 이름·키·열 이름·건수만 나온다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.test.default-member.email=phase0@example.invalid", "antlab.test.default-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class TurnRollbackDrillGoldenTest {

    /** 드릴을 끼우는 턴 — 0턴이 아닌 중간(몇 턴 진행한 뒤여도 된다는 드릴 머리 주석). */
    private static final int K = 3;

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.startWithDrill(registry, "turn_drill_api");
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
    JdbcTemplate jdbc;
    @Autowired
    DeferredPathSource deferred;
    @Autowired
    MonthBlockRepository monthBlocks;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replay(mvc, deferred, monthBlocks, SqlGoldenScenario.turnGoldenUntil(K),
                "turn_drill_sql");
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
    }

    private long count(DatabaseHandle db, long season, String sql) throws Exception {
        RowQuery q = new RowQuery("count", sql, List.of(new RowColumn("n", ColumnType.LONG)));
        return (Long) harness.readRows(db, q, season).get(0, "n");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void SQL_드릴과_같은_종목을_닫아_실패_고치기_성공한_API_시즌이_열한_표까지_같다() throws Exception {
        // 1. 두 쪽 모두 k 턴 진행 (SQL 은 prepare 에서 이미 k 번 돌았다)
        for (int t = 0; t < K; t++) {
            assertThat(advance(mvc, apiSeason, t).getStatus()).as("전환 " + t).isEqualTo(201);
        }

        // 2. 드릴이 닫을 종목 — 기준 DB 에서 드릴과 같은 질의(최소 라인업 번호)로 자연 키를 얻는다 (X9)
        RowSet target = harness.readRows(sqlDb, new RowQuery("drill_target",
                "SELECT a.market AS market, a.ticker AS ticker FROM lineup lu"
                        + " JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id"
                        + " JOIN asset a ON a.id = lu.asset_id"
                        + " WHERE lu.season_id = :sid AND lu.end_turn IS NULL AND h.quantity > 0 ORDER BY lu.id LIMIT 1",
                List.of(new RowColumn("market", ColumnType.TEXT), new RowColumn("ticker", ColumnType.TEXT))), sqlSeason);
        assertThat(target.size()).isEqualTo(1);
        String market = (String) target.get(0, "market");
        String ticker = (String) target.get(0, "ticker");

        // 3. SQL: 드릴(실패 → 고치기 → 성공) 판정 PASS, 이어서 나머지 전환으로 FINISHED
        var drill = harness.run(sqlDb, SqlGoldenScenario.rollbackDrill());
        assertThat(drill.process().timedOut()).isFalse();
        assertThat(drill.process().exitCode()).as("드릴 종료 코드").isZero();
        // 드릴의 마지막 판정은 건수 열이 붙은 두 줄(p0_17_drill|total|pass|fail)이라 전용 판독기를 쓴다 (X2)
        assertThat(CheckVerdictParser.parseWithCounts(drill.process().stdout(), "p0_17_drill").passed())
                .as("드릴 판정").isTrue();
        harness.execute(sqlDb, SqlGoldenScenario.turnGoldenFrom(K));

        // 4. Java: 같은 종목의 열린 구간을 닫고(드릴 3단계와 같은 문장) 전환 → 409 → 고치기 → 201 → 나머지
        long before = count(apiDb, apiSeason, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid");
        int closed = jdbc.update("UPDATE lineup SET end_turn = ?, updated_at = now() WHERE season_id = ? AND end_turn IS NULL"
                + " AND asset_id = (SELECT id FROM asset WHERE market = ? AND ticker = ?)", K, apiSeason, market, ticker);
        assertThat(closed).isEqualTo(1);
        MockHttpServletResponse failed = advance(mvc, apiSeason, K);
        assertThat(failed.getStatus()).isEqualTo(409);
        assertThat(code(failed)).isEqualTo("RECONCILIATION_FAILED");
        assertThat(count(apiDb, apiSeason, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid"))
                .as("실패한 전환은 로그 17행만 남긴다").isEqualTo(before + 17);
        int reopened = jdbc.update("UPDATE lineup SET end_turn = NULL, updated_at = now() WHERE season_id = ? AND end_turn IS NOT NULL"
                + " AND asset_id = (SELECT id FROM asset WHERE market = ? AND ticker = ?)", apiSeason, market, ticker);
        assertThat(reopened).as("고치기 UPDATE 가 실제로 한 행을 열었다").isEqualTo(1);
        assertThat(advance(mvc, apiSeason, K).getStatus()).isEqualTo(201);
        for (int t = K + 1; t < SqlGoldenScenario.PATH_LENGTH; t++) {
            assertThat(advance(mvc, apiSeason, t).getStatus()).as("전환 " + t).isEqualTo(201);
        }

        // 5. 열한 표 비교 (대사 로그는 detail 정규화)
        RowSet sqlAssets = harness.readRows(sqlDb, GoldenRowQueries.assetKeys(), sqlSeason);
        RowSet apiAssets = harness.readRows(apiDb, GoldenRowQueries.assetKeys(), apiSeason);
        List<GoldenTable> tables = GoldenRowQueries.allForDrillGolden();
        assertThat(tables).hasSize(11);
        for (GoldenTable table : tables) {
            RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
            RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
            if (table.name().equals("reconciliation_log")) {
                expected = GoldenRowQueries.normalizeLogDetail(expected, sqlAssets);
                actual = GoldenRowQueries.normalizeLogDetail(actual, apiAssets);
            }
            assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
            assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
            RowSetDiff.assertNoDifference(expected, actual, table.spec());
        }

        // 6. 두 DB 모두: 드릴 턴은 시도 {1: 17행·불일치 2, 2: 17 일치}, 다른 턴은 시도 1 뿐 (전체 10 × 17 + 17)
        for (Object[] side : new Object[][] {{sqlDb, sqlSeason}, {apiDb, apiSeason}}) {
            DatabaseHandle db = (DatabaseHandle) side[0];
            long season = (Long) side[1];
            String label = db.equals(sqlDb) ? "SQL" : "API";
            assertThat(count(db, season, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid"))
                    .as(label + " 로그 총 행").isEqualTo(11 * 17);
            assertThat(count(db, season, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid AND turn_no = "
                    + (K + 1) + " AND attempt_no = 1 AND status = 'MISMATCH'")).as(label + " 드릴 턴 시도 1 불일치").isEqualTo(2);
            assertThat(count(db, season, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid AND turn_no = "
                    + (K + 1) + " AND attempt_no = 2 AND status = 'MATCH'")).as(label + " 드릴 턴 시도 2 일치").isEqualTo(17);
            assertThat(count(db, season, "SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid AND turn_no <> "
                    + (K + 1) + " AND attempt_no <> 1")).as(label + " 다른 턴의 재시도").isZero();
            assertThat(count(db, season, "SELECT count(*) AS n FROM season WHERE id = :sid AND status = 'FINISHED'"))
                    .as(label + " 끝났다").isEqualTo(1);
        }
    }
}

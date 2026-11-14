package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.DefaultBearerConfig;
import antlab.support.DeferredPathSource;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TeamBuyGoldenSeason;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
import tools.jackson.databind.JsonNode;

/**
 * 성적표 골든 G2(P0-38) — 롤백 드릴 시즌. 기준은 롤백 드릴 골든과 같다: SQL {@code 20 → 21 → 23 × k → 23_turn_advance_drill → 23 × 나머지},
 * API 시즌은 같은 종목의 라인업 구간을 닫아 전환 실패(409) → 고치기 → 전환 성공 → 나머지 전환. 끝난 두 시즌에서 SQL 시즌의 {@code sr_report} =
 * API 시즌의 {@code sr_report}(37열 전부, 이 시즌은 FEED 가 없어 출발점 열도 같다) = API 응답이고, 대사 상태가 실패 이력을 보인다:
 * 불일치 행 &gt; 0, 대사 묶음 = 경로 길이 + 실패 횟수(10 + 1), 두 DB 모두 턴 1..10 마다 17행 일치 묶음이 정확히 하나.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.test.default-member.email=phase0@example.invalid", "antlab.test.default-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class SeasonReportDrillGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.startWithDrill(registry, "report_drill_api");
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
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;

    private static final int K = 3;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replay(mvc, deferred, monthBlocks, SqlGoldenScenario.turnGoldenUntil(K),
                "report_drill_sql");
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        for (int t = 0; t < K; t++) {
            assertThat(advance(mvc, apiSeason, t).getStatus()).as("전환 " + t).isEqualTo(201);
        }
        RowSet target = harness.readRows(sqlDb, new RowQuery("drill_target",
                "SELECT a.market AS market, a.ticker AS ticker FROM lineup lu"
                        + " JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id"
                        + " JOIN asset a ON a.id = lu.asset_id"
                        + " WHERE lu.season_id = :sid AND lu.end_turn IS NULL AND h.quantity > 0 ORDER BY lu.id LIMIT 1",
                List.of(new RowColumn("market", ColumnType.TEXT), new RowColumn("ticker", ColumnType.TEXT))), sqlSeason);
        assertThat(target.size()).isEqualTo(1);
        var drill = harness.run(sqlDb, SqlGoldenScenario.rollbackDrill());
        assertThat(drill.process().exitCode()).as("드릴 종료 코드").isZero();
        harness.execute(sqlDb, SqlGoldenScenario.turnGoldenFrom(K));

        int closed = jdbc.update("UPDATE lineup SET end_turn = ?, updated_at = now() WHERE season_id = ? AND end_turn IS NULL"
                + " AND asset_id = (SELECT id FROM asset WHERE market = ? AND ticker = ?)", K, apiSeason, target.get(0, "market"),
                target.get(0, "ticker"));
        assertThat(closed).isEqualTo(1);
        assertThat(advance(mvc, apiSeason, K).getStatus()).isEqualTo(409);
        int reopened = jdbc.update("UPDATE lineup SET end_turn = NULL, updated_at = now() WHERE season_id = ? AND end_turn IS NOT NULL"
                + " AND asset_id = (SELECT id FROM asset WHERE market = ? AND ticker = ?)", apiSeason, target.get(0, "market"),
                target.get(0, "ticker"));
        assertThat(reopened).isEqualTo(1);
        assertThat(advance(mvc, apiSeason, K).getStatus()).isEqualTo(201);
        for (int t = K + 1; t < SqlGoldenScenario.PATH_LENGTH; t++) {
            assertThat(advance(mvc, apiSeason, t).getStatus()).as("전환 " + t).isEqualTo(201);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G2_드릴_시즌의_성적표가_SQL과_Java와_응답에서_서른일곱_열_모두_같고_대사_이력이_보인다() throws Exception {
        RowSet sql = SeasonReportGoldenSupport.sqlReport(harness, sqlDb, sqlSeason);
        RowSet java = SeasonReportGoldenSupport.sqlReport(harness, apiDb, apiSeason);
        assertThat(SeasonReportGoldenSupport.differences(sql, java, Set.of())).as("SQL 시즌과 API 시즌에서 다른 열").isEmpty();
        JsonNode response = SeasonReportGoldenSupport.apiReport(mvc, apiSeason);
        assertThat(SeasonReportGoldenSupport.differences(sql, response)).as("응답과 SQL 결과에서 다른 열").isEmpty();
        SeasonReportGoldenSupport.assertResponseFormat(response);

        // 대사 상태: 드릴이 만든 불일치와 재시도가 숫자로 보인다
        assertThat(response.get("reconMismatchRows").asInt()).as("불일치 행").isPositive();
        assertThat(response.get("reconTurnBundles").asInt()).as("묶음 = 경로 길이 + 실패 1").isEqualTo(SqlGoldenScenario.PATH_LENGTH + 1);
        assertThat(response.get("reconManualBundles").asInt()).isZero();
        assertThat(response.get("reconOutOfRangeRows").asInt()).isZero();
        SeasonReportGoldenSupport.assertOneMatchBundlePerTurn(harness, sqlDb, sqlSeason, SqlGoldenScenario.PATH_LENGTH, "SQL");
        SeasonReportGoldenSupport.assertOneMatchBundlePerTurn(harness, apiDb, apiSeason, SqlGoldenScenario.PATH_LENGTH, "API");
        // 이 시즌은 FEED 가 없어 출발점 의존 열도 같다 — 위 비교가 이미 포함하며, 비어 있지 않음만 확인
        assertThat(((BigDecimal) sql.get(0, "total_fee")).signum()).isPositive();
        assertThat((Long) sql.get(0, "report_turns")).isEqualTo((long) SqlGoldenScenario.PATH_LENGTH);
    }
}

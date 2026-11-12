package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.history.MonthBlockRepository;
import antlab.season.LineupChangeService;
import antlab.season.SeedGenerator;
import antlab.support.DeferredPathSource;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TeamBuyGoldenSeason;
import java.math.BigDecimal;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * 성적표 골든 G1(P0-38) — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 *
 * <p>기준은 전술 변경 골든과 같은 {@code tradeGolden}(20 → 21 → 23 × 5 → 24 → 25 → 25 검증) 시즌이고, API 시즌은 그 행동을 같은 순서로
 * 재생한다(전술 변경은 POST, 라인업 변경은 내부 서비스). 양쪽을 10턴까지 끝낸 뒤 세 가지를 본다:
 * <ol>
 *   <li>(a) SQL 시즌의 {@code sr_report}(SQL DB) = API 시즌의 {@code sr_report}(API DB) — 피드 출발점이 다른 세 열
 *       ({@code nudge_share}·{@code nudge_same_turn_share}·{@code platform_nudge_fee})만 빼고 값이 같고, 뺀 세 열은 반대쪽 값을 따로 단언한다.</li>
 *   <li>(b) API 응답 = API DB 의 {@code sr_report} 37열 전부(금액 {@code compareTo}, 금액은 소수점 없는 문자열, 비율은 8자리, null 은 null).</li>
 * </ol>
 * 값(금액·가격)은 출력하지 않는다. 어긋난 열 이름만 보고한다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class SeasonReportTradeGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "report_trade_api");
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
    LineupChangeService lineupChanges;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replayTrades(mvc, deferred, monthBlocks, SqlGoldenScenario.tradeGolden(),
                "report_trade_sql", (season, turn, asset, position) -> lineupChanges.change(season, turn, asset, Position.valueOf(position)));
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        // 양쪽을 끝까지 진행한다 (SQL 은 5턴까지 돌았다)
        harness.execute(sqlDb, SqlGoldenScenario.advanceTurns(SqlGoldenScenario.PATH_LENGTH - 5));
        for (int turn = 5; turn < SqlGoldenScenario.PATH_LENGTH; turn++) {
            MockHttpServletResponse response = advance(mvc, apiSeason, turn);
            assertThat(response.getStatus()).as("전환 " + (turn + 1)).isEqualTo(201);
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G1a_SQL_시즌과_API_시즌의_성적표가_출발점_세_열만_빼고_같다() throws Exception {
        RowSet sql = SeasonReportGoldenSupport.sqlReport(harness, sqlDb, sqlSeason);
        RowSet java = SeasonReportGoldenSupport.sqlReport(harness, apiDb, apiSeason);
        assertThat(SeasonReportGoldenSupport.differences(sql, java, SeasonReportGoldenSupport.STARTING_POINT_COLUMNS))
                .as("SQL 시즌과 API 시즌에서 다른 열").isEmpty();
        // 비어 있어 헛으로 일치하지 않게: 대금·비용·변경이 모두 있다
        assertThat(((BigDecimal) sql.get(0, "trade_notional")).signum()).isPositive();
        assertThat(((BigDecimal) sql.get(0, "total_fee")).signum()).isPositive();
        assertThat(((BigDecimal) sql.get(0, "team_fee")).signum()).isPositive();
        assertThat((Long) sql.get(0, "formation_changes")).isEqualTo(1L);
        assertThat((Long) sql.get(0, "formation_change_orders")).isEqualTo(3L);
        assertThat((Long) sql.get(0, "recon_turn_bundles")).isEqualTo((long) SqlGoldenScenario.PATH_LENGTH);

        // 뺀 세 열의 반대쪽 단언: SQL 은 FEED·RANKING 출발점이 있어 독려 몫이 양수, Java 는 전부 DIRECT 라 0
        assertThat(((BigDecimal) sql.get(0, "nudge_share")).signum()).as("SQL 독려 비중").isPositive();
        assertThat(((BigDecimal) java.get(0, "nudge_share")).signum()).as("Java 독려 비중").isZero();
        assertThat(((BigDecimal) java.get(0, "nudge_same_turn_share")).signum()).as("Java 같은 턴 독려 비중").isZero();
        assertThat(((BigDecimal) java.get(0, "platform_nudge_fee")).signum()).as("Java 플랫폼 몫").isZero();
        RowSet expected = harness.readRows(sqlDb, new RowQuery("platform_fee", """
                SELECT coalesce(sum(e.fee), 0) AS fee FROM orders o JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = :sid AND o.turn_no >= 1 AND o.turn_no < (SELECT current_turn_no FROM season WHERE id = :sid)
                  AND o.source IN ('RANKING', 'ALERT')""", List.of(new RowColumn("fee", ColumnType.DECIMAL))), sqlSeason);
        assertThat((BigDecimal) sql.get(0, "platform_nudge_fee")).as("SQL 플랫폼 몫 = RANKING·ALERT 체결 수수료")
                .isEqualByComparingTo((BigDecimal) expected.get(0, "fee"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G1b_API_응답이_API_DB의_성적표_서른일곱_열과_같다() throws Exception {
        RowSet java = SeasonReportGoldenSupport.sqlReport(harness, apiDb, apiSeason);
        JsonNode response = SeasonReportGoldenSupport.apiReport(mvc, apiSeason);
        assertThat(SeasonReportGoldenSupport.differences(java, response)).as("응답과 정의 결과에서 다른 열").isEmpty();
        SeasonReportGoldenSupport.assertResponseFormat(response);
        // 금액 열은 소수점 없는 문자열이다
        for (String name : List.of("initialCash", "finalValue", "baselineFinalValue", "tradeNotional", "totalFee", "teamFee",
                "formationChangeFee", "platformNudgeFee")) {
            assertThat(response.get(name).asString()).as(name).matches("-?\\d+");
        }
    }
}

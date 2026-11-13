package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.ComparisonSpec;
import antlab.support.DefaultBearerConfig;
import antlab.support.DeferredPathSource;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.RowSetDiff;
import antlab.support.RuleResultAssert;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScriptStep;
import antlab.support.TeamBuyGoldenSeason;
import antlab.support.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대사 골든 — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 * P0-31 골든과 같은 준비({@link TeamBuyGoldenSeason})로 만든 API 시즌(0턴 팀) 위에서:
 * ① psql 로 db/22 의 {@code recon_rules(시즌, 0)} 15행과 Java {@code evaluateManual} 15행의 9컬럼이 규칙마다 같다
 * ② 개발 엔드포인트가 기록한 시도와 {@code db/22_reconcile_season.sql} 이 기록한 시도의 15행이 id·시도 번호·시각만 빼고 같다
 * ③ 그 DB 에서 db/22 검증이 통과한다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class})
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class ReconciliationGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "recon_api");
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
    TestTokens tokens;
    @Autowired
    DeferredPathSource deferred;
    @Autowired
    MonthBlockRepository monthBlocks;
    @Autowired
    ReconciliationService service;
    @Autowired
    PlatformTransactionManager txManager;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private long apiSeason;
    private int replayed;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replay(mvc, deferred, monthBlocks);
        apiSeason = r.apiSeason();
        replayed = r.orders();
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private static final RowQuery RULES = new RowQuery("recon_rules", """
            SELECT rule_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count,
                   classification, detail
            FROM pg_temp.recon_rules(:sid, 0::smallint) ORDER BY rule_no""", List.of(
            new RowColumn("rule_no", ColumnType.LONG), new RowColumn("check_code", ColumnType.TEXT),
            new RowColumn("status", ColumnType.TEXT), new RowColumn("expected_value", ColumnType.DECIMAL),
            new RowColumn("actual_value", ColumnType.DECIMAL), new RowColumn("diff_value", ColumnType.DECIMAL),
            new RowColumn("mismatch_count", ColumnType.LONG), new RowColumn("classification", ColumnType.TEXT),
            new RowColumn("detail", ColumnType.JSON)));

    private List<RuleResult> sqlRules() throws Exception {
        RowSet rows = harness.queryWithDefinitions(apiDb, SqlGoldenScenario.RECONCILE_DEFINITIONS, RULES, apiSeason);
        List<RuleResult> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object detail = rows.get(i, "detail");
            String classification = (String) rows.get(i, "classification");
            out.add(new RuleResult(((Long) rows.get(i, "rule_no")).intValue(),
                    CheckCode.valueOf((String) rows.get(i, "check_code")),
                    ReconciliationStatus.valueOf((String) rows.get(i, "status")),
                    (java.math.BigDecimal) rows.get(i, "expected_value"), (java.math.BigDecimal) rows.get(i, "actual_value"),
                    (java.math.BigDecimal) rows.get(i, "diff_value"), ((Long) rows.get(i, "mismatch_count")).intValue(),
                    classification == null ? null : ReconciliationClassification.valueOf(classification),
                    detail == null ? null : detail.toString()));
        }
        return out;
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API_시즌에서_Java_규칙_15개와_psql_recon_rules의_9컬럼이_같다() throws Exception {
        assertThat(replayed).as("재생한 주문 수").isPositive();
        List<RuleResult> sql = sqlRules();
        List<RuleResult> java = new TransactionTemplate(txManager).execute(s -> service.evaluateManual(apiSeason, 0));
        assertThat(sql).hasSize(15);
        assertThat(java).hasSize(15);
        RuleResultAssert.assertSame(sql, java);
        // 0턴 팀 시즌은 SQL 과 같은 방식으로 만든 것이므로 15개 모두 일치한다
        assertThat(RuleResultAssert.mismatchCodes(java)).isEmpty();
        // 스칼라 규칙의 자릿수(실측 X6) — 값은 적지 않고 자릿수만 본다
        java.stream().filter(r -> r.expectedValue() != null).forEach(r ->
                System.out.println("[X6] 규칙 " + r.ruleNo() + " 기대 scale=" + r.expectedValue().scale() + " 실제 scale="
                        + r.actualValue().scale()));
    }

    private static final List<RowColumn> LOG_COLUMNS = List.of(new RowColumn("check_code", ColumnType.TEXT),
            new RowColumn("status", ColumnType.TEXT), new RowColumn("expected_value", ColumnType.DECIMAL),
            new RowColumn("actual_value", ColumnType.DECIMAL), new RowColumn("diff_value", ColumnType.DECIMAL),
            new RowColumn("mismatch_count", ColumnType.LONG), new RowColumn("classification", ColumnType.TEXT),
            new RowColumn("detail", ColumnType.JSON));

    private static RowQuery attemptQuery(int attempt) {
        return new RowQuery("reconciliation_log_" + attempt, """
                SELECT check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification, detail
                FROM reconciliation_log WHERE season_id = :sid AND attempt_no = %d""".formatted(attempt), LOG_COLUMNS);
    }

    private int maxAttempt() throws Exception {
        RowQuery q = new RowQuery("max_attempt", "SELECT coalesce(max(attempt_no), 0) AS n FROM reconciliation_log WHERE season_id = :sid",
                List.of(new RowColumn("n", ColumnType.LONG)));
        return ((Long) harness.readRows(apiDb, q, apiSeason).get(0, "n")).intValue();
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 개발_엔드포인트의_기록과_SQL_수동_대사의_기록이_한_번호_공간에서_행까지_같다() throws Exception {
        int before = maxAttempt();
        MockHttpServletResponse r = mvc.post().uri("/api/v1/dev/seasons/" + apiSeason + "/reconciliation")
                .header("Authorization", "Bearer " + tokens.admin(TestTokens.DEFAULT_SUBJECT))
                .header(antlab.api.dev.DevReconciliationController.DEV_HEADER, antlab.api.dev.DevReconciliationController.DEV_HEADER_VALUE)
                .exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        int javaAttempt = JsonMapper.shared().readTree(r.getContentAsString()).get("attemptNo").asInt();
        assertThat(javaAttempt).isEqualTo(before + 1);

        // 같은 API DB 에서 SQL 수동 대사(연습 시즌 이메일 = 고정 회원)를 돌리면 다음 번호를 받는다
        var result = harness.run(apiDb, new ScriptStep("db/22_reconcile_season.sql", Kind.RUN, null, Duration.ofMinutes(5)));
        assertThat(result.process().exitCode()).isZero();
        assertThat(maxAttempt()).isEqualTo(before + 2);

        ComparisonSpec spec = ComparisonSpec.named("reconciliation_log").keys("check_code")
                .values("status", "expected_value", "actual_value", "diff_value", "mismatch_count", "classification", "detail")
                .build();
        RowSet java = harness.readRows(apiDb, attemptQuery(javaAttempt), apiSeason);
        RowSet sql = harness.readRows(apiDb, attemptQuery(before + 2), apiSeason);
        assertThat(java.size()).isEqualTo(15);
        assertThat(sql.size()).isEqualTo(15);
        RowSetDiff.assertNoDifference(sql, java, spec);
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void API가_만든_DB에서_대사_검증이_통과한다() throws Exception {
        var result = harness.run(apiDb, new ScriptStep("db/22_reconcile_season_check.sql", Kind.CHECK, "p0_16_check",
                Duration.ofMinutes(5)));
        harness.assertCheckPassed(result, "p0_16_check");
    }
}

package antlab.scoring;

import static antlab.scoring.ReconciliationApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import antlab.support.RuleResultAssert;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 규칙 12(턴 가격)의 인라인 재귀 CTE 확인: 1턴 이후 가격(합성 수익률·환율)에서 Java 쿼리가 낸 기대 집합이
 * SQL 정의 함수({@code recon_expected_price} → {@code recon_price})와 같은 값이다. 저장 가격을 SQL 함수가 낸 값으로 채우면
 * Java 도 모두 일치해야 하고, 한 곳을 바꾸면 SQL 과 같은 종류로 불일치해야 한다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationPriceParityTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    ReconciliationService service;

    private interface Body {
        void run(long season);
    }

    /** 3턴까지의 수익률·환율을 만들고(합성) 저장 가격을 SQL 기대값으로 채운 상태에서 본문을 돌린 뒤 롤백한다. */
    private void inThreeTurns(Body body) throws Exception {
        long season = startSeason(mvc);
        new TransactionTemplate(txManager).execute(status -> {
            try {
                ReconDefinitions.load(jdbc);
                jdbc.execute("""
                        INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
                        SELECT ap.asset_id, sp.month_block_id,
                               ((ap.asset_id * 7 + sp.turn_no * 13) % 41 - 20) / 400.0
                        FROM season s
                        JOIN asset_position ap ON ap.base_date = s.base_date
                        JOIN season_path sp ON sp.season_id = s.id AND sp.turn_no BETWEEN 1 AND 3
                        WHERE s.id = {S} ON CONFLICT DO NOTHING""".replace("{S}", Long.toString(season)));
                jdbc.execute("""
                        INSERT INTO fx_month (month_block_id, currency, fx_change)
                        SELECT sp.month_block_id, 'USD', (sp.turn_no * 11 % 17 - 8) / 300.0
                        FROM season_path sp WHERE sp.season_id = {S} AND sp.turn_no BETWEEN 1 AND 3 ON CONFLICT DO NOTHING""".replace("{S}", Long.toString(season)));
                jdbc.execute("""
                        INSERT INTO turn_price (season_id, turn_no, asset_id, price)
                        SELECT {S}, x.turn_no, x.asset_id, x.price
                        FROM pg_temp.recon_expected_price({S}, 3::smallint) x WHERE x.turn_no > 0 AND x.price IS NOT NULL""".replace("{S}", Long.toString(season)));
                jdbc.update("UPDATE season SET current_turn_no = 3 WHERE id = ?", season);
                body.run(season);
            } finally {
                status.setRollbackOnly();
            }
            return null;
        });
    }

    private void assertParity(long season, Set<String> expectedKinds) {
        List<RuleResult> sql = ReconDefinitions.sqlRules(jdbc, season, 3);
        List<RuleResult> java = service.evaluateManual(season, 3);
        RuleResultAssert.assertSame(sql, java);
        RuleResult r12 = java.stream().filter(r -> r.checkCode() == CheckCode.TURN_PRICE).findFirst().orElseThrow();
        for (String kind : expectedKinds) {
            assertThat(r12.detail()).contains("\"" + kind + "\"");
        }
        if (expectedKinds.isEmpty()) {
            assertThat(r12.status()).isEqualTo(ReconciliationStatus.MATCH);
        }
    }

    @Test
    void SQL_함수가_낸_가격으로_채우면_Java_규칙_12도_모두_일치한다() throws Exception {
        inThreeTurns(season -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no BETWEEN 1 AND 3",
                    Long.class, season)).isEqualTo(12L);
            assertParity(season, Set.of());
        });
    }

    @Test
    void 한_가격을_바꾸면_PRICE_행을_빼면_MISSING_검사_턴_뒤_행은_FUTURE로_SQL과_같다() throws Exception {
        inThreeTurns(season -> {
            jdbc.update("UPDATE turn_price SET price = price + 1 WHERE season_id = ? AND turn_no = 2 AND asset_id = (SELECT min(asset_id) FROM turn_price WHERE season_id = ?)",
                    season, season);
            assertParity(season, Set.of("PRICE"));
            jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND turn_no = 3 AND asset_id = (SELECT max(asset_id) FROM turn_price WHERE season_id = ?)",
                    season, season);
            assertParity(season, Set.of("PRICE", "MISSING"));
            jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) SELECT season_id, 4, asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = 3 LIMIT 1",
                    season);
            assertParity(season, Set.of("PRICE", "MISSING", "FUTURE"));
        });
    }

    @Test
    void 수익률_행이_없으면_NO_RETURN으로_SQL과_같이_보고한다() throws Exception {
        inThreeTurns(season -> {
            jdbc.update("DELETE FROM asset_month_return WHERE month_block_id = (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 2)"
                    + " AND asset_id = (SELECT min(asset_id) FROM turn_price WHERE season_id = ?)", season, season);
            assertParity(season, Set.of("NO_RETURN"));
        });
    }
}

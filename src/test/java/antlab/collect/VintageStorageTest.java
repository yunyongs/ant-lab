package antlab.collect;

import static antlab.collect.CollectedFixtures.DAY_1;
import static antlab.collect.CollectedFixtures.RUN_1;
import static antlab.collect.CollectedFixtures.RUN_2;
import static antlab.collect.CollectedFixtures.RUN_3;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * Vintage storage and the point-in-time read, on synthetic closes. Later rows are added, never changed, and the
 * as-of query returns what was known at an instant. The query text here is the reference for the M1-09 repository
 * method (largest vintage_no with observed_at at or before the instant).
 */
@JpaMappingTest
@Sql("/fixtures/history_pool.sql")
class VintageStorageTest {

    private static final String AS_OF = """
            SELECT DISTINCT ON (trade_date) trade_date, vintage_no, close_price
            FROM asset_daily_price
            WHERE asset_id = (SELECT id FROM asset WHERE market = 'KR' AND ticker = '000660')
              AND price_basis = 'ADJUSTED' AND observed_at <= ?::timestamptz
            ORDER BY trade_date, vintage_no DESC
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobRunRepository jobRuns;

    private CollectedFixtures fixtures;

    private long insertVintages() {
        fixtures = new CollectedFixtures(jdbc);
        long run1 = fixtures.run(RUN_1);
        long run2 = fixtures.run(RUN_2);
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "100.0000", 1, RUN_1, run1);
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "90.0000", 2, RUN_2, run2);
        return run2;
    }

    private BigDecimal knownAt(String instant) {
        List<Map<String, Object>> rows = jdbc.queryForList(AS_OF, instant);
        return rows.isEmpty() ? null : (BigDecimal) rows.get(0).get("close_price");
    }

    @Test
    void 두_번째_빈티지를_넣어도_첫_빈티지_행은_모든_열이_그대로다() {
        fixtures = new CollectedFixtures(jdbc);
        long run1 = fixtures.run(RUN_1);
        long first = fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "100.0000", 1, RUN_1, run1);
        String before = jdbc.queryForObject("SELECT to_jsonb(t)::text FROM asset_daily_price t WHERE id = ?",
                String.class, first);

        long run2 = fixtures.run(RUN_2);
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "90.0000", 2, RUN_2, run2);

        assertThat(jdbc.queryForObject("SELECT to_jsonb(t)::text FROM asset_daily_price t WHERE id = ?",
                String.class, first)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_daily_price", Integer.class)).isEqualTo(2);
    }

    @Test
    void 시점_기준_읽기는_그_시각에_알려진_빈티지를_돌려준다() {
        insertVintages();

        assertThat(knownAt("2030-01-03 13:59:59+09")).as("첫 관측 전").isNull();
        assertThat(knownAt(RUN_1)).as("첫 관측 시각").isEqualByComparingTo("100");
        assertThat(knownAt("2030-01-03 23:00:00+09")).as("둘째 관측 전").isEqualByComparingTo("100");
        assertThat(knownAt(RUN_2)).as("둘째 관측 시각").isEqualByComparingTo("90");
        assertThat(knownAt("2031-01-01 00:00:00+09")).as("최신").isEqualByComparingTo("90");
    }

    @Test
    void 값이_A에서_B로_다시_A로_돌아와도_사이_시점은_B를_돌려준다() {
        long run2 = insertVintages();
        long run3 = fixtures.run(RUN_3);
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "100.0000", 3, RUN_3, run3);

        assertThat(knownAt(RUN_2)).isEqualByComparingTo("90");
        assertThat(knownAt(RUN_3)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT max(vintage_no) FROM asset_daily_price", Integer.class)).isEqualTo(3);
        assertThat(run2).isPositive();
    }

    @Test
    void 다른_가격_기준은_같은_거래일에도_따로_읽힌다() {
        insertVintages();
        long run = fixtures.run(RUN_1);
        fixtures.price("KR", "000660", DAY_1, "RAW", null, "55.0000", 1, RUN_1, run);

        assertThat(jdbc.queryForList(AS_OF, "2031-01-01 00:00:00+09")).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT close_price FROM asset_daily_price WHERE price_basis = 'RAW'
                """, BigDecimal.class)).isEqualByComparingTo("55");
    }

    @Test
    void 가장_최근_실행은_시작일시로_찾는다() {
        long newest = insertVintages();

        assertThat(jobRuns.findFirstByJobNameOrderByStartedAtDesc(JobName.KIS_DAILY_PRICE))
                .get().extracting(JobRun::getId).isEqualTo(newest);
        assertThat(jobRuns.findById(newest)).get().extracting(JobRun::getStatus).isEqualTo(JobStatus.SUCCEEDED);
    }
}

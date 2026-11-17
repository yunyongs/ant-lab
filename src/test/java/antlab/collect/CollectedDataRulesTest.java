package antlab.collect;

import static antlab.collect.CollectedFixtures.DAY_1;
import static antlab.collect.CollectedFixtures.RUN_1;
import static antlab.collect.CollectedFixtures.RUN_2;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * Rules C1-C6 of erd 10.5, which DDL cannot enforce. Each query returns no row on a correct fixture and the
 * planted violation is caught. These are the reference queries for the writer (M1-06).
 */
@JpaMappingTest
@Sql("/fixtures/history_pool.sql")
class CollectedDataRulesTest {

    /** C1: vintage_no values of one (asset, date, basis) are 1..n without gaps. */
    static final String C1 = """
            SELECT asset_id, trade_date, price_basis FROM asset_daily_price
            GROUP BY asset_id, trade_date, price_basis
            HAVING min(vintage_no) <> 1 OR max(vintage_no) <> count(*)
            """;

    private static final String NEXT_VINTAGE = """
            FROM asset_daily_price a JOIN asset_daily_price b
              ON b.asset_id = a.asset_id AND b.trade_date = a.trade_date AND b.price_basis = a.price_basis
             AND b.vintage_no = a.vintage_no + 1
            """;

    /** C2: observed_at strictly increases with vintage_no. */
    static final String C2 = "SELECT a.id " + NEXT_VINTAGE + " WHERE b.observed_at <= a.observed_at";

    /** C3: consecutive vintages differ in close_price. */
    static final String C3 = "SELECT a.id " + NEXT_VINTAGE + " WHERE b.close_price = a.close_price";

    /** C4: exchange_code is NULL exactly for KR assets. */
    static final String C4 = """
            SELECT p.id FROM asset_daily_price p JOIN asset a ON a.id = p.asset_id
            WHERE (a.market = 'KR') <> (p.exchange_code IS NULL)
            """;

    /** C5: KR ETF closes are RAW, every other close is ADJUSTED. */
    static final String C5 = """
            SELECT p.id FROM asset_daily_price p JOIN asset a ON a.id = p.asset_id
            WHERE p.price_basis <> CASE WHEN a.market = 'KR' AND a.asset_type = 'ETF' THEN 'RAW' ELSE 'ADJUSTED' END
            """;

    /** C6: observed_at is the started_at of the run that fetched the row. */
    static final String C6 = """
            SELECT p.id FROM asset_daily_price p JOIN job_run r ON r.id = p.job_run_id
            WHERE p.observed_at <> r.started_at
            """;

    private static final List<String> RULES = List.of(C1, C2, C3, C4, C5, C6);

    @Autowired
    JdbcTemplate jdbc;

    private CollectedFixtures fixtures;
    private long run1;
    private long run2;

    private void correctFixture() {
        fixtures = new CollectedFixtures(jdbc);
        run1 = fixtures.run(RUN_1);
        run2 = fixtures.run(RUN_2);
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "100.0000", 1, RUN_1, run1);
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "90.0000", 2, RUN_2, run2);
        fixtures.price("KR", "069500", DAY_1, "RAW", null, "35.0000", 1, RUN_1, run1);
        fixtures.price("US", "SPY", DAY_1, "ADJUSTED", "AMS", "500.5000", 1, RUN_1, run1);
        fixtures.price("US", "BRK.B", DAY_1, "ADJUSTED", "NYS", "400.0000", 1, RUN_1, run1);
    }

    private int violations(String rule) {
        return jdbc.queryForList(rule).size();
    }

    @Test
    void 올바른_고정본에서는_여섯_규칙이_모두_0행이다() {
        correctFixture();

        for (String rule : RULES) {
            assertThat(violations(rule)).as(rule).isZero();
        }
    }

    @Test
    void C1_빈티지_번호에_빈틈이_있으면_잡는다() {
        correctFixture();
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "80.0000", 4, "2030-01-06 09:00:00+09", run2);

        assertThat(violations(C1)).isEqualTo(1);
    }

    @Test
    void C1_첫_빈티지가_1이_아니면_잡는다() {
        correctFixture();
        fixtures.price("KR", "069500", "2030-01-01", "RAW", null, "34.0000", 2, RUN_1, run1);

        assertThat(violations(C1)).isEqualTo(1);
    }

    @Test
    void C2_관측일시가_늘지_않으면_잡는다() {
        correctFixture();
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "95.0000", 3, RUN_2, run2);

        assertThat(violations(C2)).isEqualTo(1);
    }

    @Test
    void C3_이어지는_빈티지의_종가가_같으면_잡는다() {
        correctFixture();
        fixtures.price("KR", "000660", DAY_1, "ADJUSTED", null, "90.0000", 3, "2030-01-06 09:00:00+09",
                fixtures.run("2030-01-06 09:00:00+09"));

        assertThat(violations(C3)).isEqualTo(1);
    }

    @Test
    void C4_한국_종목에_거래소_코드가_있으면_잡는다() {
        correctFixture();
        fixtures.price("KR", "069500", "2030-01-01", "RAW", "NAS", "34.0000", 1, RUN_1, run1);

        assertThat(violations(C4)).isEqualTo(1);
    }

    @Test
    void C4_미국_종목에_거래소_코드가_없으면_잡는다() {
        correctFixture();
        fixtures.price("US", "SPY", "2030-01-01", "ADJUSTED", null, "499.0000", 1, RUN_1, run1);

        assertThat(violations(C4)).isEqualTo(1);
    }

    @Test
    void C5_한국_ETF가_수정주가이거나_한국_주식이_원본이면_잡는다() {
        correctFixture();
        fixtures.price("KR", "069500", "2030-01-01", "ADJUSTED", null, "34.0000", 1, RUN_1, run1);
        fixtures.price("KR", "000660", "2030-01-01", "RAW", null, "99.0000", 1, RUN_1, run1);

        assertThat(violations(C5)).isEqualTo(2);
    }

    @Test
    void C5_미국_종목이_원본이면_잡는다() {
        correctFixture();
        fixtures.price("US", "SPY", "2030-01-01", "RAW", "AMS", "499.0000", 1, RUN_1, run1);

        assertThat(violations(C5)).isEqualTo(1);
    }

    @Test
    void C6_관측일시가_실행_시작일시와_다르면_잡는다() {
        correctFixture();
        fixtures.price("KR", "069500", "2030-01-01", "RAW", null, "34.0000", 1, RUN_2, run1);

        assertThat(violations(C6)).isEqualTo(1);
    }
}

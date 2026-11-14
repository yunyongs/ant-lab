package antlab.common.money;

import static antlab.support.SqlFunctionParity.exactCase;
import static antlab.support.SqlFunctionParity.lit;
import static antlab.support.SqlFunctionParity.valueCase;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenCleanup;
import antlab.support.SqlFunctionParity;
import antlab.support.SqlFunctionParity.Case;
import antlab.support.SqlGoldenHarness;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The repository's pure SQL functions (db/21 buy definitions, db/23 {@code ta_drawdown}) against {@link MoneyMath}
 * and a hand-computed expected value, through the shared {@link SqlFunctionParity} base. One input per case feeds
 * both sides; the expected values are worked out by hand in the comments.
 */
class MoneyMathSqlFunctionParityTest {

    static PostgreSQLContainer container;

    @BeforeAll
    static void start() {
        container = SqlGoldenHarness.newContainer();
        container.start();
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(null, container);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 매수_순수함수_다섯개가_Java_SQL_기대값과_같다() throws Exception {
        List<Case> cases = new ArrayList<>();
        // market, KR rate, US rate, FX spread rate → expected combined rate (KR: the KR rate; US: US + FX)
        String[][] rates = {{"KR", "0.003", "0.004", "0.001", "0.00300000"}, {"US", "0.003", "0.004", "0.001", "0.00500000"},
                {"US", "0.7", "0.6", "0.6", "1.20000000"}};
        for (int i = 0; i < rates.length; i++) {
            String[] r = rates[i];
            cases.add(exactCase("buy_rate." + i,
                    "pg_temp.buy_rate(" + lit(r[0]) + ", " + lit(bd(r[1])) + ", " + lit(bd(r[2])) + ", " + lit(bd(r[3])) + ")",
                    MoneyMath.feeRate(r[0], bd(r[1]), bd(r[2]), bd(r[3])), r[4]));
        }
        // price, quantity, rate → 10 × 0.05 = 0.5 rounds away from zero to 1; 0.49 to 0; 10 × 1.2 = 12
        String[][] fees = {{"10", "1", "0.05", "1"}, {"10", "1", "0.049", "0"}, {"10", "1", "1.2", "12"}};
        for (int i = 0; i < fees.length; i++) {
            String[] f = fees[i];
            int quantity = Integer.parseInt(f[1]);
            cases.add(valueCase("buy_fee." + i,
                    "pg_temp.buy_fee(" + lit(bd(f[0])) + ", " + lit(quantity) + ", " + lit(bd(f[2])) + ")",
                    MoneyMath.fee(bd(f[0]), quantity, bd(f[2])), f[3]));
        }
        // available, notional, fee → 10 + 1 <= 11 is true (1); 10 + 1 <= 10 is false (0)
        String[][] affordables = {{"11", "10", "1", "1"}, {"10", "10", "1", "0"}};
        for (int i = 0; i < affordables.length; i++) {
            String[] a = affordables[i];
            BigDecimal java = MoneyMath.affordable(bd(a[0]), bd(a[1]), bd(a[2])) ? BigDecimal.ONE : BigDecimal.ZERO;
            cases.add(valueCase("buy_affordable." + i,
                    "CASE WHEN pg_temp.buy_affordable(" + lit(bd(a[0])) + ", " + lit(bd(a[1])) + ", " + lit(bd(a[2]))
                            + ") THEN 1 ELSE 0 END",
                    java, a[3]));
        }
        // old quantity, old average, new quantity, price → (200 + 101) / 3 = 100.3333…; 14 / 2 = 7; (1.0001 + 2) / 2 = 1.50005 → 1.5001
        String[][] averages = {{"2", "100", "1", "101", "100.3333"}, {"0", "0", "2", "7", "7.0000"},
                {"1", "1.0001", "1", "2", "1.5001"}};
        for (int i = 0; i < averages.length; i++) {
            String[] v = averages[i];
            int oldQuantity = Integer.parseInt(v[0]);
            int quantity = Integer.parseInt(v[2]);
            cases.add(valueCase("buy_avg_price." + i,
                    "pg_temp.buy_avg_price(" + lit(oldQuantity) + ", " + lit(bd(v[1])) + ", " + lit(quantity) + ", " + lit(bd(v[3])) + ")",
                    MoneyMath.avgPrice(oldQuantity, bd(v[1]), quantity, bd(v[3])), v[4]));
        }
        // budget, price, rate → 11 / 5.5 = 2; 100 / 10.5 = 9.52 → 9; 100 / 22 = 4.54 → 4
        String[][] quantities = {{"11", "5", "0.10", "2"}, {"100", "10", "0.05", "9"}, {"100", "10", "1.2", "4"}};
        for (int i = 0; i < quantities.length; i++) {
            String[] q = quantities[i];
            cases.add(valueCase("team_qty." + i,
                    "pg_temp.team_qty(" + lit(bd(q[0])) + ", " + lit(bd(q[1])) + ", " + lit(bd(q[2])) + ")",
                    BigDecimal.valueOf(MoneyMath.teamQty(bd(q[0]), bd(q[1]), bd(q[2]))), q[3]));
        }
        SqlFunctionParity parity = new SqlFunctionParity(container).install(Path.of("db/21_team_buy_def.sql"),
                "buy_rate", "buy_fee", "buy_affordable", "buy_avg_price", "team_qty");
        SqlFunctionParity.Report report = parity.verify(cases);
        assertThat(report.cases()).isEqualTo(14);
    }

    @Test
    void 낙폭_ta_drawdown이_MoneyMath_drawdown과_기대값과_같다() throws Exception {
        // total, peak → (total − peak) / peak, one HALF_UP rounding at 8 decimals (away from zero on ties); never above 0
        String[][] drawdowns = {
                {"90", "120", "-0.25000000"},                     // erd 6.3 example
                {"150", "150", "0.00000000"},                     // new peak
                {"151", "150", "0.00000000"},                     // total above peak
                {"0", "1000", "-1.00000000"},                     // total wiped out
                {"199999999", "200000000", "-0.00000001"},        // exactly 0.5e-8 → away from zero
                {"199999997", "200000000", "-0.00000002"},        // 1.5e-8 → 2e-8
                {"299999999", "300000000", "0.00000000"},         // 0.33e-8 → 0
                {"1", "3", "-0.66666667"},                        // −2/3, repeating
                {"2", "3", "-0.33333333"},                        // −1/3, repeating
                {"7", "8", "-0.12500000"},                        // −1/8, exact
                {"100", "100000000", "-0.99999900"}};             // large peak
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < drawdowns.length; i++) {
            String[] d = drawdowns[i];
            cases.add(exactCase("ta_drawdown." + i,
                    "pg_temp.ta_drawdown(" + lit(bd(d[0])) + ", " + lit(bd(d[1])) + ")",
                    MoneyMath.drawdown(bd(d[0]), bd(d[1])), d[2]));
        }
        SqlFunctionParity parity = new SqlFunctionParity(container).install(Path.of("db/23_turn_advance_def.sql"),
                "ta_drawdown");
        SqlFunctionParity.Report report = parity.verify(cases);
        assertThat(report.cases()).isEqualTo(drawdowns.length);
    }
}

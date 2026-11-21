package antlab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.money.MoneyMath;
import antlab.common.money.MoneyMath.ReturnFactors;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Java is the oracle of the simulator's money kernel. A seeded generator draws cases for every {@link MoneyMath}
 * function the rule core uses, evaluates them here and compares (or, with {@code ANTLAB_PARITY_WRITE=1}, rewrites) the
 * tracked file {@code money-vectors-v1.jsonl}. The Python twin reads the same file and must return the same decimal
 * (value and scale) or the same refusal class.
 *
 * <p>One line per case: {@code {"fn","args","result"}} or {@code {"fn","args","error"}}. Decimals are plain strings
 * (the scale is part of the value), integers are JSON numbers, big integers are strings, lists are arrays.
 */
class SimMoneyVectorsTest {

    static final Path FILE = Path.of("src/test/resources/sim/money-vectors-v1.jsonl");
    private static final long SEED = 0x5EED_0301_0A01L;
    private static final int MIN_CASES = 2000;

    @Test
    void 파일이_Java_결과와_같다() throws IOException {
        List<String> lines = generate();
        assertThat(lines.size()).isGreaterThanOrEqualTo(MIN_CASES);
        if ("1".equals(System.getenv("ANTLAB_PARITY_WRITE"))) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        List<String> tracked = Files.readAllLines(FILE, StandardCharsets.UTF_8);
        assertThat(tracked).hasSameSizeAs(lines);
        for (int i = 0; i < lines.size(); i++) {
            assertThat(tracked.get(i)).as("line " + (i + 1)).isEqualTo(lines.get(i));
        }
    }

    @Test
    void 모든_함수와_거절_종류가_있다() {
        List<String> tracked = generate();
        for (String fn : FUNCTIONS) {
            List<String> own = tracked.stream().filter(l -> l.startsWith("{\"fn\":\"" + fn + "\"")).toList();
            assertThat(own).as(fn).hasSizeGreaterThanOrEqualTo(30);
            assertThat(own.stream().anyMatch(l -> l.contains("\"result\""))).as(fn + " has results").isTrue();
        }
        assertThat(tracked.stream().anyMatch(l -> l.contains("\"error\":\"INVALID\""))).isTrue();
        assertThat(tracked.stream().anyMatch(l -> l.contains("\"error\":\"ARITHMETIC\""))).isTrue();
    }

    static final List<String> FUNCTIONS = List.of(
            "initialPrice", "priceAt", "feeRate", "fee", "avgPrice", "realizedPnl", "affordable", "teamQty",
            "drawdown", "ratio", "valuation", "totalValue", "exactWon", "exactAvgPrice", "exactStoredPrice",
            "exactRate", "exactProbability", "fractionMean", "pad40Divide", "sampleStddev8", "sampleStddev",
            "pgDivide", "pgAvg", "round8", "shareDown", "scaleByMultiple", "scoreDivide", "divideSignificant",
            "sqrtSignificant", "round12", "round2", "mean", "percentPoints", "ceilCount");

    private record Case(String fn, List<Object> args) {
    }

    static final class SplitMix64 {
        private long state;

        SplitMix64(long seed) {
            this.state = seed;
        }

        long next() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int below(int bound) {
            return (int) Long.remainderUnsigned(next(), bound);
        }

        boolean chance(int percent) {
            return below(100) < percent;
        }

        <T> T pick(List<T> items) {
            return items.get(below(items.size()));
        }
    }

    private static BigDecimal d(String text) {
        return new BigDecimal(text);
    }

    /** A non-negative number with {@code digits} random digits at the given scale. */
    private static BigDecimal rand(SplitMix64 r, int digits, int scale) {
        int n = 1 + r.below(digits);
        StringBuilder sb = new StringBuilder();
        sb.append(1 + r.below(9));
        for (int i = 1; i < n; i++) {
            sb.append(r.below(10));
        }
        return new BigDecimal(new BigInteger(sb.toString()), scale);
    }

    private static BigDecimal rate(SplitMix64 r) {
        return switch (r.below(6)) {
            case 0 -> d("0.00000000");
            case 1 -> r.pick(List.of(d("0.00015000"), d("0.00500000"), d("0.50000000"), d("0.00005000"),
                    d("0.00250000"), d("0.00400000"), d("0.00023000"), d("0.25000000")));
            default -> rand(r, 8, 8);
        };
    }

    private static BigDecimal won(SplitMix64 r) {
        return switch (r.below(8)) {
            case 0 -> r.pick(List.of(d("1"), d("2"), d("3"), d("100000000"), d("30000000"), d("99999999"),
                    d("100000001"), d("999999999999999")));
            case 1 -> rand(r, 5, 0);
            case 2 -> rand(r, 9, 0);
            case 3 -> rand(r, 12, 0);
            default -> rand(r, 8, 0);
        };
    }

    private static BigDecimal asScale4(BigDecimal integer) {
        return integer.setScale(4);
    }

    private static List<Case> cases(SplitMix64 r) {
        List<Case> out = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            BigDecimal base = i % 3 == 0 ? rand(r, 8, 4).add(d("0.5")) : rand(r, 8, r.below(5));
            BigDecimal fx = r.pick(List.of(d("1.0000"), d("0.5000"), d("1.5000"), d("0.2500"), d("1380.5000"),
                    d("1423.2500"), rand(r, 5, 4)));
            out.add(new Case("initialPrice", List.of(base, fx)));
        }
        out.add(new Case("initialPrice", List.of(d("0"), d("1.0000"))));
        out.add(new Case("initialPrice", List.of(d("1.0000"), d("0.0000"))));
        out.add(new Case("initialPrice", List.of(d("-1.0000"), d("1.0000"))));
        out.add(new Case("initialPrice", List.of(d("1.00001"), d("1.0000"))));
        out.add(new Case("initialPrice", List.of(d("1.0000"), d("1.00001"))));
        out.add(new Case("initialPrice", List.of(d("999999999999999.9999"), d("9999.9999"))));
        out.add(new Case("initialPrice", List.of(d("0.0001"), d("0.0001"))));
        out.add(new Case("initialPrice", List.of(d("0.0001"), d("5000.0000"))));

        for (int i = 0; i < 90; i++) {
            BigDecimal initial = r.chance(30) ? asScale4(won(r)) : won(r);
            int len = r.chance(15) ? r.below(3) : 1 + r.below(60);
            List<List<BigDecimal>> list = new ArrayList<>();
            for (int k = 0; k < len; k++) {
                list.add(List.of(returnValue(r, true), r.chance(40) ? d("0.00000000") : returnValue(r, false)));
            }
            out.add(new Case("priceAt", List.of(initial, list)));
        }
        out.add(new Case("priceAt", List.of(d("1000"), List.of(List.of(d("-1.00000000"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("1000"), List.of(List.of(d("0.00000000"), d("-1.00000000"))))));
        out.add(new Case("priceAt", List.of(d("1000"), List.of(List.of(d("-1.00000001"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("0"), List.of())));
        out.add(new Case("priceAt", List.of(d("1000.5"), List.of())));
        out.add(new Case("priceAt", List.of(d("1000"), List.of(List.of(d("0.5"), d("0.5"))))));
        out.add(new Case("priceAt", List.of(d("999999999999999"), List.of(List.of(d("1.00000000"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("3"), List.of(List.of(d("0.50000000"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("15"), List.of(List.of(d("-0.90000000"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("45"), List.of(List.of(d("-0.30000000"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("25"), List.of(List.of(d("-0.90000000"), d("0.00000000"))))));
        out.add(new Case("priceAt", List.of(d("30"), List.of(List.of(d("-0.50000000"), d("0.00000000")),
                List.of(d("0.00000000"), d("-0.90000000"))))));
        out.add(new Case("priceAt", List.of(d("15"), List.of(List.of(d("0.00000000"), d("-0.90000000"))))));
        out.add(new Case("scaleByMultiple", List.of(d("15"), d("0.1"))));
        out.add(new Case("scaleByMultiple", List.of(d("45"), d("0.7"))));
        out.add(new Case("fee", List.of(d("15"), 1, d("0.10000000"))));
        out.add(new Case("fee", List.of(d("45"), 1, d("0.70000000"))));
        out.add(new Case("priceAt", List.of(d("5"), List.of(List.of(d("0.50000000"), d("0.00000000"))))));

        for (int i = 0; i < 40; i++) {
            out.add(new Case("feeRate", List.of(i % 2 == 0 ? "KR" : "US", rate(r), rate(r), rate(r))));
        }
        out.add(new Case("feeRate", List.of("XX", d("0.0001"), d("0.0001"), d("0.0001"))));
        out.add(new Case("feeRate", List.of("kr", d("0.0001"), d("0.0001"), d("0.0001"))));
        out.add(new Case("feeRate", List.of("KR", d("-0.0001"), d("0.0001"), d("0.0001"))));
        out.add(new Case("feeRate", List.of("KR", d("1"), d("0.0001"), d("0.0001"))));
        out.add(new Case("feeRate", List.of("US", d("0.0001"), d("0.999999999"), d("0.0001"))));
        out.add(new Case("feeRate", List.of("US", d("0.0001"), d("0.99999999"), d("0.99999999"))));
        out.add(new Case("feeRate", List.of("KR", d("0.0001"), d("0.0001"), d("1.0"))));

        for (int i = 0; i < 130; i++) {
            BigDecimal price = won(r);
            int qty = r.chance(10) ? 1 : r.chance(5) ? Integer.MAX_VALUE : 1 + r.below(r.chance(50) ? 100 : 100000);
            out.add(new Case("fee", List.of(price, qty, rate(r))));
        }
        out.add(new Case("fee", List.of(d("10000"), 1, d("0.00005000"))));
        out.add(new Case("fee", List.of(d("30000"), 1, d("0.00005000"))));
        out.add(new Case("fee", List.of(d("1"), 1, d("0.50000000"))));
        out.add(new Case("fee", List.of(d("3"), 1, d("0.50000000"))));
        out.add(new Case("fee", List.of(d("1"), 0, d("0.00015000"))));
        out.add(new Case("fee", List.of(d("1"), -1, d("0.00015000"))));
        out.add(new Case("fee", List.of(d("0"), 1, d("0.00015000"))));
        out.add(new Case("fee", List.of(d("1.5"), 1, d("0.00015000"))));
        out.add(new Case("fee", List.of(d("1"), 1, d("-0.00015000"))));
        out.add(new Case("fee", List.of(d("1"), 1, d("0.000150001"))));
        out.add(new Case("fee", List.of(d("999999999999999"), Integer.MAX_VALUE, d("9999.99999999"))));

        for (int i = 0; i < 130; i++) {
            int oldQty = r.chance(15) ? 0 : 1 + r.below(r.chance(10) ? 1000000 : 500);
            BigDecimal oldAvg = oldQty == 0 ? d("0.0000") : rand(r, 8, 4);
            int qty = 1 + r.below(r.chance(10) ? 1000000 : 500);
            BigDecimal price = r.chance(25) ? d(String.valueOf(1 + r.below(50))) : won(r);
            out.add(new Case("avgPrice", List.of(oldQty, oldAvg, qty, price)));
        }
        out.add(new Case("avgPrice", List.of(1, d("1.0000"), 1, d("2"))));
        out.add(new Case("avgPrice", List.of(1, d("1.0000"), 31, d("2"))));
        out.add(new Case("avgPrice", List.of(3, d("1.0000"), 1, d("2"))));
        out.add(new Case("avgPrice", List.of(Integer.MAX_VALUE, d("10.0000"), 1, d("10"))));
        out.add(new Case("avgPrice", List.of(0, d("5.0000"), 1, d("10"))));
        out.add(new Case("avgPrice", List.of(2, d("0.0000"), 1, d("10"))));
        out.add(new Case("avgPrice", List.of(-1, d("0.0000"), 1, d("10"))));
        out.add(new Case("avgPrice", List.of(0, d("0.0000"), 0, d("10"))));
        out.add(new Case("avgPrice", List.of(1, d("1.00001"), 1, d("10"))));

        for (int i = 0; i < 90; i++) {
            BigDecimal avg = rand(r, 8, 4);
            BigDecimal price = r.chance(30) ? avg.setScale(0, java.math.RoundingMode.DOWN).max(d("1")) : won(r);
            if (price.signum() == 0) {
                price = d("1");
            }
            out.add(new Case("realizedPnl", List.of(price, avg, 1 + r.below(r.chance(10) ? 2000000 : 500))));
        }
        out.add(new Case("realizedPnl", List.of(d("10"), d("0.0000"), 1)));
        out.add(new Case("realizedPnl", List.of(d("10"), d("5.00001"), 1)));
        out.add(new Case("realizedPnl", List.of(d("10"), d("5.0000"), 0)));
        out.add(new Case("realizedPnl", List.of(d("1"), d("99999999999.9999"), Integer.MAX_VALUE)));
        out.add(new Case("realizedPnl", List.of(d("999999999999999"), d("1.0000"), Integer.MAX_VALUE)));

        for (int i = 0; i < 80; i++) {
            BigDecimal notional = won(r);
            BigDecimal fee = r.chance(30) ? d("0") : rand(r, 5, 0);
            BigDecimal available = r.chance(40) ? notional.add(fee).add(BigDecimal.valueOf(r.below(3) - 1)).max(d("0")) : won(r);
            out.add(new Case("affordable", List.of(available, notional, fee)));
        }
        out.add(new Case("affordable", List.of(d("-1"), d("0"), d("0"))));
        out.add(new Case("affordable", List.of(d("1.5"), d("0"), d("0"))));
        out.add(new Case("affordable", List.of(d("1000000000000000"), d("0"), d("0"))));

        for (int i = 0; i < 110; i++) {
            BigDecimal budget = r.chance(40) ? r.pick(List.of(d("1"), d("100000000"), d("30000000"), d("99999999"),
                    d("100000001"), d("50000000"), d("33333333"))) : won(r);
            BigDecimal price = r.chance(30) ? BigDecimal.valueOf(1 + r.below(2000)) : won(r);
            out.add(new Case("teamQty", List.of(budget, price, rate(r))));
        }
        out.add(new Case("teamQty", List.of(d("1000"), d("1000"), d("0.00000000"))));
        out.add(new Case("teamQty", List.of(d("999"), d("1000"), d("0.00000000"))));
        out.add(new Case("teamQty", List.of(d("1000"), d("999"), d("0.00100000"))));
        out.add(new Case("teamQty", List.of(d("1001"), d("1000"), d("0.00100000"))));
        out.add(new Case("teamQty", List.of(d("0"), d("1"), d("0.00000000"))));
        out.add(new Case("teamQty", List.of(d("999999999999999"), d("1"), d("0.00000000"))));
        out.add(new Case("teamQty", List.of(d("100000000"), d("0"), d("0.00000000"))));
        out.add(new Case("teamQty", List.of(d("100000000"), d("1"), d("-0.00000001"))));

        for (int i = 0; i < 90; i++) {
            BigDecimal peak = won(r);
            BigDecimal total = r.chance(15) ? peak : r.chance(10) ? d("0") : won(r);
            out.add(new Case("drawdown", List.of(total, peak)));
        }
        out.add(new Case("drawdown", List.of(d("1"), d("0"))));
        out.add(new Case("drawdown", List.of(d("-1"), d("5"))));
        out.add(new Case("drawdown", List.of(d("199999999"), d("200000000"))));
        out.add(new Case("drawdown", List.of(d("9999999950000000"), d("1000000000000000"))));
        out.add(new Case("drawdown", List.of(d("999999999999999"), d("999999999999999"))));
        out.add(new Case("drawdown", List.of(d("999999999999998"), d("999999999999999"))));

        for (int i = 0; i < 120; i++) {
            BigDecimal num = r.chance(20) ? d("0") : r.chance(50) ? won(r) : won(r).negate();
            BigDecimal den = r.chance(15) ? BigDecimal.valueOf(2).multiply(d("100000000")).multiply(BigDecimal.valueOf(1 + r.below(30)))
                    : r.chance(30) ? asScale4(won(r)).multiply(r.chance(50) ? BigDecimal.ONE : BigDecimal.ONE.negate()) : won(r);
            if (r.chance(15)) {
                num = asScale4(num);
            }
            out.add(new Case("ratio", List.of(num, den)));
        }
        out.add(new Case("ratio", List.of(d("1"), d("200000000"))));
        out.add(new Case("ratio", List.of(d("-1"), d("200000000"))));
        out.add(new Case("ratio", List.of(d("3"), d("200000000"))));
        out.add(new Case("ratio", List.of(d("1"), d("0"))));
        out.add(new Case("ratio", List.of(d("1.5"), d("3"))));
        out.add(new Case("ratio", List.of(d("3"), d("1.5"))));
        out.add(new Case("ratio", List.of(d("1"), d("3.0000"))));

        for (int i = 0; i < 40; i++) {
            out.add(new Case("valuation", List.of(won(r), r.chance(10) ? 0 : 1 + r.below(r.chance(10) ? Integer.MAX_VALUE - 1 : 5000))));
        }
        out.add(new Case("valuation", List.of(d("999999999999999"), Integer.MAX_VALUE)));
        out.add(new Case("valuation", List.of(d("10"), -1)));
        out.add(new Case("valuation", List.of(d("0"), 1)));
        out.add(new Case("valuation", List.of(d("10.5"), 1)));

        for (int i = 0; i < 50; i++) {
            List<BigDecimal> values = new ArrayList<>();
            int n = r.below(9);
            for (int k = 0; k < n; k++) {
                values.add(r.chance(20) ? d("0") : won(r));
            }
            out.add(new Case("totalValue", List.of(r.chance(10) ? d("0") : won(r), values)));
        }
        out.add(new Case("totalValue", List.of(d("-1"), List.of())));
        out.add(new Case("totalValue", List.of(d("1"), List.of(d("-1")))));
        out.add(new Case("totalValue", List.of(d("999999999999999"), List.of(d("1")))));
        out.add(new Case("totalValue", List.of(d("999999999999999"), List.of(d("0")))));

        addExact(out, r, "exactWon", 0);
        addExact(out, r, "exactAvgPrice", 4);
        addExact(out, r, "exactStoredPrice", 4);
        addExact(out, r, "exactRate", 8);
        addExact(out, r, "exactProbability", 4);
        for (int i = 0; i < 3; i++) {
            out.add(new Case("exactWon", java.util.Collections.singletonList(null)));
        }

        for (int i = 0; i < 80; i++) {
            int n = 1 + r.below(6);
            List<BigInteger> a = new ArrayList<>();
            List<BigInteger> b = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                a.add(rand(r, 9, 0).toBigInteger().multiply(BigInteger.valueOf(r.chance(30) ? -1 : 1)));
                b.add(rand(r, r.chance(20) ? 12 : 6, 0).toBigInteger());
            }
            out.add(new Case("fractionMean", List.of(a, b)));
        }
        out.add(new Case("fractionMean", List.of(List.of(), List.of())));
        out.add(new Case("fractionMean", List.of(List.of(BigInteger.ONE), List.of(BigInteger.ONE, BigInteger.TWO))));
        out.add(new Case("fractionMean", List.of(List.of(BigInteger.ONE), List.of(BigInteger.ZERO))));
        out.add(new Case("fractionMean", List.of(List.of(BigInteger.ONE, BigInteger.ONE), List.of(BigInteger.valueOf(3), BigInteger.valueOf(200000000)))));

        for (int i = 0; i < 100; i++) {
            BigDecimal n = r.chance(15) ? d("0") : r.chance(50) ? rand(r, 12, r.below(6)) : rand(r, 12, r.below(6)).negate();
            BigDecimal den = rand(r, r.chance(30) ? 3 : 9, r.below(6));
            out.add(new Case("pad40Divide", List.of(n, den)));
        }
        out.add(new Case("pad40Divide", List.of(d("1"), d("0"))));
        out.add(new Case("pad40Divide", List.of(d("1"), d("3"))));
        out.add(new Case("pad40Divide", List.of(d("0"), d("3"))));
        out.add(new Case("pad40Divide", List.of(d("1.00"), d("3.000"))));

        for (int i = 0; i < 100; i++) {
            int n = r.chance(5) ? r.below(2) : 2 + r.below(14);
            int scale = r.chance(30) ? 8 : r.below(10);
            List<BigDecimal> values = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                BigDecimal v = r.chance(10) ? d("0") : rand(r, 6, scale);
                values.add(r.chance(50) ? v.negate() : v);
            }
            if (r.chance(8) && !values.isEmpty()) {
                BigDecimal same = values.get(0);
                values = new ArrayList<>(java.util.Collections.nCopies(values.size(), same));
            }
            out.add(new Case("sampleStddev8", List.of(values)));
            out.add(new Case("sampleStddev", List.of(values)));
        }
        out.add(new Case("sampleStddev8", List.of(List.of(d("0.01000000"), d("0.03000000")))));
        out.add(new Case("sampleStddev", List.of(List.of(d("1"), d("3")))));
        out.add(new Case("sampleStddev", List.of(List.of(d("1.0"), d("1.0"), d("1.0")))));

        for (int i = 0; i < 110; i++) {
            BigDecimal n = r.chance(10) ? d("0") : r.chance(60) ? rand(r, 14, r.below(9)) : rand(r, 14, r.below(9)).negate();
            BigDecimal den = r.chance(20) ? BigDecimal.valueOf(1 + r.below(30)) : rand(r, 10, r.below(9));
            out.add(new Case("pgDivide", List.of(n, den)));
        }
        out.add(new Case("pgDivide", List.of(d("1"), d("0"))));
        out.add(new Case("pgDivide", List.of(d("1"), d("3"))));
        out.add(new Case("pgDivide", List.of(d("10000"), d("3"))));
        out.add(new Case("pgDivide", List.of(d("1"), d("30000"))));
        out.add(new Case("pgDivide", List.of(d("12345678901234567890"), d("7"))));
        out.add(new Case("pgDivide", List.of(d("0.00000001"), d("7"))));
        out.add(new Case("pgDivide", List.of(d("5"), d("2"))));
        out.add(new Case("pgDivide", List.of(d("-5"), d("2"))));

        for (int i = 0; i < 70; i++) {
            int n = r.chance(5) ? 0 : 1 + r.below(12);
            int scale = r.below(8);
            List<BigDecimal> values = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                BigDecimal v = rand(r, 8, r.chance(40) ? scale : r.below(8));
                values.add(r.chance(40) ? v.negate() : v);
            }
            out.add(new Case("pgAvg", List.of(values)));
        }

        for (int i = 0; i < 60; i++) {
            BigDecimal v = rand(r, 6, r.below(14));
            if (r.chance(30)) {
                v = new BigDecimal(new BigInteger(v.unscaledValue() + "5"), 9);
            }
            out.add(new Case("round8", List.of(r.chance(40) ? v.negate() : v)));
            out.add(new Case("round12", List.of(r.chance(40) ? v.negate() : v)));
            out.add(new Case("round2", List.of(r.chance(40) ? v.negate() : v)));
        }
        out.add(new Case("round8", List.of(d("0.000000005"))));
        out.add(new Case("round8", List.of(d("-0.000000005"))));
        out.add(new Case("round8", List.of(d("0.000000004999999"))));
        out.add(new Case("round8", List.of(d("2"))));
        out.add(new Case("round12", List.of(d("0.0000000000005"))));
        out.add(new Case("round2", List.of(d("0.005"))));
        out.add(new Case("round2", List.of(d("-0.005"))));
        out.add(new Case("round2", List.of(d("0.0049999999"))));

        for (int i = 0; i < 80; i++) {
            BigDecimal total = rand(r, 9, 0).add(d("1"));
            BigDecimal holding = r.chance(10) ? d("0") : r.chance(10) ? total : total.multiply(BigDecimal.valueOf(r.below(1000))).divideToIntegralValue(d("1000"));
            out.add(new Case("shareDown", List.of(holding, total)));
        }
        out.add(new Case("shareDown", List.of(d("1"), d("3"))));
        out.add(new Case("shareDown", List.of(d("2"), d("3"))));
        out.add(new Case("shareDown", List.of(d("4"), d("3"))));
        out.add(new Case("shareDown", List.of(d("1"), d("0"))));
        out.add(new Case("shareDown", List.of(d("-1"), d("3"))));
        out.add(new Case("shareDown", List.of(d("1.5"), d("3"))));

        for (int i = 0; i < 80; i++) {
            BigDecimal mult = r.chance(10) ? d("0") : r.chance(30) ? r.pick(List.of(d("1"), d("1.5"), d("0.5"), d("2.00000000"), d("0.99999999"))) : rand(r, 9, 8);
            out.add(new Case("scaleByMultiple", List.of(won(r), mult)));
        }
        out.add(new Case("scaleByMultiple", List.of(d("1"), d("0.5"))));
        out.add(new Case("scaleByMultiple", List.of(d("3"), d("0.5"))));
        out.add(new Case("scaleByMultiple", List.of(d("1"), d("-0.5"))));
        out.add(new Case("scaleByMultiple", List.of(d("0"), d("0.5"))));
        out.add(new Case("scaleByMultiple", List.of(d("999999999999999"), d("2"))));

        for (int i = 0; i < 70; i++) {
            BigDecimal n = r.chance(10) ? d("0") : rand(r, 12, r.below(10));
            BigDecimal den = rand(r, 8, r.below(10));
            out.add(new Case("scoreDivide", List.of(r.chance(40) ? n.negate() : n, den)));
        }
        out.add(new Case("scoreDivide", List.of(d("1"), d("0"))));
        out.add(new Case("scoreDivide", List.of(d("0"), d("0"))));
        out.add(new Case("scoreDivide", List.of(d("1"), d("3"))));
        out.add(new Case("scoreDivide", List.of(d("2"), d("3"))));

        for (int i = 0; i < 70; i++) {
            BigDecimal den = rand(r, 8, r.below(10));
            BigDecimal n = r.chance(10) ? d("0").setScale(den.scale()) : rand(r, 12, den.scale() + r.below(6));
            out.add(new Case("divideSignificant", List.of(r.chance(40) ? n.negate() : n, den)));
        }
        out.add(new Case("divideSignificant", List.of(d("1"), d("0"))));
        out.add(new Case("divideSignificant", List.of(d("1"), d("3"))));
        out.add(new Case("divideSignificant", List.of(d("1.00"), d("4"))));
        out.add(new Case("divideSignificant", List.of(d("10"), d("4"))));
        out.add(new Case("divideSignificant", List.of(d("100.0"), d("4.0"))));
        out.add(new Case("divideSignificant", List.of(d("2"), d("3"))));
        out.add(new Case("divideSignificant", List.of(d("0.0000000001"), d("7"))));
        out.add(new Case("divideSignificant", List.of(d("1"), d("1024"))));

        for (int i = 0; i < 70; i++) {
            BigDecimal v = r.chance(25) ? rand(r, 6, r.below(4)).pow(2) : rand(r, 14, r.below(14));
            out.add(new Case("sqrtSignificant", List.of(v)));
        }
        out.add(new Case("sqrtSignificant", List.of(d("-1"))));
        out.add(new Case("sqrtSignificant", List.of(d("0"))));
        out.add(new Case("sqrtSignificant", List.of(d("0.00"))));
        out.add(new Case("sqrtSignificant", List.of(d("4"))));
        out.add(new Case("sqrtSignificant", List.of(d("2"))));
        out.add(new Case("sqrtSignificant", List.of(d("0.25"))));
        out.add(new Case("sqrtSignificant", List.of(d("0.0001"))));
        out.add(new Case("sqrtSignificant", List.of(d("1E-2").setScale(5))));
        out.add(new Case("sqrtSignificant", List.of(d("100000000"))));

        for (int i = 0; i < 70; i++) {
            int n = r.chance(5) ? 0 : 1 + r.below(10);
            int scale = r.below(10);
            List<BigDecimal> values = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                BigDecimal v = rand(r, 9, scale);
                values.add(r.chance(40) ? v.negate() : v);
            }
            out.add(new Case("mean", List.of(values, r.pick(List.of(0, 2, 4, 8, 12)))));
        }
        out.add(new Case("mean", List.of(List.of(d("1"), d("2")), 0)));
        out.add(new Case("mean", List.of(List.of(d("-1"), d("-2")), 0)));
        out.add(new Case("mean", List.of(List.of(d("0.01"), d("0.02")), 2)));

        for (int i = 0; i < 60; i++) {
            BigDecimal v = rand(r, 6, r.below(10));
            out.add(new Case("percentPoints", List.of(r.chance(40) ? v.negate() : v)));
        }
        out.add(new Case("percentPoints", List.of(d("0.005"))));
        out.add(new Case("percentPoints", List.of(d("-0.005"))));
        out.add(new Case("percentPoints", List.of(d("0.00499999"))));
        out.add(new Case("percentPoints", List.of(d("1"))));
        out.add(new Case("percentPoints", List.of(d("0"))));

        for (int i = 0; i < 60; i++) {
            BigDecimal ratio = r.chance(15) ? r.pick(List.of(d("0.05"), d("0.10"), d("0.2"), d("0.5"), d("1"))) : rand(r, 5, r.below(6));
            long n = r.chance(10) ? r.below(3) : 1 + r.below(r.chance(20) ? 100000 : 400);
            out.add(new Case("ceilCount", List.of(r.chance(10) ? ratio.negate() : ratio, n)));
        }
        out.add(new Case("ceilCount", List.of(d("0.05"), 200L)));
        out.add(new Case("ceilCount", List.of(d("0.0501"), 200L)));
        out.add(new Case("ceilCount", List.of(d("2"), 4000000000L)));
        out.add(new Case("ceilCount", List.of(d("-2"), 4000000000L)));
        out.add(new Case("ceilCount", List.of(d("0.00001"), 1L)));
        out.add(new Case("ceilCount", List.of(d("-0.5"), 1L)));
        return out;
    }

    private static BigDecimal returnValue(SplitMix64 r, boolean local) {
        return switch (r.below(8)) {
            case 0 -> d("0.00000000");
            case 1 -> r.pick(List.of(d("0.50000000"), d("-0.50000000"), d("0.10000000"), d("-0.00000001"),
                    d("0.00000001"), d("-0.99000000"), d("1.00000000")));
            case 2 -> local ? d("-1.00000000") : d("0.00500000");
            default -> {
                BigDecimal v = new BigDecimal(new BigInteger(String.valueOf(r.below(local ? 30000000 : 2000000))), 8);
                yield r.chance(50) ? v.negate() : v;
            }
        };
    }

    private static void addExact(List<Case> out, SplitMix64 r, String fn, int scale) {
        for (int i = 0; i < 36; i++) {
            BigDecimal v = switch (r.below(4)) {
                case 0 -> rand(r, 9, scale);
                case 1 -> rand(r, 9, scale).setScale(scale + r.below(3));
                case 2 -> rand(r, 9, 0).setScale(scale + r.below(4));
                default -> rand(r, 9, scale + 1 + r.below(3));
            };
            out.add(new Case(fn, List.of(r.chance(15) ? v.negate() : v)));
        }
        out.add(new Case(fn, List.of(d("0"))));
        out.add(new Case(fn, List.of(d("1.5"))));
        out.add(new Case(fn, List.of(d("1.00000000000"))));
        out.add(new Case(fn, List.of(d("0.00000000000001"))));
    }

    private static Object eval(Case c) {
        List<Object> a = c.args();
        return switch (c.fn()) {
            case "initialPrice" -> MoneyMath.initialPrice((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "priceAt" -> {
                List<ReturnFactors> factors = new ArrayList<>();
                for (Object pair : (List<?>) a.get(1)) {
                    List<?> p = (List<?>) pair;
                    factors.add(new ReturnFactors((BigDecimal) p.get(0), (BigDecimal) p.get(1)));
                }
                yield MoneyMath.priceAt((BigDecimal) a.get(0), factors);
            }
            case "feeRate" -> MoneyMath.feeRate((String) a.get(0), (BigDecimal) a.get(1), (BigDecimal) a.get(2), (BigDecimal) a.get(3));
            case "fee" -> MoneyMath.fee((BigDecimal) a.get(0), (Integer) a.get(1), (BigDecimal) a.get(2));
            case "avgPrice" -> MoneyMath.avgPrice((Integer) a.get(0), (BigDecimal) a.get(1), (Integer) a.get(2), (BigDecimal) a.get(3));
            case "realizedPnl" -> MoneyMath.realizedPnl((BigDecimal) a.get(0), (BigDecimal) a.get(1), (Integer) a.get(2));
            case "affordable" -> MoneyMath.affordable((BigDecimal) a.get(0), (BigDecimal) a.get(1), (BigDecimal) a.get(2));
            case "teamQty" -> MoneyMath.teamQty((BigDecimal) a.get(0), (BigDecimal) a.get(1), (BigDecimal) a.get(2));
            case "drawdown" -> MoneyMath.drawdown((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "ratio" -> MoneyMath.ratio((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "valuation" -> MoneyMath.valuation((BigDecimal) a.get(0), (Integer) a.get(1));
            case "totalValue" -> MoneyMath.totalValue((BigDecimal) a.get(0), decimals(a.get(1)));
            case "exactWon" -> MoneyMath.exactWon((BigDecimal) a.get(0));
            case "exactAvgPrice" -> MoneyMath.exactAvgPrice((BigDecimal) a.get(0));
            case "exactStoredPrice" -> MoneyMath.exactStoredPrice((BigDecimal) a.get(0));
            case "exactRate" -> MoneyMath.exactRate((BigDecimal) a.get(0));
            case "exactProbability" -> MoneyMath.exactProbability((BigDecimal) a.get(0));
            case "fractionMean" -> MoneyMath.fractionMean(integers(a.get(0)), integers(a.get(1)));
            case "pad40Divide" -> MoneyMath.pad40Divide((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "sampleStddev8" -> MoneyMath.sampleStddev8(decimals(a.get(0)));
            case "sampleStddev" -> MoneyMath.sampleStddev(decimals(a.get(0)));
            case "pgDivide" -> MoneyMath.pgDivide((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "pgAvg" -> MoneyMath.pgAvg(decimals(a.get(0)));
            case "round8" -> MoneyMath.round8((BigDecimal) a.get(0));
            case "shareDown" -> MoneyMath.shareDown((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "scaleByMultiple" -> MoneyMath.scaleByMultiple((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "scoreDivide" -> MoneyMath.scoreDivide((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "divideSignificant" -> MoneyMath.divideSignificant((BigDecimal) a.get(0), (BigDecimal) a.get(1));
            case "sqrtSignificant" -> MoneyMath.sqrtSignificant((BigDecimal) a.get(0));
            case "round12" -> MoneyMath.round12((BigDecimal) a.get(0));
            case "round2" -> MoneyMath.round2((BigDecimal) a.get(0));
            case "mean" -> MoneyMath.mean(decimals(a.get(0)), (Integer) a.get(1));
            case "percentPoints" -> MoneyMath.percentPoints((BigDecimal) a.get(0));
            case "ceilCount" -> MoneyMath.ceilCount((BigDecimal) a.get(0), (Long) a.get(1));
            default -> throw new IllegalStateException("unknown function " + c.fn());
        };
    }

    @SuppressWarnings("unchecked")
    private static List<BigDecimal> decimals(Object o) {
        return (List<BigDecimal>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<BigInteger> integers(Object o) {
        return (List<BigInteger>) o;
    }

    private static String json(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof BigDecimal b) {
            if (b.scale() < 0) {
                throw new IllegalStateException("a negative scale has no plain form: " + b);
            }
            return "\"" + b.toPlainString() + "\"";
        }
        if (o instanceof BigInteger b) {
            return "\"" + b + "\"";
        }
        if (o instanceof String s) {
            return "\"" + s + "\"";
        }
        if (o instanceof Integer || o instanceof Long || o instanceof Boolean) {
            return o.toString();
        }
        if (o instanceof List<?> list) {
            return list.stream().map(SimMoneyVectorsTest::json).collect(Collectors.joining(",", "[", "]"));
        }
        throw new IllegalStateException("unsupported " + o.getClass());
    }

    private static List<String> generate() {
        List<String> lines = new ArrayList<>();
        for (Case c : cases(new SplitMix64(SEED))) {
            String head = "{\"fn\":\"" + c.fn() + "\",\"args\":" + json(c.args());
            String tail;
            try {
                tail = ",\"result\":" + json(eval(c)) + "}";
            } catch (IllegalArgumentException ex) {
                tail = ",\"error\":\"INVALID\"}";
            } catch (ArithmeticException ex) {
                tail = ",\"error\":\"ARITHMETIC\"}";
            }
            lines.add(head + tail);
        }
        return lines;
    }
}

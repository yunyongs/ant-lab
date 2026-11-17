package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The three numeric steps the attention calculator takes from PostgreSQL — {@code avg(numeric)}, unrounded {@code stddev_samp(numeric)}
 * and {@code numeric / numeric} — equal the {@link MoneyMath} versions in value and in scale. The attention golden compares 8-digit results only,
 * so a wrong intermediate scale could hide there; this test compares the intermediates directly. The shared {@link MoneyMathSqlParityTest} is untouched.
 */
@IntegrationTest
class MoneyMathPgStatsParityTest {

    @Autowired
    DataSource dataSource;

    /** Absolute-return shaped vectors (scale 8, 2–12 values), constant vectors, tiny and large magnitudes, mixed scales. */
    private static List<List<BigDecimal>> vectors() {
        Random random = new Random(20261116L);
        List<List<BigDecimal>> vectors = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int length = 2 + random.nextInt(11);
            long bound = switch (i % 4) {
                case 0 -> 12_000_000L;
                case 1 -> 5_000L;
                case 2 -> 99_999_999_999L;
                default -> 150_000L;
            };
            List<BigDecimal> v = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                v.add(BigDecimal.valueOf(Math.floorMod(random.nextLong(), bound + 1), 8));
            }
            vectors.add(v);
        }
        for (int length = 2; length <= 12; length++) {
            vectors.add(java.util.Collections.nCopies(length, new BigDecimal("0.01000000")));
            vectors.add(java.util.Collections.nCopies(length, new BigDecimal("0.00000000")));
        }
        vectors.add(List.of(new BigDecimal("0.0006"), new BigDecimal("0.00040000"), new BigDecimal("0.0007")));
        vectors.add(List.of(new BigDecimal("0.00060000"), new BigDecimal("0.00040000"), new BigDecimal("0.00070000"),
                new BigDecimal("0.00050000")));
        // empty and single-value vectors (NULL results), negative and cancelling sums, the NUMERIC(12,8) extremes
        vectors.add(List.of());
        vectors.add(List.of(new BigDecimal("0.12345678")));
        vectors.add(List.of(new BigDecimal("-0.50000000"), new BigDecimal("0.50000000")));
        vectors.add(List.of(new BigDecimal("-0.00000001"), new BigDecimal("0.00000001"), new BigDecimal("0.00000000")));
        vectors.add(List.of(new BigDecimal("-1.00000000"), new BigDecimal("-0.33333333"), new BigDecimal("-0.66666667")));
        vectors.add(List.of(new BigDecimal("9999.99999999"), new BigDecimal("-9999.99999999")));
        vectors.add(List.of(new BigDecimal("9999.99999999"), new BigDecimal("9999.99999998"), new BigDecimal("9999.99999997")));
        // a large common value with tiny deviations
        vectors.add(List.of(new BigDecimal("5000.00060000"), new BigDecimal("5000.00040000"), new BigDecimal("5000.00070000"),
                new BigDecimal("5000.00050000")));
        vectors.add(List.of(new BigDecimal("5000.12345601"), new BigDecimal("5000.12345602"), new BigDecimal("5000.12345603")));
        vectors.add(List.of(new BigDecimal("0.00000001"), new BigDecimal("0.00000002")));
        return vectors;
    }

    private static String array(List<BigDecimal> v) {
        return v.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",", "{", "}"));
    }

    private static boolean same(BigDecimal expected, BigDecimal actual, boolean checkScale) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        return expected.compareTo(actual) == 0 && (!checkScale || expected.scale() == actual.scale());
    }

    @Test
    void pgAvg_값과_자릿수가_PG_avg와_같다() throws Exception {
        List<String> mismatches = new ArrayList<>();
        List<List<BigDecimal>> vectors = vectors();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT avg(u.x) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            for (int i = 0; i < vectors.size(); i++) {
                statement.setString(1, array(vectors.get(i)));
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!same(result.getBigDecimal(1), MoneyMath.pgAvg(vectors.get(i)), true)) {
                        mismatches.add("vector " + i + " size " + vectors.get(i).size());
                    }
                }
            }
        }
        assertThat(vectors.size()).isGreaterThan(200);
        assertThat(mismatches).as("vectors where avg differs from PostgreSQL (indexes only)").isEmpty();
    }

    @Test
    void sampleStddev_반올림_전_값과_자릿수가_PG_stddev_samp와_같다() throws Exception {
        List<String> mismatches = new ArrayList<>();
        List<String> roundingMismatches = new ArrayList<>();
        List<List<BigDecimal>> vectors = vectors();
        int compared = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT stddev_samp(u.x) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            for (int i = 0; i < vectors.size(); i++) {
                statement.setString(1, array(vectors.get(i)));
                BigDecimal expected;
                try (var result = statement.executeQuery()) {
                    result.next();
                    expected = result.getBigDecimal(1);
                }
                BigDecimal actual = MoneyMath.sampleStddev(vectors.get(i));
                // all-equal values give 0; PostgreSQL's scale of that zero carries no meaning
                boolean zero = expected != null && actual != null && expected.signum() == 0 && actual.signum() == 0;
                if (!zero) {
                    compared++;
                }
                if (!same(expected, actual, !zero)) {
                    mismatches.add("vector " + i + " size " + vectors.get(i).size());
                }
                BigDecimal rounded = MoneyMath.sampleStddev8(vectors.get(i));
                boolean sameRounded = actual == null ? rounded == null : rounded != null && rounded.equals(MoneyMath.round8(actual));
                if (!sameRounded) {
                    roundingMismatches.add("vector " + i + " size " + vectors.get(i).size());
                }
            }
        }
        assertThat(compared).isGreaterThan(200);
        assertThat(mismatches).as("vectors where stddev_samp differs from PostgreSQL (indexes only)").isEmpty();
        assertThat(roundingMismatches).as("vectors where sampleStddev8 is not round8(sampleStddev) (indexes only)").isEmpty();
    }

    @Test
    void pgDivide_값과_자릿수가_PG_나눗셈과_같다() throws Exception {
        Random random = new Random(1116L);
        int[] scales = {0, 2, 4, 8, 16, 17, 20, 24};
        List<BigDecimal[]> cases = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            BigDecimal n = random(random, scales[random.nextInt(scales.length)], 1 + random.nextInt(12));
            BigDecimal d = random(random, scales[random.nextInt(scales.length)], 1 + random.nextInt(12));
            if (d.signum() != 0) {
                cases.add(new BigDecimal[] {n, d});
            }
        }
        cases.add(new BigDecimal[] {new BigDecimal("0.00020000000000"), new BigDecimal("0.000100000000000000")});
        cases.add(new BigDecimal[] {new BigDecimal("0.00000000"), new BigDecimal("0.0123")});
        cases.add(new BigDecimal[] {new BigDecimal("-0.00055000"), new BigDecimal("0.0001")});
        cases.add(new BigDecimal[] {new BigDecimal("1"), new BigDecimal("3")});
        cases.add(new BigDecimal[] {new BigDecimal("2"), new BigDecimal("3")});
        // exact ties at the last kept digit, both signs, with the digit just before and after the tie (HALF_UP away from zero)
        for (int scale = 17; scale <= 24; scale++) {
            for (long units : new long[] {5, 15, 25, 45, 4, 6, 14, 16}) {
                for (long divisor : new long[] {2, 10, 20}) {
                    cases.add(new BigDecimal[] {BigDecimal.valueOf(units, scale), BigDecimal.valueOf(divisor)});
                    cases.add(new BigDecimal[] {BigDecimal.valueOf(-units, scale), BigDecimal.valueOf(divisor)});
                }
            }
        }
        // dividend and divisor share the leading base-10000 group, with a smaller or larger leading group, and a negative divisor
        cases.add(new BigDecimal[] {new BigDecimal("12345678"), new BigDecimal("12345679")});
        cases.add(new BigDecimal[] {new BigDecimal("12345679"), new BigDecimal("12345678")});
        cases.add(new BigDecimal[] {new BigDecimal("1.0001"), new BigDecimal("1.0002")});
        cases.add(new BigDecimal[] {new BigDecimal("1.9999"), new BigDecimal("1.0000")});
        cases.add(new BigDecimal[] {new BigDecimal("0.0001"), new BigDecimal("0.0002")});
        cases.add(new BigDecimal[] {new BigDecimal("0.00010001"), new BigDecimal("-0.00010002")});
        // negative and cancelling numerators, the largest NUMERIC(12,8) magnitudes
        cases.add(new BigDecimal[] {new BigDecimal("-9999.99999999"), new BigDecimal("0.00010000")});
        cases.add(new BigDecimal[] {new BigDecimal("9999.99999999"), new BigDecimal("0.00000001")});
        cases.add(new BigDecimal[] {new BigDecimal("0.00055000").subtract(new BigDecimal("0.00055000")), new BigDecimal("0.0001")});
        // result scale cap of 1000 digits
        cases.add(new BigDecimal[] {new BigDecimal(BigInteger.ONE, 1100), new BigDecimal("3")});
        cases.add(new BigDecimal[] {new BigDecimal(BigInteger.valueOf(7), 1005), new BigDecimal("0.0007")});
        List<String> mismatches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT CAST(? AS numeric) / CAST(? AS numeric)")) {
            for (int i = 0; i < cases.size(); i++) {
                statement.setString(1, cases.get(i)[0].toPlainString());
                statement.setString(2, cases.get(i)[1].toPlainString());
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!same(result.getBigDecimal(1), MoneyMath.pgDivide(cases.get(i)[0], cases.get(i)[1]), true)) {
                        mismatches.add("case " + i);
                    }
                }
            }
        }
        assertThat(cases.size()).isGreaterThan(500);
        assertThat(mismatches).as("divisions that differ from PostgreSQL (indexes only)").isEmpty();
    }

    private static BigDecimal random(Random random, int scale, int digits) {
        BigInteger magnitude = new BigInteger(Math.max(1, digits * 3), random);
        return new BigDecimal(random.nextInt(5) == 0 ? magnitude.negate() : magnitude, scale);
    }

    @Test
    void 상수_벡터의_표준편차는_PG와_값과_자릿수가_같고_두_값_미만은_둘_다_NULL이다() throws Exception {
        List<List<BigDecimal>> constants = new ArrayList<>();
        for (int length = 2; length <= 6; length++) {
            for (String value : new String[] {"0", "0.5", "0.01000000", "0.000000000000", "-3.10", "9999.99999999", "5000.12345601"}) {
                constants.add(java.util.Collections.nCopies(length, new BigDecimal(value)));
            }
        }
        constants.add(List.of());
        constants.add(List.of(new BigDecimal("0.01000000")));
        List<String> mismatches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT stddev_samp(u.x) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            for (int i = 0; i < constants.size(); i++) {
                statement.setString(1, array(constants.get(i)));
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!same(result.getBigDecimal(1), MoneyMath.sampleStddev(constants.get(i)), true)) {
                        mismatches.add("vector " + i + " size " + constants.get(i).size());
                    }
                }
            }
        }
        assertThat(mismatches).as("constant or short vectors where stddev_samp differs in value or scale (indexes only)").isEmpty();
    }

    @Test
    void 나누는_수가_0이면_PG와_MoneyMath가_모두_거부한다() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT CAST(? AS numeric) / CAST(? AS numeric)")) {
            statement.setString(1, "0.12345678");
            statement.setString(2, "0.000");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                try (var result = statement.executeQuery()) {
                    result.next();
                }
            }).isInstanceOf(java.sql.SQLException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MoneyMath.pgDivide(new BigDecimal("0.12345678"), new BigDecimal("0.000")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MoneyMath.pgDivide(BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

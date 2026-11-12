package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import antlab.support.IntegrationTest;

/** P0-15의 순수 PostgreSQL 함수와 Java 계산을 같은 입력으로 대조한다. */
@IntegrationTest
class MoneyMathSqlParityTest {

    @Autowired
    DataSource dataSource;

    @Test
    void 매수_순수함수_다섯개가_같은_결과를_낸다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            installPureFunctions(connection);
            // 같은 입력을 한 번만 적고 Java와 SQL 양쪽에 넘긴다.
            String[][] rates = {{"KR", "0.003", "0.004", "0.001"}, {"US", "0.003", "0.004", "0.001"},
                    {"US", "0.7", "0.6", "0.6"}};
            for (String[] r : rates) {
                assertThat(sql(connection, "SELECT pg_temp.buy_rate(?, ?, ?, ?)", r[0], bd(r[1]), bd(r[2]), bd(r[3])))
                        .isEqualByComparingTo(MoneyMath.feeRate(r[0], bd(r[1]), bd(r[2]), bd(r[3])));
            }
            // 가격, 수량, 요율 — 절반 경계, 절반 아래, 합산 요율 1 이상
            String[][] fees = {{"10", "1", "0.05"}, {"10", "1", "0.049"}, {"10", "1", "1.2"}};
            for (String[] f : fees) {
                int quantity = Integer.parseInt(f[1]);
                assertThat(sql(connection, "SELECT pg_temp.buy_fee(?, ?, ?)", bd(f[0]), quantity, bd(f[2])))
                        .isEqualByComparingTo(MoneyMath.fee(bd(f[0]), quantity, bd(f[2])));
            }
            // 사용 가능 금액, 대금, 수수료 — 참과 거짓
            String[][] affordables = {{"11", "10", "1"}, {"10", "10", "1"}};
            for (String[] a : affordables) {
                assertThat(sql(connection, "SELECT pg_temp.buy_affordable(?, ?, ?)", bd(a[0]), bd(a[1]), bd(a[2])))
                        .isEqualByComparingTo(MoneyMath.affordable(bd(a[0]), bd(a[1]), bd(a[2]))
                                ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            // 기존 수량, 기존 평균, 신규 수량, 체결가 — 반복소수, 재매수(기존 0), 정확한 절반
            String[][] averages = {{"2", "100", "1", "101"}, {"0", "0", "2", "7"}, {"1", "1.0001", "1", "2"}};
            for (String[] v : averages) {
                int oldQuantity = Integer.parseInt(v[0]);
                int quantity = Integer.parseInt(v[2]);
                assertThat(sql(connection, "SELECT pg_temp.buy_avg_price(?, ?, ?, ?)",
                        oldQuantity, bd(v[1]), quantity, bd(v[3])))
                        .isEqualByComparingTo(MoneyMath.avgPrice(oldQuantity, bd(v[1]), quantity, bd(v[3])));
            }
            // 예산, 가격, 요율 — 나누어떨어짐, 나머지가 있는 몫, 합산 요율 1 이상
            String[][] quantities = {{"11", "5", "0.10"}, {"100", "10", "0.05"}, {"100", "10", "1.2"}};
            for (String[] q : quantities) {
                assertThat(sql(connection, "SELECT pg_temp.team_qty(?, ?, ?)", bd(q[0]), bd(q[1]), bd(q[2])))
                        .isEqualByComparingTo(BigDecimal.valueOf(MoneyMath.teamQty(bd(q[0]), bd(q[1]), bd(q[2]))));
            }
        }
    }

    @Test
    void 실현_손익_SQL_round_4와_Java가_같다() throws Exception {
        // 체결가, 평균단가, 수량 — 이익, 손실, 0, 평균단가 소수 4자리, 큰 수량
        String[][] cases = {{"160000", "150000.0000", "4"}, {"30000", "35000", "40"}, {"150000", "150000", "1"},
                {"150001", "150000.6667", "3"}, {"1", "0.0001", "2"}, {"999999999", "1", "1000000"}};
        try (Connection connection = dataSource.getConnection()) {
            for (String[] c : cases) {
                int quantity = Integer.parseInt(c[2]);
                BigDecimal expected = sql(connection, "SELECT ROUND((?::numeric - ?::numeric) * ?::numeric, 4)",
                        bd(c[0]), bd(c[1]), bd(c[2]));
                BigDecimal actual = MoneyMath.realizedPnl(bd(c[0]), bd(c[1]), quantity);
                assertThat(actual).isEqualByComparingTo(expected);
                assertThat(actual.scale()).isEqualTo(4);
            }
        }
    }

    @Test
    void 초기_가격_SQL_round와_Java가_같다() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT ROUND(? * ?, 0)")) {
            statement.setBigDecimal(1, bd("10.25"));
            statement.setBigDecimal(2, bd("2"));
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getBigDecimal(1)).isEqualByComparingTo(MoneyMath.initialPrice(bd("10.25"), bd("2")));
            }
        }
    }

    // ---------------------------------------------------------------- P0-38: 40자리 올림 나눗셈·표본 표준편차

    private static final String PAD40 = "0.0000000000000000000000000000000000000000";

    /** 고정 시드 무작위 벡터와 8자리 경계 벡터 — 표준편차·분산 대조가 같은 벡터를 쓴다. */
    private static List<List<BigDecimal>> parityVectors() {
        Random random = new Random(20261111L);
        List<List<BigDecimal>> vectors = new ArrayList<>();
        int[] scales = {8, 16, 40};
        for (int i = 0; i < 400; i++) {
            int length = 2 + random.nextInt(119);
            int scale = scales[i % 3];
            List<BigDecimal> v = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                v.add(randomReturn(random, scale));
            }
            vectors.add(v);
        }
        // 서로 다른 scale 이 섞인 벡터(원화 종목 8자리와 외화 종목 16자리)
        for (int i = 0; i < 100; i++) {
            int length = 6 + random.nextInt(60);
            List<BigDecimal> v = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                v.add(randomReturn(random, random.nextBoolean() ? 8 : 16));
            }
            vectors.add(v);
        }
        // 8자리 경계: 값 −d, 0, d 의 표본 표준편차는 정확히 d 이다 → d 가 정확한 절반·절반 바로 아래·위
        for (String d : new String[] {"0.000000005", "0.000000015", "0.000000004999999999", "0.000000005000000001",
                "0.0123456785", "0.0123456784999", "0.0123456785001", "0.5", "0.00000001"}) {
            BigDecimal x = new BigDecimal(d);
            vectors.add(List.of(x.negate(), BigDecimal.ZERO, x));
        }
        // 길이 2, 같은 값, 0 이 섞인 값
        vectors.add(List.of(new BigDecimal("0.1"), new BigDecimal("0.1")));
        vectors.add(List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        vectors.add(List.of(new BigDecimal("-0.99999999"), new BigDecimal("12.34567890")));
        return vectors;
    }

    /** 고정 시드 무작위 수익률 벡터(scale 8·16·40, 길이 2–120) — Java 표본 표준편차 8자리가 PostgreSQL stddev_samp 와 같다. */
    @Test
    void 표본_표준편차_8자리가_PG_stddev_samp와_같다() throws Exception {
        List<List<BigDecimal>> vectors = parityVectors();
        List<String> mismatches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ROUND(stddev_samp(u.x), 8) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            for (int i = 0; i < vectors.size(); i++) {
                List<BigDecimal> v = vectors.get(i);
                statement.setString(1, v.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",", "{", "}")));
                BigDecimal expected;
                try (var result = statement.executeQuery()) {
                    result.next();
                    expected = result.getBigDecimal(1);
                }
                BigDecimal actual = MoneyMath.sampleStddev8(v);
                if (expected.compareTo(actual) != 0 || expected.scale() != actual.scale()) {
                    mismatches.add("벡터 " + i + " 길이 " + v.size() + " PG=" + expected.toPlainString() + " Java=" + actual.toPlainString());
                }
            }
        }
        assertThat(vectors.size()).isGreaterThan(500);
        assertThat(mismatches).as("PG 와 갈린 벡터").isEmpty();
    }

    /** 분산 단계(반올림 모드·자릿수)를 PG {@code var_samp} 와 값·scale 까지 직접 대조한다. HALF_UP → DOWN 변이가 여기서 갈린다. */
    @Test
    void 표본_분산이_PG_var_samp와_값과_자릿수까지_같다() throws Exception {
        List<List<BigDecimal>> vectors = parityVectors();
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT var_samp(u.x) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            for (int i = 0; i < vectors.size(); i++) {
                List<BigDecimal> v = vectors.get(i);
                statement.setString(1, v.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",", "{", "}")));
                BigDecimal expected;
                try (var result = statement.executeQuery()) {
                    result.next();
                    expected = result.getBigDecimal(1);
                }
                BigDecimal actual = MoneyMath.sampleVariance(v);
                if (expected.signum() == 0 && actual.signum() == 0) {
                    continue; // 모두 같은 값 — 자릿수는 의미 없는 0
                }
                compared++;
                if (expected.compareTo(actual) != 0 || expected.scale() != actual.scale()) {
                    mismatches.add("벡터 " + i + " 길이 " + v.size() + " PG scale=" + expected.scale() + " Java scale=" + actual.scale());
                }
            }
        }
        assertThat(compared).isGreaterThan(500);
        assertThat(mismatches).as("PG 와 갈린 벡터").isEmpty();
    }

    /**
     * 분산 자릿수(18)와 8자리 절반 경계를 직접 PG 와 대조하는 합성 사례. 값 ±1.092186116, ±0.735232641(scale 9)의 분산은 (M + 2/3)·10^-18 꼴이고
     * 표준편차는 정확히 1.075000015 근방이라 8자리 반올림 경계에 놓인다. PG 의 분산 자릿수(scale)와 최종 값을 함께 단언한다.
     * 한계: 분산 반올림 모드(HALF_UP → DOWN)만 바꾸는 변이는 이 사례로도 못 잡는다. 분산 한 단위(10^-18)의 차이가 제곱근에서
     * 1/(2σ) 단위 &lt; 0.5 로 줄어 같은 자리로 다시 반올림되기 때문이다(σ ≥ 1.05 인 구간에서 분산 자릿수가 2s 와 같아진다).
     */
    @Test
    void 분산_반올림이_경계를_넘는_합성_벡터가_PG와_같다() throws Exception {
        List<BigDecimal> v = List.of(new BigDecimal("-1.092186116"), new BigDecimal("-0.735232641"),
                new BigDecimal("0.735232641"), new BigDecimal("1.092186116"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ROUND(stddev_samp(u.x), 8), scale(var_samp(u.x)) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            statement.setString(1, v.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",", "{", "}")));
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getBigDecimal(1)).isEqualByComparingTo("1.07500002");
                assertThat(result.getInt(2)).as("분산 자릿수").isEqualTo(18);
            }
        }
        assertThat(MoneyMath.sampleStddev8(v)).isEqualByComparingTo("1.07500002");
    }

    @Test
    void 길이_1_벡터는_PG도_Java도_널이다() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT stddev_samp(u.x) FROM unnest(CAST(? AS numeric[])) AS u(x)")) {
            statement.setString(1, "{0.5}");
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getBigDecimal(1)).isNull();
            }
        }
        assertThat(MoneyMath.sampleStddev8(List.of(new BigDecimal("0.5")))).isNull();
    }

    /** (n + 0.0…0)/d 의 값과 소수 자릿수(scale)가 PostgreSQL 과 같다 — 분자·분모 scale 과 몫의 크기를 넓게 훑는다. */
    @Test
    void pad40Divide_값과_자릿수가_PG_나눗셈과_같다() throws Exception {
        Random random = new Random(40404040L);
        int[] numeratorScales = {0, 4, 8, 16, 17, 40, 45};
        int[] denominatorScales = {0, 4, 8, 16, 17, 40, 45};
        List<String[]> cases = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            BigDecimal n = randomDecimal(random, numeratorScales[random.nextInt(numeratorScales.length)], 1 + random.nextInt(12));
            BigDecimal d = randomDecimal(random, denominatorScales[random.nextInt(denominatorScales.length)], 1 + random.nextInt(12));
            if (d.signum() == 0) {
                continue;
            }
            cases.add(new String[] {n.toPlainString(), d.toPlainString()});
        }
        // 몫 크기 경계: 매우 작은 몫(자릿수가 40을 넘는 영역), 큰 몫, 0 분자, 정확한 절반
        cases.add(new String[] {"1", "1" + "0".repeat(30)});
        cases.add(new String[] {"1", "1" + "0".repeat(25)});
        cases.add(new String[] {"1", "1" + "0".repeat(24)});
        cases.add(new String[] {"1", "1" + "0".repeat(23)});
        cases.add(new String[] {"123456789", "0.0001"});
        cases.add(new String[] {"0", "7"});
        cases.add(new String[] {"-5", "3"});
        cases.add(new String[] {"1." + "0".repeat(39) + "1", "2"});
        cases.add(new String[] {"9999", "10000"});
        cases.add(new String[] {"10000", "9999"});
        cases.add(new String[] {"-3.5", "-0.0007"});
        List<String> mismatches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT (CAST(? AS numeric) + " + PAD40 + ") / CAST(? AS numeric)")) {
            for (String[] c : cases) {
                statement.setString(1, c[0]);
                statement.setString(2, c[1]);
                BigDecimal expected;
                try (var result = statement.executeQuery()) {
                    result.next();
                    expected = result.getBigDecimal(1);
                }
                BigDecimal actual = MoneyMath.pad40Divide(new BigDecimal(c[0]), new BigDecimal(c[1]));
                if (expected.compareTo(actual) != 0 || expected.scale() != actual.scale()) {
                    mismatches.add(c[0] + " / " + c[1] + " → PG scale " + expected.scale() + ", Java scale " + actual.scale());
                }
            }
        }
        assertThat(cases.size()).isGreaterThan(600);
        assertThat(mismatches).as("PG 와 갈린 나눗셈").isEmpty();
    }

    /** 수익률 꼴: (−0.5, 0.5) 안의 무작위 값, 소수 {@code scale} 자리. */
    private static BigDecimal randomReturn(Random random, int scale) {
        BigInteger limit = BigInteger.TEN.pow(scale).divide(BigInteger.TWO);
        BigInteger magnitude = new BigInteger(limit.bitLength(), random).mod(limit);
        return new BigDecimal(random.nextBoolean() ? magnitude : magnitude.negate(), scale);
    }

    /** 자릿수가 {@code digits} 개 안팎인 무작위 값, 소수 {@code scale} 자리. */
    private static BigDecimal randomDecimal(Random random, int scale, int digits) {
        BigInteger magnitude = new BigInteger(Math.max(1, digits * 3), random);
        return new BigDecimal(random.nextInt(5) == 0 ? magnitude.negate() : magnitude, scale);
    }

    private static void installPureFunctions(Connection connection) throws Exception {
        String source = Files.readString(Path.of("db/21_team_buy_def.sql"));
        Map<String, String> functions = Map.of(
                "buy_rate", extract(source, "CREATE OR REPLACE FUNCTION pg_temp.buy_rate"),
                "buy_fee", extract(source, "CREATE OR REPLACE FUNCTION pg_temp.buy_fee"),
                "buy_affordable", extract(source, "CREATE OR REPLACE FUNCTION pg_temp.buy_affordable"),
                "buy_avg_price", extract(source, "CREATE OR REPLACE FUNCTION pg_temp.buy_avg_price"),
                "team_qty", extract(source, "CREATE OR REPLACE FUNCTION pg_temp.team_qty"));
        for (String function : functions.values()) {
            try (var statement = connection.createStatement()) {
                statement.execute(function);
            }
        }
    }

    private static String extract(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("SQL 함수 정의를 찾지 못했다");
        }
        int end = source.indexOf("$$;", start);
        if (end < 0) {
            throw new IllegalStateException("SQL 함수 종료 표식을 찾지 못했다");
        }
        return source.substring(start, end + 3);
    }

    private static BigDecimal sql(Connection connection, String query, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof BigDecimal decimal) {
                    statement.setBigDecimal(i + 1, decimal);
                } else if (value instanceof Integer integer) {
                    statement.setInt(i + 1, integer);
                } else {
                    statement.setString(i + 1, (String) value);
                }
            }
            try (var result = statement.executeQuery()) {
                result.next();
                Object value = result.getObject(1);
                return value instanceof Boolean booleanValue
                        ? (booleanValue ? BigDecimal.ONE : BigDecimal.ZERO)
                        : result.getBigDecimal(1);
            }
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

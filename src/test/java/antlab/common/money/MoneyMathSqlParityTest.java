package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
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

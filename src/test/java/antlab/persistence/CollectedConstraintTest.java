package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bad row per V5 constraint, checked by SQLSTATE and constraint name. PostgreSQL reports only the first violated
 * CHECK by name (erd 2), so each case breaks exactly one rule. Each case runs in its own rolled-back transaction
 * and uses synthetic assets and prices only.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CollectedConstraintTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String START = "TIMESTAMPTZ '2030-01-03 09:00:00+09'";
    private static final String FINISH = "TIMESTAMPTZ '2030-01-03 09:01:00+09'";

    @Autowired
    DataSource dataSource;

    /** One rolled-back transaction plus row builders; only test constants and just-returned keys are concatenated into SQL. */
    final class Tx implements AutoCloseable {
        final Connection c;

        Tx() throws SQLException {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
        }

        long asset() throws SQLException {
            int n = SEQ.incrementAndGet();
            long group = queryLong("INSERT INTO asset_group (code, name, kind) VALUES ('FAKE_COLL_" + n
                    + "', '가짜 묶음', 'SECTOR') RETURNING id");
            return queryLong("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, "
                    + "return_basis) VALUES ('KR', '" + String.format("9%05d", n) + "', '가짜 종목', 'STOCK', 'KRW', "
                    + group + ", DATE '2000-01-04', 'PRICE') RETURNING id");
        }

        /** A finished run; override column to SQL expression to break one rule. */
        long run(Map<String, String> over) throws SQLException {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("job_name", "'KIS_DAILY_PRICE'");
            v.put("run_trigger", "'MANUAL'");
            v.put("status", "'SUCCEEDED'");
            v.put("started_at", START);
            v.put("finished_at", FINISH);
            v.put("request_count", "5");
            v.put("inserted_count", "3");
            v.put("revised_count", "1");
            v.put("unchanged_count", "2");
            v.put("failed_target_count", "0");
            v.putAll(over);
            return queryLong("INSERT INTO job_run (" + String.join(", ", v.keySet()) + ") VALUES ("
                    + String.join(", ", v.values()) + ") RETURNING id");
        }

        long run() throws SQLException {
            return run(Map.of());
        }

        /** A running run: no finish time, no counts. */
        long runningRun(String jobName) throws SQLException {
            return run(Map.of("job_name", "'" + jobName + "'", "status", "'RUNNING'", "finished_at", "NULL",
                    "request_count", "NULL", "inserted_count", "NULL", "revised_count", "NULL",
                    "unchanged_count", "NULL", "failed_target_count", "NULL"));
        }

        /** A price row for (asset, run); override column to SQL expression to break one rule. */
        long price(long asset, long run, Map<String, String> over) throws SQLException {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("asset_id", Long.toString(asset));
            v.put("trade_date", "DATE '2030-01-02'");
            v.put("price_basis", "'ADJUSTED'");
            v.put("source", "'KIS'");
            v.put("exchange_code", "NULL");
            v.put("close_price", "12345.6789");
            v.put("vintage_no", "1");
            v.put("observed_at", START);
            v.put("job_run_id", Long.toString(run));
            v.putAll(over);
            return queryLong("INSERT INTO asset_daily_price (" + String.join(", ", v.keySet()) + ") VALUES ("
                    + String.join(", ", v.values()) + ") RETURNING id");
        }

        long price(long asset, long run) throws SQLException {
            return price(asset, run, Map.of());
        }

        void exec(String sql) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        long queryLong(String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Override
        public void close() throws SQLException {
            c.rollback();
            c.close();
        }
    }

    interface Body {
        void run(Tx tx) throws Exception;
    }

    /** The body must end in a SQL error with the expected SQLSTATE and constraint name (column name for NOT NULL). */
    private void expectViolation(String sqlState, String name, Body body) throws Exception {
        try (Tx tx = new Tx()) {
            body.run(tx);
            throw new AssertionError("제약 위반이 나야 한다: " + name);
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as(name + " SQLSTATE").isEqualTo(sqlState);
            assertThat(nameIn(e.getMessage())).as(name + " 제약 이름").isEqualTo(name);
        }
    }

    /** Extracts the constraint name from the first line of the server message; a NOT NULL violation has none, so the column name. */
    private static String nameIn(String message) {
        String first = message.lines().findFirst().orElse(message);
        Matcher m = Pattern.compile("constraint \"([^\"]+)\"").matcher(first);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("column \"([^\"]+)\"").matcher(first);
        return m.find() ? m.group(1) : first;
    }

    // --- happy paths -----------------------------------------------------------------------------------------

    @Test
    void 실행_기록과_종가_한_행이_들어간다() throws Exception {
        try (Tx tx = new Tx()) {
            long run = tx.run();
            long price = tx.price(tx.asset(), run);

            assertThat(tx.queryLong("SELECT vintage_no FROM asset_daily_price WHERE id = " + price)).isEqualTo(1);
            assertThat(tx.queryLong("SELECT count(*) FROM job_run WHERE id = " + run + " AND updated_at IS NOT NULL"))
                    .isEqualTo(1);
        }
    }

    @Test
    void 진행_중_실행은_종료일시와_개수가_비어_있다() throws Exception {
        try (Tx tx = new Tx()) {
            long run = tx.runningRun("KIS_DAILY_PRICE");

            assertThat(tx.queryLong("SELECT count(*) FROM job_run WHERE id = " + run
                    + " AND status = 'RUNNING' AND finished_at IS NULL AND request_count IS NULL")).isEqualTo(1);
        }
    }

    @Test
    void 끝난_실행_뒤에는_같은_잡이_다시_진행_중이_될_수_있다() throws Exception {
        try (Tx tx = new Tx()) {
            tx.run();
            long again = tx.runningRun("KIS_DAILY_PRICE");

            assertThat(again).isPositive();
        }
    }

    @Test
    void 일부_실패한_실행은_오류_코드를_갖는다() throws Exception {
        try (Tx tx = new Tx()) {
            long run = tx.run(Map.of("status", "'PARTIAL'", "failed_target_count", "1", "error_code", "'KIS_REJECTED'",
                    "error_detail", "'masked'"));

            assertThat(run).isPositive();
        }
    }

    @Test
    void 미국_종목은_거래소_코드를_갖고_원본_종가는_소수_넷째_자리까지다() throws Exception {
        try (Tx tx = new Tx()) {
            long asset = tx.asset();
            long run = tx.run();
            long price = tx.price(asset, run, Map.of("exchange_code", "'NAS'", "close_price", "123.4567"));

            assertThat(tx.queryLong("SELECT close_price * 10000 FROM asset_daily_price WHERE id = " + price))
                    .isEqualTo(1234567);
        }
    }

    @Test
    void 같은_날짜의_다른_빈티지와_다른_가격_기준은_함께_있을_수_있다() throws Exception {
        try (Tx tx = new Tx()) {
            long asset = tx.asset();
            long run = tx.run();
            tx.price(asset, run);
            tx.price(asset, run, Map.of("vintage_no", "2", "close_price", "12346.0000",
                    "observed_at", "TIMESTAMPTZ '2030-01-04 09:00:00+09'"));
            tx.price(asset, run, Map.of("price_basis", "'RAW'"));

            assertThat(tx.queryLong("SELECT count(*) FROM asset_daily_price WHERE asset_id = " + asset)).isEqualTo(3);
        }
    }

    // --- cases that must fail --------------------------------------------------------------------------------

    record Case(String title, String sqlState, String name, Body body) {
        @Override
        public String toString() {
            return title;
        }
    }

    private static Case price(String title, String sqlState, String name, Map<String, String> over) {
        return new Case(title, sqlState, name, tx -> tx.price(tx.asset(), tx.run(), over));
    }

    private static Case run(String title, String sqlState, String name, Map<String, String> over) {
        return new Case(title, sqlState, name, tx -> tx.run(over));
    }

    static Stream<Case> priceCases() {
        return Stream.of(
                price("종가 0", "23514", "ck_asset_daily_price_close_positive", Map.of("close_price", "0")),
                price("종가 음수", "23514", "ck_asset_daily_price_close_positive", Map.of("close_price", "-1")),
                price("종가 NaN", "23514", "ck_asset_daily_price_close_positive", Map.of("close_price", "'NaN'")),
                price("빈티지 0", "23514", "ck_asset_daily_price_vintage_no", Map.of("vintage_no", "0")),
                price("거래일이 관측일(KST)과 같음", "23514", "ck_asset_daily_price_after_session",
                        Map.of("trade_date", "DATE '2030-01-03'")),
                price("거래일이 관측일(KST)보다 뒤", "23514", "ck_asset_daily_price_after_session",
                        Map.of("trade_date", "DATE '2030-01-04'")),
                price("UTC로는 전날 저녁이지만 KST로는 거래일과 같은 날 관측", "23514", "ck_asset_daily_price_after_session",
                        Map.of("trade_date", "DATE '2030-01-03'", "observed_at", "TIMESTAMPTZ '2030-01-02 20:00:00+00'")),
                price("거래소 코드 목록 밖", "23514", "ck_asset_daily_price_exchange_code", Map.of("exchange_code", "'XXX'")),
                price("가격 기준 소문자", "23514", "ck_asset_daily_price_price_basis", Map.of("price_basis", "'adjusted'")),
                price("출처 목록 밖", "23514", "ck_asset_daily_price_source", Map.of("source", "'YF'")),
                price("거래일 없음", "23502", "trade_date", Map.of("trade_date", "NULL")),
                price("종가 없음", "23502", "close_price", Map.of("close_price", "NULL")),
                price("관측일시 없음", "23502", "observed_at", Map.of("observed_at", "NULL")),
                price("없는 종목", "23503", "fk_asset_daily_price_asset", Map.of("asset_id", "-1")),
                price("없는 실행", "23503", "fk_asset_daily_price_job_run", Map.of("job_run_id", "-1")));
    }

    static Stream<Case> runCases() {
        return Stream.of(
                run("잡 이름 목록 밖", "23514", "ck_job_run_job_name", Map.of("job_name", "'OTHER_JOB'")),
                run("실행 방식 목록 밖", "23514", "ck_job_run_run_trigger", Map.of("run_trigger", "'AUTO'")),
                run("상태 목록 밖", "23514", "ck_job_run_status", Map.of("status", "'DONE'")),
                run("진행 중인데 종료일시가 있음", "23514", "ck_job_run_finished", Map.of("status", "'RUNNING'",
                        "request_count", "NULL", "inserted_count", "NULL", "revised_count", "NULL",
                        "unchanged_count", "NULL", "failed_target_count", "NULL")),
                run("끝났는데 종료일시가 없음", "23514", "ck_job_run_finished", Map.of("finished_at", "NULL")),
                run("종료일시가 시작일시보다 앞", "23514", "ck_job_run_finished",
                        Map.of("finished_at", "TIMESTAMPTZ '2030-01-03 08:59:00+09'")),
                run("성공인데 개수가 없음", "23514", "ck_job_run_counts", Map.of("request_count", "NULL",
                        "inserted_count", "NULL", "revised_count", "NULL", "unchanged_count", "NULL",
                        "failed_target_count", "NULL")),
                run("성공인데 개수 하나만 없음", "23514", "ck_job_run_counts", Map.of("unchanged_count", "NULL")),
                run("요청 수 음수", "23514", "ck_job_run_counts", Map.of("request_count", "-1")),
                run("진행 중인데 개수가 있음", "23514", "ck_job_run_counts", Map.of("status", "'RUNNING'",
                        "finished_at", "NULL")),
                run("정정 수가 추가 수보다 큼", "23514", "ck_job_run_revised_le_inserted",
                        Map.of("inserted_count", "1", "revised_count", "2")),
                run("실패인데 오류 코드 없음", "23514", "ck_job_run_error", Map.of("status", "'FAILED'")),
                run("일부 실패인데 오류 코드 없음", "23514", "ck_job_run_error", Map.of("status", "'PARTIAL'")),
                run("성공인데 오류 코드 있음", "23514", "ck_job_run_error", Map.of("error_code", "'KIS_HTTP'")),
                run("오류 코드 소문자", "23514", "ck_job_run_error_code_format",
                        Map.of("status", "'FAILED'", "error_code", "'kis_http'")),
                run("오류 코드 숫자로 시작", "23514", "ck_job_run_error_code_format",
                        Map.of("status", "'FAILED'", "error_code", "'1_HTTP'")),
                run("잡 이름 없음", "23502", "job_name", Map.of("job_name", "NULL")),
                run("시작일시 없음", "23502", "started_at", Map.of("started_at", "NULL")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("priceCases")
    void 종가_표의_나쁜_행은_이름_붙은_제약이_막는다(Case c) throws Exception {
        expectViolation(c.sqlState(), c.name(), c.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runCases")
    void 실행_기록의_나쁜_행은_이름_붙은_제약이_막는다(Case c) throws Exception {
        expectViolation(c.sqlState(), c.name(), c.body());
    }

    @Test
    void 같은_종목_날짜_기준_빈티지는_둘_들어갈_수_없다() throws Exception {
        expectViolation("23505", "uq_asset_daily_price_vintage", tx -> {
            long asset = tx.asset();
            long run = tx.run();
            tx.price(asset, run);
            tx.price(asset, run, Map.of("close_price", "99.0000"));
        });
    }

    @Test
    void 같은_잡이_진행_중인_채로_둘_수_없다() throws Exception {
        expectViolation("23505", "uq_job_run_running", tx -> {
            tx.runningRun("KIS_DAILY_PRICE");
            tx.runningRun("KIS_DAILY_PRICE");
        });
    }

    @Test
    void 종가가_가리키는_실행은_지울_수_없다() throws Exception {
        expectViolation("23503", "fk_asset_daily_price_job_run", tx -> {
            long run = tx.run();
            tx.price(tx.asset(), run);
            tx.exec("DELETE FROM job_run WHERE id = " + run);
        });
    }

    @Test
    void 종가가_가리키는_종목은_지울_수_없다() throws Exception {
        expectViolation("23503", "fk_asset_daily_price_asset", tx -> {
            long asset = tx.asset();
            tx.price(asset, tx.run());
            tx.exec("DELETE FROM asset WHERE id = " + asset);
        });
    }
}

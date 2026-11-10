package antlab.support;

import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import java.util.ArrayList;
import java.util.List;

/**
 * 골든 비교 대상 테이블의 조회와 비교 명세. 대리 키는 자연 키(시장·종목 코드, 턴 번호, 재생 행동 키)로 바꿔 내보낸다.
 * 모든 질의는 {@code :sid} 로 시즌 하나를 가리키고, 열 타입을 명시한다. 업무 공식은 여기서 다시 계산하지 않는다.
 *
 * <p>현재 SQL(20·21·23)이 채우는 테이블만 담는다. 매도·전술 변경·피드 테이블은 25·26 및 P0-33 이후 기능이 머지된 뒤 확장한다.
 */
public final class GoldenRowQueries {

    /** 질의 한 쌍 */
    public record GoldenTable(RowQuery query, ComparisonSpec spec) {
        public String name() {
            return query.name();
        }
    }

    /** 연습 시즌을 이메일로 찾는 질의 (번호표는 DB마다 다를 수 있다) */
    public static final String PRACTICE_SEASON_SQL =
            "SELECT se.id FROM season se JOIN member m ON m.id = se.member_id "
                    + "WHERE lower(m.email) = lower('phase0@example.invalid')";

    /**
     * 주문의 재생 행동 키를 만드는 SQL·Java 조각은 이 아래 세 곳에만 둔다 (키 정의가 바뀔 때 함께 바뀌어야 한다).
     * 키: 턴·시장·종목·방향·같은 묶음 안의 순번.
     */
    private static final String ORDER_NTH_SQL =
            "row_number() OVER (PARTITION BY o.turn_no, o.asset_id, o.side ORDER BY o.id)";

    private static final String ORDER_CTE = """
            WITH ord AS (
              SELECT o.id, o.turn_no, a.market, a.ticker, o.side,
                     %s AS nth
              FROM orders o JOIN asset a ON a.id = o.asset_id
              WHERE o.season_id = :sid)
            """.formatted(ORDER_NTH_SQL);

    /** {@link #ORDER_CTE} 의 {@code ord} 행으로 만든 키 문자열 (아래 {@link #orderKey} 와 같은 모양) */
    private static final String ORD_KEY_SQL =
            "ord.turn_no || '/' || ord.market || '/' || ord.ticker || '/' || ord.side || '/' || ord.nth";

    /** 재생 입력을 꺼내는 주문 질의 (실행 순서: 턴, 주문 번호) */
    public static String orderCaptureSql(long seasonId) {
        return """
                SELECT o.id, o.turn_no, a.market, a.ticker, o.side, o.quantity, %s AS nth
                FROM orders o JOIN asset a ON a.id = o.asset_id WHERE o.season_id = %d ORDER BY o.turn_no, o.id"""
                .formatted(ORDER_NTH_SQL, seasonId);
    }

    /** 재생 행동 키 문자열. {@link #ORD_KEY_SQL} 과 같은 모양이어야 한다. */
    public static String orderKey(long turnNo, String market, String ticker, String side, long nth) {
        return turnNo + "/" + market + "/" + ticker + "/" + side + "/" + nth;
    }

    private GoldenRowQueries() {
    }

    private static RowColumn col(String name, ColumnType type) {
        return new RowColumn(name, type);
    }

    public static GoldenTable season() {
        RowQuery q = new RowQuery("season", """
                SELECT 'practice' AS scope, se.status, se.path_length, se.current_turn_no, se.seed, se.engine_version,
                       se.data_snapshot_date, se.base_date, se.initial_cash, se.fee_rate_kr, se.fee_rate_us,
                       se.fx_spread_rate, se.risk_limit,
                       se.start_goalkeeper, se.start_defender, se.start_midfielder, se.start_forward,
                       se.current_goalkeeper, se.current_defender, se.current_midfielder, se.current_forward
                FROM season se WHERE se.id = :sid""", List.of(
                col("scope", ColumnType.TEXT), col("status", ColumnType.TEXT), col("path_length", ColumnType.LONG),
                col("current_turn_no", ColumnType.LONG), col("seed", ColumnType.LONG),
                col("engine_version", ColumnType.TEXT), col("data_snapshot_date", ColumnType.DATE),
                col("base_date", ColumnType.DATE), col("initial_cash", ColumnType.DECIMAL),
                col("fee_rate_kr", ColumnType.DECIMAL), col("fee_rate_us", ColumnType.DECIMAL),
                col("fx_spread_rate", ColumnType.DECIMAL), col("risk_limit", ColumnType.DECIMAL),
                col("start_goalkeeper", ColumnType.LONG), col("start_defender", ColumnType.LONG),
                col("start_midfielder", ColumnType.LONG), col("start_forward", ColumnType.LONG),
                col("current_goalkeeper", ColumnType.LONG), col("current_defender", ColumnType.LONG),
                col("current_midfielder", ColumnType.LONG), col("current_forward", ColumnType.LONG)));
        return new GoldenTable(q, ComparisonSpec.named("season").keys("scope")
                .values("status", "path_length", "current_turn_no", "seed", "engine_version", "data_snapshot_date",
                        "base_date", "initial_cash", "fee_rate_kr", "fee_rate_us", "fx_spread_rate", "risk_limit",
                        "start_goalkeeper", "start_defender", "start_midfielder", "start_forward",
                        "current_goalkeeper", "current_defender", "current_midfielder", "current_forward")
                .build());
    }

    public static GoldenTable seasonPath() {
        RowQuery q = new RowQuery("season_path", """
                SELECT sp.turn_no, mb.month_start
                FROM season_path sp JOIN month_block mb ON mb.id = sp.month_block_id
                WHERE sp.season_id = :sid ORDER BY sp.turn_no""", List.of(
                col("turn_no", ColumnType.LONG), col("month_start", ColumnType.DATE)));
        return new GoldenTable(q, ComparisonSpec.named("season_path").keys("turn_no").values("month_start").build());
    }

    public static GoldenTable turnPrice() {
        RowQuery q = new RowQuery("turn_price", """
                SELECT tp.turn_no, a.market, a.ticker, tp.price
                FROM turn_price tp JOIN asset a ON a.id = tp.asset_id
                WHERE tp.season_id = :sid""", List.of(
                col("turn_no", ColumnType.LONG), col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT),
                col("price", ColumnType.DECIMAL)));
        return new GoldenTable(q, ComparisonSpec.named("turn_price").keys("turn_no", "market", "ticker")
                .values("price").build());
    }

    public static GoldenTable turn() {
        RowQuery q = new RowQuery("turn", """
                SELECT t.turn_no, t.cash_balance, t.holdings_value, t.total_value, t.goalkeeper_value,
                       t.defender_value, t.midfielder_value, t.forward_value, t.drawdown
                FROM turn t WHERE t.season_id = :sid""", List.of(
                col("turn_no", ColumnType.LONG), col("cash_balance", ColumnType.DECIMAL),
                col("holdings_value", ColumnType.DECIMAL), col("total_value", ColumnType.DECIMAL),
                col("goalkeeper_value", ColumnType.DECIMAL), col("defender_value", ColumnType.DECIMAL),
                col("midfielder_value", ColumnType.DECIMAL), col("forward_value", ColumnType.DECIMAL),
                col("drawdown", ColumnType.DECIMAL)));
        return new GoldenTable(q, ComparisonSpec.named("turn").keys("turn_no")
                .values("cash_balance", "holdings_value", "total_value", "goalkeeper_value", "defender_value",
                        "midfielder_value", "forward_value", "drawdown").build());
    }

    public static GoldenTable cashAccount() {
        RowQuery q = new RowQuery("cash_account", """
                SELECT 'practice' AS scope, c.balance, c.reserved FROM cash_account c WHERE c.season_id = :sid""",
                List.of(col("scope", ColumnType.TEXT), col("balance", ColumnType.DECIMAL),
                        col("reserved", ColumnType.DECIMAL)));
        return new GoldenTable(q, ComparisonSpec.named("cash_account").keys("scope").values("balance", "reserved")
                .build());
    }

    public static GoldenTable holding() {
        RowQuery q = new RowQuery("holding", """
                SELECT a.market, a.ticker, h.quantity, h.avg_price
                FROM holding h JOIN asset a ON a.id = h.asset_id WHERE h.season_id = :sid""", List.of(
                col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT), col("quantity", ColumnType.LONG),
                col("avg_price", ColumnType.DECIMAL)));
        return new GoldenTable(q, ComparisonSpec.named("holding").keys("market", "ticker")
                .values("quantity", "avg_price").build());
    }

    public static GoldenTable orders() {
        RowQuery q = new RowQuery("orders", """
                SELECT o.turn_no, a.market, a.ticker, o.side,
                       %s AS nth,
                       o.type, o.quantity, o.limit_price, o.status, o.source,
                       (o.formation_change_id IS NOT NULL) AS has_formation_change,
                       (o.turn_feed_id IS NOT NULL) AS has_turn_feed
                FROM orders o JOIN asset a ON a.id = o.asset_id WHERE o.season_id = :sid""".formatted(ORDER_NTH_SQL), List.of(
                col("turn_no", ColumnType.LONG), col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT),
                col("side", ColumnType.TEXT), col("nth", ColumnType.LONG), col("type", ColumnType.TEXT),
                col("quantity", ColumnType.LONG), col("limit_price", ColumnType.DECIMAL),
                col("status", ColumnType.TEXT), col("source", ColumnType.TEXT),
                col("has_formation_change", ColumnType.BOOLEAN), col("has_turn_feed", ColumnType.BOOLEAN)));
        return new GoldenTable(q, ComparisonSpec.named("orders").keys("turn_no", "market", "ticker", "side", "nth")
                .values("type", "quantity", "limit_price", "status", "source", "has_formation_change", "has_turn_feed")
                .build());
    }

    public static GoldenTable execution() {
        RowQuery q = new RowQuery("execution", ORDER_CTE + """
                SELECT ord.turn_no, ord.market, ord.ticker, ord.side, ord.nth,
                       row_number() OVER (PARTITION BY ord.id ORDER BY e.id) AS exec_seq,
                       e.fill_price, e.quantity, e.fee, e.tax, e.realized_pnl
                FROM execution e JOIN ord ON ord.id = e.order_id""", List.of(
                col("turn_no", ColumnType.LONG), col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT),
                col("side", ColumnType.TEXT), col("nth", ColumnType.LONG), col("exec_seq", ColumnType.LONG),
                col("fill_price", ColumnType.DECIMAL), col("quantity", ColumnType.LONG),
                col("fee", ColumnType.DECIMAL), col("tax", ColumnType.DECIMAL),
                col("realized_pnl", ColumnType.DECIMAL)));
        return new GoldenTable(q, ComparisonSpec.named("execution")
                .keys("turn_no", "market", "ticker", "side", "nth", "exec_seq")
                .values("fill_price", "quantity", "fee", "tax", "realized_pnl").build());
    }

    /**
     * 키: 주문 행동 키(주문 없는 행은 '-')·유형·같은 주문·유형 안의 순번. 전역 순번은 키로 쓰지 않는다 —
     * 한 행이 빠지거나 같은 주문의 BUY·FEE 쓰기 순서가 달라도 뒤의 모든 행이 어긋나 보이지 않게 하려는 것이다.
     */
    public static GoldenTable cashLedger() {
        RowQuery q = new RowQuery("cash_ledger", ORDER_CTE + """
                SELECT coalesce(%s, '-') AS order_key, l.type,
                       row_number() OVER (PARTITION BY l.ref_order_id, l.type ORDER BY l.id) AS nth,
                       row_number() OVER (ORDER BY l.id) AS seq,
                       l.amount, l.balance_after
                FROM cash_ledger l LEFT JOIN ord ON ord.id = l.ref_order_id WHERE l.season_id = :sid"""
                .formatted(ORD_KEY_SQL), List.of(
                col("order_key", ColumnType.TEXT), col("type", ColumnType.TEXT), col("nth", ColumnType.LONG),
                col("seq", ColumnType.LONG), col("amount", ColumnType.DECIMAL),
                col("balance_after", ColumnType.DECIMAL)));
        return new GoldenTable(q, ComparisonSpec.named("cash_ledger").keys("order_key", "type", "nth")
                .values("amount", "balance_after")
                .exclude("seq", "전역 기록 순번 — 쓰기 순서가 다른 구현과 비교할 때 한 행 차이가 뒤 행 전체로 번지므로 키·값에서 뺀다")
                .build());
    }

    public static GoldenTable lineup() {
        RowQuery q = new RowQuery("lineup", """
                SELECT a.market, a.ticker, lu.start_turn, lu.declared_position, lu.end_turn
                FROM lineup lu JOIN asset a ON a.id = lu.asset_id WHERE lu.season_id = :sid""", List.of(
                col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT), col("start_turn", ColumnType.LONG),
                col("declared_position", ColumnType.TEXT), col("end_turn", ColumnType.LONG)));
        return new GoldenTable(q, ComparisonSpec.named("lineup").keys("market", "ticker", "start_turn")
                .values("declared_position", "end_turn").build());
    }

    /** 시도 번호는 정규화로 지우지 않는다 */
    public static GoldenTable reconciliationLog() {
        RowQuery q = new RowQuery("reconciliation_log", """
                SELECT r.turn_no, r.attempt_no, r.check_code, r.status, r.expected_value, r.actual_value,
                       r.diff_value, r.mismatch_count, r.classification, r.detail::text AS detail
                FROM reconciliation_log r WHERE r.season_id = :sid""", List.of(
                col("turn_no", ColumnType.LONG), col("attempt_no", ColumnType.LONG),
                col("check_code", ColumnType.TEXT), col("status", ColumnType.TEXT),
                col("expected_value", ColumnType.DECIMAL), col("actual_value", ColumnType.DECIMAL),
                col("diff_value", ColumnType.DECIMAL), col("mismatch_count", ColumnType.LONG),
                col("classification", ColumnType.TEXT), col("detail", ColumnType.JSON)));
        return new GoldenTable(q, ComparisonSpec.named("reconciliation_log").keys("turn_no", "attempt_no", "check_code")
                .values("status", "expected_value", "actual_value", "diff_value", "mismatch_count", "classification",
                        "detail").build());
    }

    /** 현재 SQL(20·21·23)이 채우는 모든 테이블 */
    public static List<GoldenTable> allForTurnGolden() {
        return new ArrayList<>(List.of(season(), seasonPath(), turnPrice(), turn(), cashAccount(), holding(),
                orders(), execution(), cashLedger(), lineup(), reconciliationLog()));
    }
}

package antlab.scoring;

import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.HoldingFact;
import antlab.scoring.SeasonReportSources.OrderFacts;
import antlab.scoring.SeasonReportSources.ReconFacts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 성적표 원천 조회 — 읽기 전용 네이티브 문장 다섯 개. 정수·원 단위 값만 가져오고 비율·평균·변동성은 계산기가 {@code MoneyMath} 한 곳에서
 * 반올림한다. 문장 수는 보유 종목 수·턴 수와 무관하다(고정). 각 문장은 SQL 정의 {@code db/26_season_report_def.sql} 의 해당 부분과 <b>같은 식</b>이다:
 * <ul>
 *   <li>{@link #orders} — {@code sr_report} 의 {@code po} CTE: 체결을 <b>주문별로 먼저 합친 뒤</b> 턴·출발점 조건으로 걸러 조인 증폭을 막는다.
 *       보고 범위 {@code turn_no < T}, 대금·독려는 1턴 이후, 수수료는 0턴 포함.</li>
 *   <li>{@link #holdings} — {@code sr_hold}: q(t) = turn_no &lt; t 인 체결의 매수 − 매도 수량, 값 = 수량 × 그 턴의 turn_price. 현재 보유로 과거를 만들지 않는다.</li>
 *   <li>{@link #benchmarkPrices} — {@code sr_baseline} 의 입력: 대용 두 종목의 0..T 턴 가격.</li>
 *   <li>{@link #recon} — {@code sr_report} 의 대사 부분: 불일치 행 수, 경로 길이를 넘는 턴의 행 수, (턴, 시도) 묶음 중 17행·15행 수.</li>
 *   <li>{@link #requireConsistent} — {@code sr_t} 의 경로·풀({@code asset_position(base_date)})·풀 가격 검사.</li>
 * </ul>
 * 이 클래스에는 반올림이 없다. 쓰기와 잠금도 없다.
 */
@Component
public class SeasonReportQuery {

    /** 한 턴 전환의 대사 묶음 행 수(SQL 정의의 17). */
    private static final int TURN_BUNDLE_ROWS = 17;
    /** 수동 대사의 묶음 행 수(SQL 정의의 15). */
    private static final int MANUAL_BUNDLE_ROWS = 15;

    private static final String ORDERS = """
            WITH po AS (
                SELECT o.id, o.turn_no, o.source, f.turn_no AS feed_turn,
                       sum(e.fill_price * e.quantity) AS notional, sum(e.fee) AS fee
                FROM orders o
                JOIN execution e ON e.order_id = o.id
                LEFT JOIN turn_feed f ON f.id = o.turn_feed_id
                WHERE o.season_id = :seasonId AND o.turn_no < :reportTurn
                GROUP BY o.id, o.turn_no, o.source, f.turn_no
            )
            SELECT COALESCE(sum(po.notional) FILTER (WHERE po.turn_no >= 1), 0) AS trade_notional,
                   COALESCE(sum(po.fee), 0) AS total_fee,
                   COALESCE(sum(po.fee) FILTER (WHERE po.turn_no = 0), 0) AS team_fee,
                   COALESCE(sum(po.fee) FILTER (WHERE po.turn_no >= 1 AND po.source IN (:platformSources)), 0) AS platform_nudge_fee,
                   COALESCE(sum(po.notional) FILTER (WHERE po.turn_no >= 1 AND po.source IN (:nudgeSources)), 0) AS nudge_notional,
                   COALESCE(sum(po.notional) FILTER (WHERE po.turn_no >= 1 AND po.source IN (:nudgeSources)
                                                      AND (po.source = 'RANKING' OR po.feed_turn = po.turn_no)), 0) AS same_turn_notional,
                   (SELECT count(*) FROM orders p WHERE p.season_id = :seasonId AND p.turn_no = :reportTurn) AS pending_orders,
                   (SELECT count(*) FROM orders p WHERE p.season_id = :seasonId AND p.turn_no > :reportTurn) AS orders_after_report
            FROM po""";

    private static final String HOLDINGS = """
            WITH po AS (
                SELECT o.asset_id, o.turn_no, sum(CASE WHEN o.side = 'BUY' THEN e.quantity ELSE -e.quantity END) AS dq
                FROM orders o
                JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = :seasonId AND o.turn_no < :reportTurn
                GROUP BY o.asset_id, o.turn_no
            )
            SELECT a.market AS market, a.ticker AS ticker, g.n AS turn_no, sum(po.dq) * tp.price AS value
            FROM (SELECT DISTINCT po0.asset_id FROM po po0) x
            CROSS JOIN generate_series(1, CAST(:reportTurn AS integer)) AS g(n)
            JOIN po ON po.asset_id = x.asset_id AND po.turn_no < g.n
            JOIN turn_price tp ON tp.season_id = :seasonId AND tp.asset_id = x.asset_id AND tp.turn_no = g.n
            JOIN asset a ON a.id = x.asset_id
            GROUP BY a.market, a.ticker, g.n, tp.price
            HAVING sum(po.dq) > 0
            ORDER BY a.market, a.ticker, g.n""";

    private static final String BENCHMARK = """
            SELECT a.market AS market, a.ticker AS ticker, tp.turn_no AS turn_no, tp.price AS price
            FROM turn_price tp
            JOIN asset a ON a.id = tp.asset_id
            WHERE tp.season_id = :seasonId AND tp.turn_no BETWEEN 0 AND :reportTurn
              AND ((a.market = :krMarket AND a.ticker = :krTicker) OR (a.market = :usMarket AND a.ticker = :usTicker))
            ORDER BY a.market, a.ticker, tp.turn_no""";

    private static final String RECON = """
            WITH bundle AS (
                SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :seasonId GROUP BY turn_no, attempt_no
            )
            SELECT (SELECT count(*) FROM reconciliation_log WHERE season_id = :seasonId AND status = 'MISMATCH') AS mismatch_rows,
                   (SELECT count(*) FROM reconciliation_log WHERE season_id = :seasonId AND turn_no > :pathLength) AS out_of_range_rows,
                   (SELECT count(*) FROM bundle WHERE n = :turnBundleRows) AS turn_bundles,
                   (SELECT count(*) FROM bundle WHERE n = :manualBundleRows) AS manual_bundles""";

    private static final String CONSISTENCY = """
            SELECT (SELECT count(*) FROM season_path sp
                    WHERE sp.season_id = :seasonId AND sp.turn_no BETWEEN 1 AND :reportTurn) AS path_rows,
                   (SELECT count(*) FROM turn_price t0
                    CROSS JOIN generate_series(0, CAST(:reportTurn AS integer)) AS g(n)
                    LEFT JOIN turn_price tp ON tp.season_id = :seasonId AND tp.asset_id = t0.asset_id AND tp.turn_no = g.n
                    WHERE t0.season_id = :seasonId AND t0.turn_no = 0 AND tp.id IS NULL) AS missing_pool_prices,
                   (SELECT count(*) FROM (
                        (SELECT ap.asset_id FROM asset_position ap
                         WHERE ap.base_date = (SELECT se.base_date FROM season se WHERE se.id = :seasonId)
                         EXCEPT SELECT t0.asset_id FROM turn_price t0 WHERE t0.season_id = :seasonId AND t0.turn_no = 0)
                        UNION ALL
                        (SELECT t0.asset_id FROM turn_price t0 WHERE t0.season_id = :seasonId AND t0.turn_no = 0
                         EXCEPT SELECT ap.asset_id FROM asset_position ap
                         WHERE ap.base_date = (SELECT se.base_date FROM season se WHERE se.id = :seasonId))
                    ) diff) AS pool_mismatch""";

    private final JdbcClient jdbc;

    public SeasonReportQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 주문·체결 집계(26 {@code po} CTE 와 같은 식). 값은 원 단위 정수 합이다. */
    public OrderFacts orders(long seasonId, int reportTurn) {
        return jdbc.sql(ORDERS).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .param("platformSources", ReportRules.PLATFORM_SOURCES)
                .param("nudgeSources", ReportRules.NUDGE_SOURCES)
                .query((rs, n) -> new OrderFacts(rs.getBigDecimal("trade_notional"), rs.getBigDecimal("total_fee"),
                        rs.getBigDecimal("team_fee"), rs.getBigDecimal("platform_nudge_fee"),
                        rs.getBigDecimal("nudge_notional"), rs.getBigDecimal("same_turn_notional"),
                        rs.getInt("pending_orders"), rs.getInt("orders_after_report")))
                .single();
    }

    /** 턴 1..T 의 보유 재구성(26 {@code sr_hold} 와 같은 식): 턴 t 시작 시점에 수량이 0보다 큰 (종목, 턴)과 평가액. */
    public List<HoldingFact> holdings(long seasonId, int reportTurn) {
        return jdbc.sql(HOLDINGS).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .query((rs, n) -> new HoldingFact(new AssetKey(rs.getString("market"), rs.getString("ticker")),
                        rs.getInt("turn_no"), rs.getBigDecimal("value")))
                .list();
    }

    /** 기준선 대용 두 종목의 0..T 턴 가격(26 {@code sr_baseline} 의 입력). 시장 + 티커 자연 키로 찾고 턴 순이다. */
    public Map<AssetKey, List<BigDecimal>> benchmarkPrices(long seasonId, int reportTurn) {
        Map<AssetKey, List<BigDecimal>> out = new LinkedHashMap<>();
        jdbc.sql(BENCHMARK).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .param("krMarket", ReportRules.BENCH_KR_MARKET).param("krTicker", ReportRules.BENCH_KR_TICKER)
                .param("usMarket", ReportRules.BENCH_US_MARKET).param("usTicker", ReportRules.BENCH_US_TICKER)
                .query((rs, n) -> {
                    out.computeIfAbsent(new AssetKey(rs.getString("market"), rs.getString("ticker")), k -> new ArrayList<>())
                            .add(rs.getBigDecimal("price"));
                    return null;
                }).list();
        return out;
    }

    /** 대사 집계(26 {@code sr_report} 의 대사 부분과 같은 식). 건수뿐이며 값·detail 은 읽지 않는다. */
    public ReconFacts recon(long seasonId, int pathLength) {
        return jdbc.sql(RECON).param("seasonId", seasonId).param("pathLength", pathLength)
                .param("turnBundleRows", TURN_BUNDLE_ROWS).param("manualBundleRows", MANUAL_BUNDLE_ROWS)
                .query((rs, n) -> new ReconFacts(rs.getInt("mismatch_rows"), rs.getInt("turn_bundles"),
                        rs.getInt("manual_bundles"), rs.getInt("out_of_range_rows")))
                .single();
    }

    /**
     * 26 {@code sr_t} 의 경로·풀·풀 가격 검사: 경로 1..T 행이 모두 있고, 시즌 풀({@code asset_position(base_date)})과 0턴 가격이 있는
     * 종목 집합이 같고(대칭차 0), 풀 전체의 0..T 가격이 비어 있지 않은지 확인한다.
     * 어긋나면 고정 문장의 {@link IllegalStateException}(금액·번호 없음) — 500 으로 간다.
     */
    public void requireConsistent(long seasonId, int reportTurn) {
        long[] counts = jdbc.sql(CONSISTENCY).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .query((rs, n) -> new long[] {rs.getLong("path_rows"), rs.getLong("missing_pool_prices"),
                        rs.getLong("pool_mismatch")}).single();
        if (counts[0] != reportTurn) {
            throw new IllegalStateException("성적표 원천이 모순이다: 경로가 처음부터 보고 턴까지 이어지지 않는다");
        }
        if (counts[1] != 0) {
            throw new IllegalStateException("성적표 원천이 모순이다: 풀 종목의 턴 가격이 비어 있다");
        }
        if (counts[2] != 0) {
            throw new IllegalStateException("성적표 원천이 모순이다: 시즌 풀과 0턴 가격 종목이 다르다");
        }
    }
}

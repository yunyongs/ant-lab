package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Currency;
import antlab.asset.Position;
import antlab.common.money.MoneyMath;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SyntheticHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Shared by the synthetic parity test and the sample golden test. Reads the raw history tables and the stored {@code asset_position} rows,
 * builds the calculator inputs from the raw tables only (never from the {@code pos_series} view), runs {@link BaseDatePositionCalculator}
 * for every base date and compares all eight columns of every row. Unjudgeable rows must be all null and carry the reason of
 * {@code pos_judge}. Only counts and keys reach messages, never values.
 */
final class PositionParity {

    static final String DEFINITION = "db/12_calc_position_def.sql";

    private static RowColumn col(String name, ColumnType type) {
        return new RowColumn(name, type);
    }

    static final RowQuery ASSETS = new RowQuery("assets", """
            SELECT market AS market, ticker AS ticker, currency AS currency FROM asset ORDER BY market, ticker""",
            List.of(col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT), col("currency", ColumnType.TEXT)));

    static final RowQuery BLOCKS = new RowQuery("blocks", """
            SELECT month_start AS month_start FROM month_block ORDER BY month_start""",
            List.of(col("month_start", ColumnType.DATE)));

    static final RowQuery RETURNS = new RowQuery("returns", """
            SELECT a.market AS market, a.ticker AS ticker, b.month_start AS month_start, r.local_return AS local_return
            FROM asset_month_return r
            JOIN asset a ON a.id = r.asset_id
            JOIN month_block b ON b.id = r.month_block_id
            ORDER BY a.market, a.ticker, b.month_start""",
            List.of(col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT), col("month_start", ColumnType.DATE),
                    col("local_return", ColumnType.DECIMAL)));

    static final RowQuery FX = new RowQuery("fx", """
            SELECT f.currency AS currency, b.month_start AS month_start, f.fx_change AS fx_change
            FROM fx_month f JOIN month_block b ON b.id = f.month_block_id
            ORDER BY f.currency, b.month_start""",
            List.of(col("currency", ColumnType.TEXT), col("month_start", ColumnType.DATE), col("fx_change", ColumnType.DECIMAL)));

    static final RowQuery STORED = new RowQuery("stored", """
            SELECT a.market AS market, a.ticker AS ticker, p.base_date AS base_date, p.window_start AS window_start,
                   p.window_end AS window_end, p.months_used AS months_used, p.up_participation AS up_participation,
                   p.down_participation AS down_participation, p.volatility AS volatility, p.max_drawdown AS max_drawdown,
                   p.system_position AS system_position
            FROM asset_position p JOIN asset a ON a.id = p.asset_id
            ORDER BY p.base_date, a.market, a.ticker""",
            List.of(col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT), col("base_date", ColumnType.DATE),
                    col("window_start", ColumnType.DATE), col("window_end", ColumnType.DATE), col("months_used", ColumnType.LONG),
                    col("up_participation", ColumnType.DECIMAL), col("down_participation", ColumnType.DECIMAL),
                    col("volatility", ColumnType.DECIMAL), col("max_drawdown", ColumnType.DECIMAL),
                    col("system_position", ColumnType.TEXT)));

    /** The reason as the SQL definition says it; read in a session that has loaded {@link #DEFINITION}. */
    static final RowQuery REASONS = new RowQuery("reasons", """
            SELECT a.market AS market, a.ticker AS ticker, j.base_date AS base_date, j.reason AS reason
            FROM pos_judge j JOIN asset a ON a.id = j.asset_id
            ORDER BY j.base_date, a.market, a.ticker""",
            List.of(col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT), col("base_date", ColumnType.DATE),
                    col("reason", ColumnType.TEXT)));

    /** The market drawdown of every base date that has a judged row, as the SQL definition computes it (one value per base date). */
    static final RowQuery MARKET_MDD = new RowQuery("market_mdd", """
            SELECT m.base_date AS base_date, min(m.mkt_max_drawdown) AS mkt_max_drawdown, max(m.mkt_max_drawdown) AS mkt_max_drawdown_max
            FROM pos_metric m WHERE m.reason IS NULL GROUP BY m.base_date ORDER BY m.base_date""",
            List.of(col("base_date", ColumnType.DATE), col("mkt_max_drawdown", ColumnType.DECIMAL),
                    col("mkt_max_drawdown_max", ColumnType.DECIMAL)));

    /** What a comparison reached; printed by the golden test and asserted by the synthetic test. */
    record Coverage(int judged, int unjudged, Map<LocalDate, Integer> judgedByDate, Map<PositionMeter.Reason, Integer> reasons,
            Map<Position, Integer> positions, boolean topTie, boolean bottomTie) {
    }

    private PositionParity() {
    }

    static String key(Object market, Object ticker) {
        return SyntheticHistory.key((String) market, (String) ticker);
    }

    /** The calculator inputs rebuilt from the raw table rows. */
    record Inputs(PositionHistory history, Map<String, AssetSeries> assets) {
    }

    static Inputs inputs(RowSet assets, RowSet blocks, RowSet returns, RowSet fx) {
        Map<String, Currency> currencies = new LinkedHashMap<>();
        Map<String, TreeMap<YearMonth, BigDecimal>> byAsset = new LinkedHashMap<>();
        for (int i = 0; i < assets.size(); i++) {
            String key = key(assets.get(i, "market"), assets.get(i, "ticker"));
            currencies.put(key, Currency.valueOf((String) assets.get(i, "currency")));
            byAsset.put(key, new TreeMap<>());
        }
        for (int i = 0; i < returns.size(); i++) {
            byAsset.get(key(returns.get(i, "market"), returns.get(i, "ticker")))
                    .put(YearMonth.from((LocalDate) returns.get(i, "month_start")), (BigDecimal) returns.get(i, "local_return"));
        }
        Map<String, AssetSeries> series = new LinkedHashMap<>();
        byAsset.forEach((key, map) -> series.put(key, new AssetSeries(currencies.get(key), map)));

        TreeSet<YearMonth> monthBlocks = new TreeSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            monthBlocks.add(YearMonth.from((LocalDate) blocks.get(i, "month_start")));
        }
        Map<Currency, Map<YearMonth, BigDecimal>> fxChange = new HashMap<>();
        for (int i = 0; i < fx.size(); i++) {
            fxChange.computeIfAbsent(Currency.valueOf((String) fx.get(i, "currency")), c -> new HashMap<>())
                    .put(YearMonth.from((LocalDate) fx.get(i, "month_start")), (BigDecimal) fx.get(i, "fx_change"));
        }
        PositionHistory history = new PositionHistory(monthBlocks, fxChange,
                series.get(key(ReportRules.BENCH_KR_MARKET, ReportRules.BENCH_KR_TICKER)),
                series.get(key(ReportRules.BENCH_US_MARKET, ReportRules.BENCH_US_TICKER)));
        return new Inputs(history, series);
    }

    /** Judges every base date of {@code stored} and checks each stored row and each reason. */
    static Coverage compare(Inputs inputs, RowSet stored, RowSet reasons, RowSet marketMdd) {
        Map<LocalDate, BigDecimal> sqlMarketMdd = new TreeMap<>();
        for (int i = 0; i < marketMdd.size(); i++) {
            assertThat(((BigDecimal) marketMdd.get(i, "mkt_max_drawdown")).compareTo((BigDecimal) marketMdd.get(i, "mkt_max_drawdown_max")))
                    .as("one market drawdown per base date").isZero();
            sqlMarketMdd.put((LocalDate) marketMdd.get(i, "base_date"), (BigDecimal) marketMdd.get(i, "mkt_max_drawdown"));
        }
        Map<LocalDate, Map<String, Integer>> poolRows = new TreeMap<>();
        for (int i = 0; i < stored.size(); i++) {
            poolRows.computeIfAbsent((LocalDate) stored.get(i, "base_date"), d -> new LinkedHashMap<>())
                    .put(key(stored.get(i, "market"), stored.get(i, "ticker")), i);
        }
        Map<String, String> reasonByRow = new HashMap<>();
        for (int i = 0; i < reasons.size(); i++) {
            reasonByRow.put(key(reasons.get(i, "market"), reasons.get(i, "ticker")) + "@" + reasons.get(i, "base_date"),
                    (String) reasons.get(i, "reason"));
        }
        assertThat(reasons.size()).as("pos_judge rows equal stored rows").isEqualTo(stored.size());

        List<String> mismatches = new ArrayList<>();
        int judged = 0;
        int unjudged = 0;
        Map<LocalDate, Integer> judgedByDate = new TreeMap<>();
        Map<PositionMeter.Reason, Integer> reasonCounts = new EnumMap<>(PositionMeter.Reason.class);
        Map<Position, Integer> positions = new EnumMap<>(Position.class);
        boolean topTie = false;
        boolean bottomTie = false;
        int marketChecked = 0;
        for (Map.Entry<LocalDate, Map<String, Integer>> day : poolRows.entrySet()) {
            Map<String, AssetSeries> pool = new LinkedHashMap<>();
            day.getValue().keySet().forEach(k -> pool.put(k, inputs.assets().get(k)));
            BaseDatePositionCalculator.Detailed<String> detailed = BaseDatePositionCalculator.judgeDetailed(day.getKey(), pool,
                    inputs.history());
            Map<String, BaseDateJudgement> got = detailed.rows();
            BigDecimal sqlMdd = sqlMarketMdd.get(day.getKey());
            if (sqlMdd != null) {
                marketChecked++;
                if (detailed.marketMaxDrawdown() == null || detailed.marketMaxDrawdown().compareTo(sqlMdd) != 0) {
                    mismatches.add("market drawdown differs: " + day.getKey());
                }
            }
            List<BigDecimal> vols = new ArrayList<>();
            for (Map.Entry<String, Integer> row : day.getValue().entrySet()) {
                String label = row.getKey() + "@" + day.getKey();
                BaseDateJudgement j = got.get(row.getKey());
                int i = row.getValue();
                String sqlReason = reasonByRow.get(label);
                if (!j.judgeable()) {
                    unjudged++;
                    reasonCounts.merge(j.reason(), 1, Integer::sum);
                    boolean allNull = stored.get(i, "window_start") == null && stored.get(i, "window_end") == null
                            && stored.get(i, "months_used") == null && stored.get(i, "up_participation") == null
                            && stored.get(i, "down_participation") == null && stored.get(i, "volatility") == null
                            && stored.get(i, "max_drawdown") == null && stored.get(i, "system_position") == null;
                    if (!allNull || !j.reason().name().equals(sqlReason)) {
                        mismatches.add("unjudged row differs: " + label);
                    }
                    continue;
                }
                judged++;
                judgedByDate.merge(day.getKey(), 1, Integer::sum);
                positions.merge(j.systemPosition(), 1, Integer::sum);
                vols.add(j.volatility());
                boolean same = sqlReason == null
                        && day.getKey().equals(stored.get(i, "base_date"))
                        && j.windowStart().atDay(1).equals(stored.get(i, "window_start"))
                        && j.windowEnd().atDay(1).equals(stored.get(i, "window_end"))
                        && stored.get(i, "months_used") != null && ((Long) stored.get(i, "months_used")).intValue() == j.monthsUsed()
                        && same(stored.get(i, "up_participation"), j.upParticipation())
                        && same(stored.get(i, "down_participation"), j.downParticipation())
                        && same(stored.get(i, "volatility"), j.volatility())
                        && same(stored.get(i, "max_drawdown"), j.maxDrawdown())
                        && j.systemPosition().name().equals(stored.get(i, "system_position"));
                if (!same) {
                    mismatches.add("judged row differs: " + label);
                }
            }
            topTie |= tiesAtBoundary(vols, true);
            bottomTie |= tiesAtBoundary(vols, false);
        }
        assertThat(mismatches).as("rows that differ between SQL and Java (count %d)", mismatches.size()).isEmpty();
        assertThat(marketChecked).as("base dates whose market drawdown was compared").isEqualTo(judgedByDate.size());
        return new Coverage(judged, unjudged, judgedByDate, reasonCounts, positions, topTie, bottomTie);
    }

    private static boolean same(Object stored, BigDecimal calculated) {
        return stored != null && calculated != null && ((BigDecimal) stored).compareTo(calculated) == 0;
    }

    /** True when more than k judged assets sit within the top (or bottom) k by volatility because of a tie at the k-th place. */
    private static boolean tiesAtBoundary(List<BigDecimal> vols, boolean top) {
        if (vols.isEmpty()) {
            return false;
        }
        int k = MoneyMath.ceilCount(ReportRules.TOP_RATIO, vols.size());
        List<BigDecimal> sorted = new ArrayList<>(vols);
        sorted.sort(top ? java.util.Comparator.reverseOrder() : java.util.Comparator.naturalOrder());
        BigDecimal edge = sorted.get(k - 1);
        long inside = sorted.stream().filter(v -> top ? v.compareTo(edge) >= 0 : v.compareTo(edge) <= 0).count();
        return inside > k;
    }
}

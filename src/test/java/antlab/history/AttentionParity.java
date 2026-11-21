package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FullDataDb;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SyntheticHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared by the synthetic parity test and the sample golden test: reads the returns and the stored {@code asset_month_attention} rows,
 * runs {@link AttentionCalculator} on the returns and compares every stored row. Only counts and keys reach messages, never values.
 */
final class AttentionParity {

    static final RowQuery RETURNS = new RowQuery("returns", """
            SELECT a.market AS market, a.ticker AS ticker, b.month_start AS month_start, r.local_return AS local_return
            FROM asset_month_return r
            JOIN asset a ON a.id = r.asset_id
            JOIN month_block b ON b.id = r.month_block_id
            ORDER BY a.market, a.ticker, b.month_start""",
            List.of(new RowColumn("market", ColumnType.TEXT), new RowColumn("ticker", ColumnType.TEXT),
                    new RowColumn("month_start", ColumnType.DATE), new RowColumn("local_return", ColumnType.DECIMAL)));

    static final RowQuery STORED = new RowQuery("stored", """
            SELECT a.market AS market, a.ticker AS ticker, b.month_start AS month_start, t.window_months AS window_months,
                   t.attention_z AS attention_z, t.streak AS streak, t.attention_index AS attention_index
            FROM asset_month_attention t
            JOIN asset a ON a.id = t.asset_id
            JOIN month_block b ON b.id = t.month_block_id
            ORDER BY a.market, a.ticker, b.month_start""",
            List.of(new RowColumn("market", ColumnType.TEXT), new RowColumn("ticker", ColumnType.TEXT),
                    new RowColumn("month_start", ColumnType.DATE), new RowColumn("window_months", ColumnType.LONG),
                    new RowColumn("attention_z", ColumnType.DECIMAL), new RowColumn("streak", ColumnType.LONG),
                    new RowColumn("attention_index", ColumnType.DECIMAL)));

    /** Counts of the cases a run reached; printed by the golden test and asserted by the synthetic test. */
    record Coverage(int rows, int nullByWindow, int nullByFloor, int clippedPlus, int clippedMinus, int segmentStarts,
            int longestStreak, boolean window11, boolean window12) {
    }

    private AttentionParity() {
    }

    /** The full-data tier: every stored row of the database must equal the calculator fed from its raw return rows. */
    static Coverage compareDatabase(FullDataDb db) throws Exception {
        RowSet returns = db.read(RETURNS);
        Coverage coverage = compare(toReturns(returns), db.read(STORED));
        assertThat(coverage.rows()).as("compared rows").isEqualTo(returns.size());
        return coverage;
    }

    /** Groups the rows of {@link #RETURNS} by {@code MARKET/TICKER}, ascending by month. */
    static Map<String, List<MonthReturn>> toReturns(RowSet rows) {
        Map<String, List<MonthReturn>> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String key = SyntheticHistory.key((String) rows.get(i, "market"), (String) rows.get(i, "ticker"));
            out.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new MonthReturn(YearMonth.from((LocalDate) rows.get(i, "month_start")), (BigDecimal) rows.get(i, "local_return")));
        }
        return out;
    }

    /** Keys of the assets whose returns differ (months or exact values with scale) between two groupings; never the values. */
    static List<String> differingAssets(Map<String, List<MonthReturn>> expected, Map<String, List<MonthReturn>> actual) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, List<MonthReturn>> entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                keys.add(entry.getKey());
            }
        }
        for (String key : actual.keySet()) {
            if (!expected.containsKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Calculates every asset and checks that the stored rows are exactly the calculated ones (same keys, same four columns). */
    static Coverage compare(Map<String, List<MonthReturn>> input, RowSet stored) {
        Map<String, AttentionValue> expected = new HashMap<>();
        int rows = 0;
        int nullByWindow = 0;
        int nullByFloor = 0;
        int plus = 0;
        int minus = 0;
        int starts = 0;
        int longest = 0;
        boolean w11 = false;
        boolean w12 = false;
        for (Map.Entry<String, List<MonthReturn>> asset : input.entrySet()) {
            for (AttentionValue v : AttentionCalculator.series(asset.getValue())) {
                expected.put(asset.getKey() + "@" + v.month(), v);
                rows++;
                if (v.windowMonths() < AttentionRules.WINDOW_MONTHS) {
                    nullByWindow++;
                } else if (v.attentionZ() == null) {
                    nullByFloor++;
                }
                if (v.attentionZ() != null && v.attentionZ().abs().compareTo(AttentionRules.Z_CAP) == 0) {
                    if (v.attentionZ().signum() > 0) {
                        plus++;
                    } else {
                        minus++;
                    }
                }
                if (v.windowMonths() == 0) {
                    starts++;
                }
                w11 |= v.windowMonths() == AttentionRules.WINDOW_MONTHS - 1;
                w12 |= v.windowMonths() == AttentionRules.WINDOW_MONTHS;
                longest = Math.max(longest, Math.abs(v.streak()));
            }
        }
        assertThat(stored.size()).as("stored row count equals return row count").isEqualTo(rows);
        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            String key = SyntheticHistory.key((String) stored.get(i, "market"), (String) stored.get(i, "ticker")) + "@"
                    + YearMonth.from((LocalDate) stored.get(i, "month_start"));
            AttentionValue want = expected.remove(key);
            if (want == null) {
                mismatches.add("no calculated row for " + key);
                continue;
            }
            boolean same = (Long) stored.get(i, "window_months") == want.windowMonths()
                    && (Long) stored.get(i, "streak") == want.streak()
                    && sameValue((BigDecimal) stored.get(i, "attention_z"), want.attentionZ())
                    && sameValue((BigDecimal) stored.get(i, "attention_index"), want.attentionIndex());
            if (!same) {
                mismatches.add("different row " + key);
            }
        }
        assertThat(mismatches).as("rows that differ between SQL and Java (count %d)", mismatches.size()).isEmpty();
        assertThat(expected.keySet()).as("calculated rows with no stored row (keys only)").isEmpty();
        return new Coverage(rows, nullByWindow, nullByFloor, plus, minus, starts, longest, w11, w12);
    }

    private static boolean sameValue(BigDecimal stored, BigDecimal calculated) {
        return stored == null ? calculated == null : calculated != null && stored.compareTo(calculated) == 0;
    }
}

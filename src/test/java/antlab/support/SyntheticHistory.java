package antlab.support;

import antlab.history.MonthReturn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Synthetic, not market data. A deterministic invented history (assets, month blocks with a gap, returns, USD changes) that exists in
 * two forms made from one source: Java inputs for the calculators and an SQL script that loads the same rows into a database that has the
 * {@code db/01} schema. Parity tests run the real {@code db/13} script on the loaded rows and compare it with the Java result,
 * so no private sample is needed. Values are whole units of 1e-8 (no binary floating point) from a fixed-seed generator and some hand-built
 * series for the edge cases the parity tests must reach.
 */
public final class SyntheticHistory {

    /** Scale of every stored return and USD change (NUMERIC(12,8)). */
    private static final int SCALE = 8;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private record Asset(String market, String ticker, String assetType) {
        String currency() {
            return market.equals("KR") ? "KRW" : "USD";
        }
    }

    /** Fixed-seed SplitMix64: the same sequence on every machine. */
    private static final class SplitMix64 {
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

        /** Uniform in [-bound, bound]. */
        long signed(long bound) {
            return Math.floorMod(next(), 2 * bound + 1) - bound;
        }

        boolean coin() {
            return (next() & 1L) == 0;
        }
    }

    private final List<YearMonth> blocks;
    private final Map<Asset, TreeMap<YearMonth, BigDecimal>> returns = new LinkedHashMap<>();
    private final TreeMap<YearMonth, BigDecimal> usdChange = new TreeMap<>();

    private SyntheticHistory(List<YearMonth> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    /**
     * Months 2000-01 to 2003-12 and 2004-03 to 2005-04 (no 2004-01 and 2004-02, so every asset has a segment break), and a set of assets
     * that reach the attention edge cases: a constant window, both clip directions, zero returns, long streaks in both directions,
     * 11- and 12-month windows, a standard deviation exactly at and just under the floor, and gaps that only one asset has.
     */
    public static SyntheticHistory standard() {
        List<YearMonth> blocks = new ArrayList<>(months(YearMonth.of(2000, 1), YearMonth.of(2003, 12)));
        blocks.addAll(months(YearMonth.of(2004, 3), YearMonth.of(2005, 4)));
        SyntheticHistory h = new SyntheticHistory(blocks);
        SplitMix64 rng = new SplitMix64(20261116L);

        // Proxy assets used by the position calculator, filled over every block.
        h.add(new Asset("KR", "069500", "ETF"), h.blocks, List.of(), rng);
        h.add(new Asset("US", "SPY", "ETF"), h.blocks, List.of(), rng);

        // Constant magnitude for 20 months: a window with zero standard deviation and a positive streak that passes the cap.
        List<Long> constant = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            constant.add(1_000_000L);
        }
        h.add(new Asset("KR", "000001", "STOCK"), h.blocks, constant, rng);

        // Large steady window, then a zero return (clipped at -3), a quiet window, then a spike (clipped at +3).
        List<Long> clips = new ArrayList<>(around(rng, 10_000_000L, 2_000_000L, 12));
        clips.add(0L);
        clips.addAll(around(rng, 200_000L, 80_000L, 12));
        clips.add(45_000_000L);
        h.add(new Asset("KR", "000002", "STOCK"), h.blocks, clips, rng);

        // Zero returns, then a run of 8 gains and a run of 7 losses.
        List<Long> zeros = new ArrayList<>(around(rng, 3_000_000L, 2_000_000L, 5));
        zeros.add(0L);
        zeros.add(0L);
        zeros.addAll(around(rng, 3_000_000L, 2_000_000L, 3));
        for (int i = 0; i < 8; i++) {
            zeros.add(Math.abs(rng.signed(5_000_000L)) + 1);
        }
        for (int i = 0; i < 7; i++) {
            zeros.add(-(Math.abs(rng.signed(5_000_000L)) + 1));
        }
        h.add(new Asset("KR", "000003", "STOCK"), h.blocks, zeros, rng);

        // Standard deviation of the first 12 magnitudes is exactly 0.0001 (at the floor, so z is computed) ...
        h.add(new Asset("KR", "000005", "STOCK"), h.blocks, floorEdge(70_000L), rng);
        // ... and the same series with one value lowered by 1e-8, so the deviation is just under the floor (z is NULL).
        h.add(new Asset("KR", "000006", "STOCK"), h.blocks, floorEdge(69_999L), rng);

        // An asset missing two months in the middle: its segments restart there although the month blocks exist.
        List<YearMonth> withGap = new ArrayList<>(h.blocks);
        withGap.remove(YearMonth.of(2001, 7));
        withGap.remove(YearMonth.of(2001, 8));
        h.add(new Asset("US", "GAP", "STOCK"), withGap, List.of(), rng);

        // A 12-month segment (windows 0 to 11 only), a gap, then a short segment.
        List<YearMonth> shortRun = new ArrayList<>(months(YearMonth.of(2002, 1), YearMonth.of(2002, 12)));
        shortRun.addAll(months(YearMonth.of(2003, 6), YearMonth.of(2003, 12)));
        h.add(new Asset("US", "SHORT", "STOCK"), shortRun, List.of(), rng);

        // Exactly 13 months: one z.
        h.add(new Asset("US", "EXACT13", "STOCK"), months(YearMonth.of(2000, 1), YearMonth.of(2001, 1)), List.of(), rng);

        // Tiny magnitudes: the deviation is under the floor in every window.
        List<Long> tiny = new ArrayList<>();
        for (int i = 0; i < h.blocks.size(); i++) {
            tiny.add(rng.signed(1_500L));
        }
        h.add(new Asset("KR", "000004", "STOCK"), h.blocks, tiny, rng);

        // Plain random assets.
        h.add(new Asset("KR", "000007", "STOCK"), h.blocks, List.of(), rng);
        h.add(new Asset("US", "AAA", "STOCK"), h.blocks, List.of(), rng);
        h.add(new Asset("US", "BBB", "STOCK"), h.blocks, List.of(), rng);

        for (YearMonth month : h.blocks) {
            h.usdChange.put(month, BigDecimal.valueOf(rng.signed(5_000_000L), SCALE));
        }
        return h;
    }

    private static List<YearMonth> months(YearMonth from, YearMonth to) {
        List<YearMonth> out = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            out.add(m);
        }
        return out;
    }

    /** {@code count} signed values near {@code center} (within {@code spread}) in units of 1e-8, random sign. */
    private static List<Long> around(SplitMix64 rng, long center, long spread, int count) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long magnitude = center + rng.signed(spread);
            out.add(rng.coin() ? magnitude : -magnitude);
        }
        return out;
    }

    /**
     * Twelve magnitudes in units of 1e-8: seven of 60000, three of 40000, one {@code top} and one of 50000. With {@code top} = 70000 the sample
     * standard deviation is exactly 0.0001. Then a plain return so a 13th month exists. Signs alternate by position.
     */
    private static List<Long> floorEdge(long top) {
        long[] magnitudes = {60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 40_000L, 40_000L, 40_000L, top,
                50_000L};
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < magnitudes.length; i++) {
            out.add(i % 2 == 0 ? magnitudes[i] : -magnitudes[i]);
        }
        out.add(75_000L);
        return out;
    }

    private void add(Asset asset, List<YearMonth> months, List<Long> prefix, SplitMix64 rng) {
        TreeMap<YearMonth, BigDecimal> series = new TreeMap<>();
        for (int i = 0; i < months.size(); i++) {
            long units = i < prefix.size() ? prefix.get(i) : rng.signed(12_000_000L);
            series.put(months.get(i), BigDecimal.valueOf(units, SCALE));
        }
        returns.put(asset, series);
    }

    public static String key(String market, String ticker) {
        return market + "/" + ticker;
    }

    /** Each asset's returns ascending by month, as the calculators take them. Keys are {@code MARKET/TICKER}. */
    public Map<String, List<MonthReturn>> returns() {
        Map<String, List<MonthReturn>> out = new LinkedHashMap<>();
        returns.forEach((asset, series) -> {
            List<MonthReturn> rows = new ArrayList<>();
            series.forEach((month, value) -> rows.add(new MonthReturn(month, value)));
            out.put(key(asset.market(), asset.ticker()), List.copyOf(rows));
        });
        return out;
    }

    public int returnRowCount() {
        return returns.values().stream().mapToInt(Map::size).sum();
    }

    public List<YearMonth> monthBlocks() {
        return blocks;
    }

    /** USD change for each month block (NUMERIC(12,8) scale). */
    public Map<YearMonth, BigDecimal> usdChanges() {
        return Map.copyOf(usdChange);
    }

    /** An SQL script for a database that has the {@code db/01} schema: groups, assets, month blocks, returns and USD changes by natural key. */
    public byte[] insertScript() {
        StringBuilder sql = new StringBuilder("-- synthetic, not market data\n");
        sql.append("INSERT INTO asset_group (code, name, kind) VALUES ('SYN_GROUP', 'synthetic group', 'SECTOR');\n");
        sql.append("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)\n")
                .append("SELECT v.market, v.ticker, 'synthetic ' || v.ticker, v.asset_type, v.currency, g.id, DATE '1990-01-01', 'PRICE'\n")
                .append("FROM (VALUES ");
        List<String> assetRows = new ArrayList<>();
        for (Asset a : returns.keySet()) {
            assetRows.add("('" + a.market() + "', '" + a.ticker() + "', '" + a.assetType() + "', '" + a.currency() + "')");
        }
        sql.append(String.join(", ", assetRows)).append(") AS v(market, ticker, asset_type, currency)\n")
                .append("CROSS JOIN asset_group g WHERE g.code = 'SYN_GROUP';\n");
        List<String> blockRows = new ArrayList<>();
        for (YearMonth month : blocks) {
            blockRows.add("(" + date(month) + ")");
        }
        sql.append("INSERT INTO month_block (month_start) VALUES ").append(String.join(", ", blockRows)).append(";\n");
        List<String> returnRows = new ArrayList<>();
        returns.forEach((asset, series) -> series.forEach((month, value) -> returnRows.add("('" + asset.market() + "', '"
                + asset.ticker() + "', " + date(month) + ", " + value.toPlainString() + ")")));
        sql.append("INSERT INTO asset_month_return (asset_id, month_block_id, local_return)\n")
                .append("SELECT a.id, b.id, v.r FROM (VALUES ").append(String.join(", ", returnRows))
                .append(") AS v(market, ticker, ms, r)\n")
                .append("JOIN asset a ON a.market = v.market AND a.ticker = v.ticker\n")
                .append("JOIN month_block b ON b.month_start = v.ms;\n");
        List<String> fxRows = new ArrayList<>();
        usdChange.forEach((month, value) -> fxRows.add("(" + date(month) + ", " + value.toPlainString() + ")"));
        sql.append("INSERT INTO fx_month (month_block_id, currency, fx_change)\n")
                .append("SELECT b.id, 'USD', v.c FROM (VALUES ").append(String.join(", ", fxRows))
                .append(") AS v(ms, c) JOIN month_block b ON b.month_start = v.ms;\n");
        return sql.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String date(YearMonth month) {
        return "DATE '" + month.atDay(1).format(DATE_FORMAT) + "'";
    }
}

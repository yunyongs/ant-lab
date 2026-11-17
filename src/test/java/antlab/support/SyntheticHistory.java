package antlab.support;

import antlab.history.MonthReturn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
    private final Map<LocalDate, List<String>> pools = new LinkedHashMap<>();

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

    /**
     * A history for base-date positions: months 2010-01 to 2011-05 and 2011-07 to 2013-03 (no 2011-06 block, so the market series has a
     * hole), two base dates whose windows are 2010-01 to 2012-12 and 2010-03 to 2013-02, and a pool that covers every outcome: judged assets
     * of every beta (some with a gap), two assets with an identical series (a volatility tie at the top-20 % boundary), and assets that stop
     * on MONTHS, N_UP and N_DOWN. The market is controlled: every sixth month rises, every sixth falls, the rest are neutral, plus one month
     * at exactly +0.01 and one at exactly -0.01 (the band edges). USD assets and the US proxy go through the FX changes.
     */
    public static SyntheticHistory positions() {
        List<YearMonth> blocks = new ArrayList<>(months(YearMonth.of(2010, 1), YearMonth.of(2011, 5)));
        blocks.addAll(months(YearMonth.of(2011, 7), YearMonth.of(2013, 3)));
        SyntheticHistory h = new SyntheticHistory(blocks);
        SplitMix64 rng = new SplitMix64(20261117L);

        List<Long> kr = new ArrayList<>();
        List<Long> us = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            long fx = rng.signed(800_000L);
            long u = rng.signed(800_000L);
            long k;
            if (i == 13) {
                k = 2_000_000L;
                fx = 0;
                u = 0;
            } else if (i == 22) {
                k = -2_000_000L;
                fx = 0;
                u = 0;
            } else if (i == 3) {
                // An early deep US drop, so the market drawdown is set by a month that the late-window mutations drop.
                k = rng.signed(200_000L);
                u = -20_000_000L;
            } else if (i % 6 == 1) {
                k = 7_000_000L + Math.abs(rng.signed(2_000_000L));
            } else if (i % 6 == 4) {
                k = -7_000_000L - Math.abs(rng.signed(2_000_000L));
            } else {
                k = rng.signed(200_000L);
            }
            kr.add(k);
            us.add(u);
            h.usdChange.put(blocks.get(i), BigDecimal.valueOf(fx, SCALE));
        }
        h.add(new Asset("KR", "069500", "ETF"), blocks, kr, rng);
        h.add(new Asset("US", "SPY", "ETF"), blocks, us, rng);

        long[] tenths = {2, 5, 7, 10, 14, 30, 25};
        for (int i = 0; i < tenths.length; i++) {
            h.add(new Asset("KR", "10000" + (i + 1), "STOCK"), blocks, scaled(kr, tenths[i], rng), rng);
        }
        List<Long> twin = scaled(kr, 20, rng);
        h.add(new Asset("KR", "100008", "STOCK"), blocks, twin, rng);
        h.add(new Asset("KR", "100009", "STOCK"), blocks, twin, rng);
        h.add(new Asset("US", "200001", "STOCK"), blocks, List.of(), rng);
        h.add(new Asset("US", "200002", "STOCK"), blocks, List.of(), rng);

        List<YearMonth> withHoles = new ArrayList<>(blocks);
        withHoles.remove(5);
        withHoles.remove(5);
        withHoles.remove(7);
        h.add(new Asset("US", "GAPX", "STOCK"), withHoles, List.of(), rng);

        List<YearMonth> noUp = new ArrayList<>();
        List<YearMonth> noDown = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (i % 6 != 1) {
                noUp.add(blocks.get(i));
            }
            if (i % 6 != 4) {
                noDown.add(blocks.get(i));
            }
        }
        h.add(new Asset("KR", "300001", "STOCK"), noUp, List.of(), rng);
        h.add(new Asset("KR", "300002", "STOCK"), noDown, List.of(), rng);
        h.add(new Asset("KR", "SHORT01", "STOCK"), months(YearMonth.of(2012, 1), YearMonth.of(2012, 12)), List.of(), rng);

        // Pool-size probe: low volatility rank (just above the three lowest) and almost no down-month participation. It is a goalkeeper
        // only when the pool rank limit grows from CEIL(0.2 * judged) to CEIL(0.2 * all rows), so a rule that counts unjudged rows changes it.
        // Every month is explicit (integer arithmetic, no random draw), so the series of the other assets do not move.
        List<Long> probe = new ArrayList<>();
        for (long k : kr) {
            probe.add(k > 0 ? k * 12 / 10 : k / 20);
        }
        h.add(new Asset("KR", "100010", "STOCK"), blocks, probe, rng);

        List<String> all = new ArrayList<>();
        for (Asset a : h.returns.keySet()) {
            all.add(key(a.market(), a.ticker()));
        }
        h.pools.put(LocalDate.of(2013, 1, 15), List.copyOf(all));
        List<String> second = new ArrayList<>(all);
        second.remove("US/200002");
        h.pools.put(LocalDate.of(2013, 3, 1), List.copyOf(second));

        // A third base date with its own pool of ten (k = 2): a noise-only asset has the lowest volatility and an identical pair at 0.1 times
        // the market shares the second place, so three assets sit inside the bottom 20 % (a tie at the lower boundary) and the pair, whose
        // down participation is at most 0.2, are goalkeepers. Added last, so no earlier series or rank moves.
        h.add(new Asset("KR", "100013", "STOCK"), blocks, scaled(kr, 0, rng), rng);
        List<Long> lowTwin = scaled(kr, 1, rng);
        h.add(new Asset("KR", "100011", "STOCK"), blocks, lowTwin, rng);
        h.add(new Asset("KR", "100012", "STOCK"), blocks, lowTwin, rng);
        h.pools.put(LocalDate.of(2013, 2, 1), List.of("KR/100013", "KR/100011", "KR/100012", "KR/100003", "KR/100004", "KR/100005",
                "KR/100006", "KR/100007", "KR/100008", "KR/100009"));
        return h;
    }

    /** {@code tenths} / 10 times the base series plus a small deterministic noise, in units of 1e-8 (integer arithmetic only). */
    private static List<Long> scaled(List<Long> base, long tenths, SplitMix64 rng) {
        List<Long> out = new ArrayList<>();
        for (long value : base) {
            out.add(value * tenths / 10 + rng.signed(20_000L));
        }
        return out;
    }

    /** Removes the USD change of one month block (the fixture of a missing in-window FX row). */
    public SyntheticHistory withoutUsdChange(YearMonth month) {
        if (usdChange.remove(month) == null) {
            throw new IllegalArgumentException("no USD change for that month");
        }
        return this;
    }

    /** Base dates and, for each, the keys ({@code MARKET/TICKER}) of the pool that has an {@code asset_position} row on that date. */
    public Map<LocalDate, List<String>> pools() {
        return Map.copyOf(pools);
    }

    /** An SQL script that adds the {@code asset_position} rows (judgement columns left NULL) after {@link #insertScript()}. */
    public byte[] positionScript() {
        List<String> rows = new ArrayList<>();
        pools.forEach((baseDate, keys) -> keys.forEach(k -> rows.add("('" + k.substring(0, 2) + "', '" + k.substring(3) + "', DATE '"
                + baseDate.format(DATE_FORMAT) + "')")));
        String sql = "-- synthetic, not market data\n"
                + "INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)\n"
                + "SELECT a.id, v.base_date, 100.0000, 1.0000 FROM (VALUES " + String.join(", ", rows)
                + ") AS v(market, ticker, base_date)\n"
                + "JOIN asset a ON a.market = v.market AND a.ticker = v.ticker;\n";
        return sql.getBytes(StandardCharsets.UTF_8);
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

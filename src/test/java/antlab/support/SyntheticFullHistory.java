package antlab.support;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic, not market data. The six db/30 input files and lineage.csv for the real pool of db/full/asset.csv (every asset) over
 * all 360 months 1996-01..2025-12, made from a fixed-seed generator. Assets that carry a proxy name get PROXY_INDEX months before their
 * first real month (stocks until 2006-12, ETFs through the listing month), like the real build; every other cell is ACTUAL.
 * Needs only the JDK so that it can also be run alone: {@code java SyntheticFullHistory.java <repo root> <output dir>}.
 */
public final class SyntheticFullHistory {

    public static final int MONTHS = 360;
    private static final LocalDate FIRST = LocalDate.of(1996, 1, 1);
    private static final LocalDate STOCK_ACTUAL_FROM = LocalDate.of(2007, 1, 1);
    public static final String BASE_DATE = "2026-01-02";

    private record Asset(String market, String ticker, String type, String listedOn, String proxyName) {
        boolean usd() {
            return market.equals("US");
        }
    }

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
    }

    private SyntheticFullHistory() {
    }

    /** File name to content: the pool files copied as they are and the generated build outputs. */
    public static Map<String, String> files(Path repoRoot) throws IOException {
        Path full = repoRoot.resolve("db/full");
        Map<String, String> out = new LinkedHashMap<>();
        for (String f : List.of("asset_group.csv", "asset.csv", "proxy_series.csv")) {
            out.put(f, Files.readString(full.resolve(f), StandardCharsets.UTF_8));
        }
        List<Asset> assets = readAssets(out.get("asset.csv"));

        List<LocalDate> months = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            months.add(FIRST.plusMonths(i));
        }
        SplitMix64 rng = new SplitMix64(20261121L);

        long[] mktKr = new long[MONTHS];
        long[] mktUs = new long[MONTHS];
        long[] fx = new long[MONTHS];
        for (int t = 0; t < MONTHS; t++) {
            mktKr[t] = marketMove(rng);
            mktUs[t] = marketMove(rng);
            fx[t] = rng.signed(1_500_000L);
        }

        StringBuilder mb = new StringBuilder("month_start\n");
        StringBuilder fxs = new StringBuilder("month_start,currency,fx_change\n");
        for (int t = 0; t < MONTHS; t++) {
            mb.append(months.get(t)).append('\n');
            fxs.append(months.get(t)).append(",USD,").append(num(fx[t])).append('\n');
        }
        out.put("month_block.csv", mb.toString());
        out.put("fx_month.csv", fxs.toString());

        StringBuilder pos = new StringBuilder("market,ticker,base_date,base_price,base_fx_rate,market_cap_rank\n");
        StringBuilder ret = new StringBuilder("market,ticker,month_start,local_return,return_kind\n");
        StringBuilder lin = new StringBuilder("market,ticker,month_start,return_kind,series_id\n");
        int krRank = 0;
        int usRank = 0;
        int n = 0;
        for (Asset a : assets) {
            n++;
            String rank = "";
            if (a.type().equals("STOCK")) {
                rank = String.valueOf(a.usd() ? ++usRank : ++krRank);
            }
            pos.append(a.market()).append(',').append(a.ticker()).append(',').append(BASE_DATE).append(',')
                    .append(10 + n).append(".0000,").append(a.usd() ? "1400.0000" : "1.0000").append(',').append(rank).append('\n');

            long beta = a.ticker().equals("069500") || a.ticker().equals("SPY") ? 10 : 2 + Math.floorMod(rng.next(), 17);
            long[] mkt = a.usd() ? mktUs : mktKr;
            boolean isMarket = a.ticker().equals("069500") || a.ticker().equals("SPY");
            LocalDate proxyThrough = proxyThrough(a);
            for (int t = 0; t < MONTHS; t++) {
                long r = isMarket ? mkt[t] : mkt[t] * beta / 10 + rng.signed(1_500_000L);
                r = Math.max(r, -90_000_000L);
                boolean proxy = a.proxyName() != null && !a.proxyName().isEmpty() && !months.get(t).isAfter(proxyThrough);
                ret.append(a.market()).append(',').append(a.ticker()).append(',').append(months.get(t)).append(',')
                        .append(num(r)).append(',').append(proxy ? "PROXY_INDEX" : "ACTUAL").append('\n');
                lin.append(a.market()).append(',').append(a.ticker()).append(',').append(months.get(t)).append(',')
                        .append(proxy ? "PROXY_INDEX" : "ACTUAL").append(',')
                        .append(proxy ? (a.usd() ? "US_SPX_PRICE" : "KR_KOSPI") : "").append('\n');
            }
        }
        out.put("asset_position.csv", pos.toString());
        out.put("asset_month_return.csv", ret.toString());
        out.put("lineage.csv", lin.toString());
        return out;
    }

    private static long marketMove(SplitMix64 rng) {
        long magnitude = 2_000_000L + Math.floorMod(rng.next(), 5_000_000L);
        return (rng.next() & 1L) == 0 ? magnitude : -magnitude;
    }

    private static LocalDate proxyThrough(Asset a) {
        if (a.type().equals("ETF")) {
            return LocalDate.parse(a.listedOn()).withDayOfMonth(1);
        }
        return STOCK_ACTUAL_FROM.minusMonths(1);
    }

    private static String num(long units) {
        return BigDecimal.valueOf(units, 8).toPlainString();
    }

    private static List<Asset> readAssets(String csv) {
        List<Asset> list = new ArrayList<>();
        String[] lines = csv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] c = lines[i].split(",", -1);
            list.add(new Asset(c[0], c[1], c[3], c[6], c[8]));
        }
        return list;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        for (Map.Entry<String, String> e : files(Path.of(args[0])).entrySet()) {
            Files.writeString(out.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }
    }
}

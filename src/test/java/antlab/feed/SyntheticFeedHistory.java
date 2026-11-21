package antlab.feed;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Synthetic, not market data. Two seasons (a large positive and a negative seed, the same price path) over 12 invented assets in 3
 * groups and 14 turns, written as an SQL script for a database that has the {@code db/01} and {@code db/02} schemas. The cells are
 * chosen so the feed generator reaches its edges: ties in attention, a missing attention row and a null attention index, zero returns,
 * a group whose mean return is exactly 0 while members move, tiny moves whose whole percent is 0 (assets and a group), a run of rises
 * for echoes, an attention drop from the top five for fades, hidden and public events, an events-only turn and a zero-candidate turn.
 */
final class SyntheticFeedHistory {

    static final int TURNS = 14;
    static final List<Long> SEEDS = List.of(7531902468135790246L, -4096209173561902478L);

    private record Asset(String market, String ticker, String name, String group) {
    }

    private record Event(int block, String key, String category, String type, int strength, String reaction) {
    }

    private static final List<Asset> ASSETS = List.of(
            new Asset("KR", "F01", "푸른전자", "FEED_TECH"), new Asset("KR", "F02", "붉은반도체", "FEED_TECH"),
            new Asset("US", "F03", "하늘소프트", "FEED_TECH"), new Asset("US", "F04", "달빛네트웍스", "FEED_TECH"),
            new Asset("KR", "F05", "한강은행", "FEED_BANK"), new Asset("KR", "F06", "북악금융", "FEED_BANK"),
            new Asset("US", "F07", "남산증권", "FEED_BANK"), new Asset("US", "F08", "동해보험", "FEED_BANK"),
            new Asset("KR", "F09", "서해에너지", "FEED_ENERGY"), new Asset("KR", "F10", "남해가스", "FEED_ENERGY"),
            new Asset("US", "F11", "산바람전력", "FEED_ENERGY"), new Asset("US", "F12", "들불정유", "FEED_ENERGY"));

    private static final List<Event> EVENTS = List.of(
            // Month 0 lies before the replay start of the parity run: these two families must not lift PAIR_TYPE to n = 3.
            new Event(0, "pair02_b1", "POLITICS", "PAIR_TYPE", 1, "0.01000000"),
            new Event(0, "pair03_c1", "POLITICS", "PAIR_TYPE", 1, "0.01000000"),
            // Block 99 lies in the base-date year (2026), after the replay range: it must not lift PAIR_TYPE either.
            new Event(99, "pair04_d1", "POLITICS", "PAIR_TYPE", 1, "0.01000000"),
            new Event(99, "pair05_e1", "POLITICS", "PAIR_TYPE", 1, "0.01000000"),
            new Event(2, "aaaaaa_01", "TRADE", "PUB_TYPE", 2, "0.05000000"),
            new Event(4, "rare01_x1", "GEOPOLITICS", "RARE_TYPE", 3, "-0.08000000"),
            new Event(4, "pair01_a1", "POLITICS", "PAIR_TYPE", 1, "0.01000000"),
            new Event(5, "bbbbbb_01", "POLICY", "PUB_TYPE", 2, "0.00000000"),
            new Event(9, "cccccc_01", "HEALTH", "PUB_TYPE", 1, "-0.01240000"),
            new Event(10, "rare02_x2", "FINANCIAL", "RARE_TYPE", 2, "0.00499999"),
            new Event(12, "dddddd_01", "SUPPLY_SHOCK", "PUB_TYPE", 3, "0.12000000"),
            new Event(12, "eeeeee_01", "FINANCIAL", "PUB_TYPE", 2, "-0.00500000"),
            new Event(14, "ffffff_01", "POLITICS", "PUB_TYPE", 1, "0.00500000"));

    private final long[][] price = new long[ASSETS.size()][TURNS + 1];
    private final String[][] attention = new String[ASSETS.size()][TURNS + 1];

    static SyntheticFeedHistory standard() {
        SyntheticFeedHistory h = new SyntheticFeedHistory();
        h.fillPrices(new SplittableRandom(20261117L));
        h.fillAttention(new SplittableRandom(20261118L));
        return h;
    }

    int assetCount() {
        return ASSETS.size();
    }

    private static long roundTo100(long value) {
        return Math.max(1000, (value + 50) / 100 * 100);
    }

    /** Prices are whole numbers; engineered cells are exact so the stored return is the intended one. */
    private void fillPrices(SplittableRandom rnd) {
        for (int a = 0; a < ASSETS.size(); a++) {
            price[a][0] = 100_000L + 10_000L * a;
            for (int t = 1; t <= TURNS; t++) {
                long prev = price[a][t - 1];
                long next;
                if (t == 3 || t == 4) {
                    next = prev;
                } else if (t == 6 && a == 0) {
                    next = prev * 11 / 10;
                } else if (t == 6 && a == 1) {
                    next = prev * 9 / 10;
                } else if (t == 6 && a == 2 || t == 6 && a == 3) {
                    next = prev;
                } else if ((t == 8 && (a == 4 || a == 5)) || (t == 11 && (a == 8 || a == 9))) {
                    next = prev + 1;
                } else if (t >= 7 && a >= 9 && a <= 11) {
                    next = roundTo100(prev * (1000 + 30 + 10 * (a - 9)) / 1000);
                } else {
                    int pm = rnd.nextInt(161) - 80;
                    next = roundTo100(prev * (1000 + pm) / 1000);
                }
                price[a][t] = next;
            }
        }
    }

    private void fillAttention(SplittableRandom rnd) {
        for (int a = 0; a < ASSETS.size(); a++) {
            for (int t = 1; t <= TURNS; t++) {
                long units = 10_000_000L + rnd.nextInt(290_000_000);
                if (a >= 9 && a <= 11 && t >= 6) {
                    units = 100_000_000L + 5_000_000L * t + 1_000_000L * a;
                }
                attention[a][t] = plain(units);
            }
        }
        // Turns 3 and 4 repeat turn 2's attention, so a flat turn has no fade either (a zero-candidate turn and an events-only turn).
        for (int a = 0; a < ASSETS.size(); a++) {
            attention[a][3] = attention[a][2];
            attention[a][4] = attention[a][2];
        }
        // The energy group keeps its attention from turn 10 to 11 while its members move: a spread needs a strict rise.
        for (int a = 8; a <= 11; a++) {
            attention[a][11] = attention[a][10];
        }
        attention[0][5] = attention[1][5] = "2.50000000";
        attention[6][9] = attention[7][9] = "1.00000000";
        attention[4][8] = "3.10000000";
        attention[4][9] = "0.20000000";
        attention[11][1] = attention[11][2] = null;
        attention[11][5] = "";
    }

    private static String plain(long unitsOf1e8) {
        return String.format("%d.%08d", unitsOf1e8 / 100_000_000L, unitsOf1e8 % 100_000_000L);
    }

    byte[] script() {
        StringBuilder sql = new StringBuilder("-- synthetic, not market data\n");
        sql.append("INSERT INTO member (email, name, password) VALUES ('feed-parity@example.test', 'parity', 'not-a-secret');\n");
        sql.append("INSERT INTO asset_group (code, name, kind) VALUES ('FEED_TECH', '기술', 'SECTOR'), ('FEED_BANK', '금융', 'SECTOR'), "
                + "('FEED_ENERGY', '에너지', 'SECTOR');\n");
        List<String> assetRows = new ArrayList<>();
        for (Asset a : ASSETS) {
            assetRows.add("('" + a.market + "', '" + a.ticker + "', '" + a.name + "', '" + a.group + "')");
        }
        sql.append("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)\n")
                .append("SELECT v.m, v.t, v.n, 'STOCK', CASE v.m WHEN 'KR' THEN 'KRW' ELSE 'USD' END, g.id, DATE '1990-01-01', 'PRICE'\n")
                .append("FROM (VALUES ").append(String.join(", ", assetRows)).append(") AS v(m, t, n, gc) JOIN asset_group g ON g.code = v.gc;\n");
        List<String> blocks = new ArrayList<>();
        for (int t = 0; t <= TURNS; t++) {
            blocks.add("(DATE '" + monthStart(t) + "', TRUE)");
        }
        blocks.add("(DATE '" + monthStart(OUT_OF_RANGE_BLOCK) + "', TRUE)");
        sql.append("INSERT INTO month_block (month_start, events_reviewed) VALUES ").append(String.join(", ", blocks)).append(";\n");
        List<String> events = new ArrayList<>();
        for (Event e : EVENTS) {
            events.add("('" + e.key + "', DATE '" + monthStart(e.block) + "', '" + e.category + "', '" + e.type + "', " + e.strength
                    + ", " + e.reaction + ")");
        }
        sql.append("INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, reaction_change, title, source)\n")
                .append("SELECT v.k, b.id, v.c, v.ty, 'START', v.s, v.r, 'synthetic', 'synthetic'\n").append("FROM (VALUES ")
                .append(String.join(", ", events)).append(") AS v(k, ms, c, ty, s, r) JOIN month_block b ON b.month_start = v.ms;\n");
        List<String> attentionRows = new ArrayList<>();
        for (int a = 0; a < ASSETS.size(); a++) {
            for (int t = 1; t <= TURNS; t++) {
                String value = attention[a][t];
                if (value != null && value.isEmpty()) {
                    continue;
                }
                attentionRows.add("('" + ASSETS.get(a).market + "', '" + ASSETS.get(a).ticker + "', DATE '" + monthStart(t) + "', "
                        + (value == null ? "NULL" : value) + ")");
            }
        }
        sql.append("INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)\n")
                .append("SELECT a.id, b.id, CASE WHEN v.i IS NULL THEN 0 ELSE 12 END, CASE WHEN v.i IS NULL THEN NULL ELSE 1.00000000 END, 1, v.i\n")
                .append("FROM (VALUES ").append(String.join(", ", attentionRows))
                .append(") AS v(m, t, ms, i) JOIN asset a ON a.market = v.m AND a.ticker = v.t JOIN month_block b ON b.month_start = v.ms;\n");
        for (long seed : SEEDS) {
            sql.append("INSERT INTO season (member_id, path_length, current_turn_no, seed, engine_version, data_snapshot_date, base_date,"
                    + " initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender,"
                    + " start_midfielder, start_forward, current_goalkeeper, current_defender, current_midfielder, current_forward)\n")
                    .append("SELECT m.id, ").append(TURNS).append(", ").append(TURNS).append(", ").append(seed)
                    .append(", 'synthetic', DATE '2026-01-01', DATE '2026-01-01', 100000000, 0.00015, 0.00025, 0.004, -0.20, 25, 25, 25, 25,"
                            + " 25, 25, 25, 25 FROM member m;\n");
            List<String> paths = new ArrayList<>();
            for (int t = 1; t <= TURNS; t++) {
                paths.add("(" + t + ", DATE '" + monthStart(t) + "')");
            }
            sql.append("INSERT INTO season_path (season_id, turn_no, month_block_id)\nSELECT s.id, v.t, b.id FROM season s CROSS JOIN (VALUES ")
                    .append(String.join(", ", paths)).append(") AS v(t, ms) JOIN month_block b ON b.month_start = v.ms WHERE s.seed = ")
                    .append(seed).append(";\n");
            List<String> prices = new ArrayList<>();
            for (int a = 0; a < ASSETS.size(); a++) {
                for (int t = 0; t <= TURNS; t++) {
                    prices.add("('" + ASSETS.get(a).market + "', '" + ASSETS.get(a).ticker + "', " + t + ", " + price[a][t] + ")");
                }
            }
            sql.append("INSERT INTO turn_price (season_id, turn_no, asset_id, price)\nSELECT s.id, v.t, a.id, v.p FROM season s CROSS JOIN (VALUES ")
                    .append(String.join(", ", prices)).append(") AS v(m, k, t, p) JOIN asset a ON a.market = v.m AND a.ticker = v.k WHERE s.seed = ")
                    .append(seed).append(";\n");
        }
        return sql.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final int OUT_OF_RANGE_BLOCK = 99;

    private static LocalDate monthStart(int turn) {
        if (turn == OUT_OF_RANGE_BLOCK) {
            return LocalDate.of(2026, 3, 1);
        }
        return LocalDate.of(2001, 1, 1).plusMonths(turn);
    }
}

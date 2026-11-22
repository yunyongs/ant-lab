package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MoveDirection;
import antlab.ranking.RankingBoard;
import antlab.ranking.RankingBoardCalculator;
import antlab.ranking.RankingInput;
import antlab.scoring.TurnSignals;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java is the oracle of the Python feed port. A seeded generator builds feed inputs from raw price histories (assembled by the production
 * {@code FeedInputReader.assemble}, the pure part of {@code readAssets}), runs the real {@link FeedGenerator}, the
 * ranking board and {@link TurnSignals}, and compares (or, with {@code ANTLAB_PARITY_WRITE=1} outside CI, rewrites) the tracked file
 * {@code src/test/resources/feed/vectors-v1.jsonl}. The first line pins the rule version and the sha256 of the five {@code feed/*.csv} files, so a changed
 * catalog or rule fails here until the file is regenerated, and the Python twin refuses a file of another catalog.
 *
 * <p>Case line: {@code {"id","input","raw"?,"feed","board","signals"}}. {@code input} is the facts the generator sees; {@code raw}
 * (when present) is what the facts were assembled from. Decimals are plain strings (the scale is part of the value). A refusal is
 * {@code {"error": "<FeedFailure>"}}. Body text is not part of the file: the port does not render it.
 */
class FeedVectorsTest {

    static final Path FILE = Path.of("src/test/resources/feed/vectors-v1.jsonl");
    private static final List<String> CATALOG_FILES = List.of("denylist.csv", "kinds.csv", "notices.csv", "templates.csv", "variables.csv");
    private static final long SEED = 0x5EED_0302_0A01L;
    private static final int RANDOM_CASES = 320;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final FeedGenerator GENERATOR = new FeedGenerator(new FeedTemplateCatalog());

    private static final List<String> CATEGORIES = List.of("GEOPOLITICS", "TRADE", "POLITICS", "SUPPLY_SHOCK", "FINANCIAL", "POLICY",
            "HEALTH");
    private static final List<String> GROUP_NAMES = List.of("기술", "금융", "에너지", "소비재", "헬스케어");
    private static final List<String> GROUP_CODES = List.of("FEED_TECH", "FEED_BANK", "FEED_ENERGY", "FEED_STAPLE", "FEED_HEALTH");
    private static final List<String> SYLLABLES = List.of("푸른", "붉은", "하늘", "달빛", "한강", "북악", "남산", "동해", "서해", "들불", "산바람", "새벽",
            "가람", "무지개", "은하", "바다", "구름", "여름");
    private static final List<String> TAILS = List.of("전자", "반도체", "소프트", "은행", "증권", "보험", "에너지", "가스", "전력", "제약");

    @Test
    void file_equals_what_java_computes() throws IOException {
        List<String> lines = generate();
        if ("1".equals(System.getenv("ANTLAB_PARITY_WRITE"))) {
            if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) {
                throw new IllegalStateException("the vector file is never rewritten under CI");
            }
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        List<String> tracked = Files.readAllLines(FILE, StandardCharsets.UTF_8);
        assertThat(tracked).as("a stale vector file: regenerate with ANTLAB_PARITY_WRITE=1").hasSameSizeAs(lines);
        for (int i = 0; i < lines.size(); i++) {
            assertThat(tracked.get(i)).as("line " + (i + 1)).isEqualTo(lines.get(i));
        }
    }

    @Test
    void cases_reach_every_edge_the_port_must_copy() throws IOException {
        List<String> lines = generate();
        JsonNode header = MAPPER.readTree(lines.get(0));
        assertThat(header.path("rule").asString()).isEqualTo(FeedRules.RULE_VERSION);
        assertThat(header.path("catalogSha256").size()).isEqualTo(CATALOG_FILES.size());
        Map<String, Integer> kinds = new java.util.TreeMap<>();
        int zeroRows = 0;
        int hidden = 0;
        int echoRepeat2 = 0;
        int errors = 0;
        int withRaw = 0;
        Set<String> assemblyErrors = new TreeSet<>();
        int secondRound = 0;
        Map<String, Integer> errorCodes = new java.util.TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            JsonNode node = MAPPER.readTree(line);
            if (node.has("raw")) {
                withRaw++;
            }
            if (node.path("assembly").has("error")) {
                assemblyErrors.add(node.path("assembly").path("error").asString());
            }
            if (!node.has("input")) {
                continue;
            }
            JsonNode feed = node.path("feed");
            if (feed.has("error")) {
                errors++;
                errorCodes.merge(feed.path("error").asString(), 1, Integer::sum);
                continue;
            }
            JsonNode rows = feed.path("rows");
            if (rows.size() == 0) {
                zeroRows++;
            }
            Set<String> seen = new TreeSet<>();
            for (JsonNode row : rows) {
                kinds.merge(row.path("kind").asString(), 1, Integer::sum);
                if (row.path("hidden").booleanValue()) {
                    hidden++;
                }
                if ("ECHO".equals(row.path("kind").asString()) && row.path("repeat").intValue() >= 2) {
                    echoRepeat2++;
                }
                String who = row.path("kind").asString() + "/" + row.path("asset").asString() + "/" + row.path("group").asString() + "/"
                        + row.path("event").asString();
                if (!seen.add(who)) {
                    secondRound++;
                }
            }
        }
        assertThat(kinds.keySet()).containsExactlyInAnyOrder("RISE", "FALL", "ECHO", "SPREAD", "FADE", "EVENT");
        assertThat(kinds.values()).allMatch(n -> n >= 60);
        assertThat(zeroRows).isGreaterThanOrEqualTo(5);
        assertThat(hidden).isGreaterThanOrEqualTo(20);
        assertThat(echoRepeat2).isGreaterThanOrEqualTo(10);
        assertThat(secondRound).isGreaterThanOrEqualTo(50);
        assertThat(withRaw).isGreaterThanOrEqualTo(RANDOM_CASES / 3);
        assertThat(errorCodes.keySet()).containsExactlyInAnyOrder("FEED_INPUT", "FEED_REACTION_MISSING", "FEED_RENDER");
        assertThat(errors).isGreaterThanOrEqualTo(10);
        assertThat(assemblyErrors).containsExactlyInAnyOrder("FEED_INPUT", "MONEY_INVALID");
        assertThat(lines.size() - 1).isGreaterThanOrEqualTo(300);
    }

    // ---- generation ----

    static List<String> generate() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(json(header()));
        SplitMix64 r = new SplitMix64(SEED);
        int id = 1;
        for (int i = 0; i < RANDOM_CASES; i++) {
            lines.add(json(randomCase(r, id++, i)));
        }
        for (Map<String, Object> line : assemblyRefusals(r, id)) {
            lines.add(json(line));
            id++;
        }
        for (Map<String, Object> spec : errorSpecs()) {
            lines.add(json(evaluate(id++, spec, null, null)));
        }
        return lines;
    }

    private static Map<String, Object> header() throws IOException {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("rule", FeedRules.RULE_VERSION);
        Map<String, Object> sha = new LinkedHashMap<>();
        for (String file : CATALOG_FILES) {
            byte[] bytes = Files.readAllBytes(Path.of("feed", file));
            try {
                sha.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        h.put("catalogSha256", sha);
        List<Object> hash = new ArrayList<>();
        for (String text : FeedGeneratorSqlParityTest.HASH_INPUTS) {
            hash.add(map("text", text, "value", Long.toString(FeedHash.hash(text))));
        }
        h.put("hash", hash);
        List<Object> picks = new ArrayList<>();
        picks.add(map("seed", "1", "turn", 1, "usage", "count", "key", "", "value", Long.toString(FeedHash.pick(1L, 1, "count", ""))));
        picks.add(map("seed", "-4096209173561902478", "turn", 14, "usage", "pick", "key", "group:FEED_TECH:3:2",
                "value", Long.toString(FeedHash.pick(-4096209173561902478L, 14, "pick", "group:FEED_TECH:3:2"))));
        h.put("pick", picks);
        List<Object> order = new ArrayList<>();
        for (String text : List.of("B", "a", "가", "asset:KR:F1", "asset:KR:F10", "asset:KR:F2", "event:a", "😀", "Ａ")) {
            order.add(text);
        }
        order.sort((a, b) -> FeedHash.compareBytes((String) a, (String) b));
        h.put("byteOrder", order);
        return h;
    }

    /** The database-free inputs of {@code FeedInputReader.assemble}, before any hand corruption. */
    private record Raw(int turn, long seed, List<Map<String, Object>> pool, Map<Long, List<BigDecimal>> history,
            Map<Long, BigDecimal> current, Map<Long, BigDecimal> attention, Map<Long, BigDecimal> previousAttention,
            List<Map<String, Object>> previousFeed, Map<Long, Integer> scales) {
    }

    private static Raw randomRaw(SplitMix64 r, int index) {
        int regime = index % 8;
        int turn = 1 + r.below(index % 5 == 0 ? 20 : 10);
        int poolSize = 6 + r.below(15);
        int groups = 2 + r.below(4);
        long seed = r.next();
        List<Map<String, Object>> pool = new ArrayList<>();
        List<String> usedNames = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            String name;
            do {
                name = SYLLABLES.get(r.below(SYLLABLES.size())) + TAILS.get(r.below(TAILS.size()));
            } while (usedNames.contains(name));
            usedNames.add(name);
            int g = r.below(groups);
            pool.add(map("id", 100L + i, "market", r.below(2) == 0 ? "KR" : "US", "ticker", String.format("T%03d", r.below(900) + 1),
                    "name", name, "groupId", 1L + g, "groupCode", GROUP_CODES.get(g), "groupName", GROUP_NAMES.get(g)));
        }
        pool = dedupeTickers(pool);

        Map<Long, List<BigDecimal>> history = new LinkedHashMap<>();
        Map<Long, BigDecimal> current = new LinkedHashMap<>();
        Map<Long, Integer> scales = new LinkedHashMap<>();
        for (Map<String, Object> a : pool) {
            long assetId = (Long) a.get("id");
            List<BigDecimal> prices = new ArrayList<>();
            BigDecimal price = new BigDecimal(10_000L * (1 + r.below(200))).setScale(4);
            prices.add(price);
            for (int t = 1; t <= turn; t++) {
                price = step(r, price, regime);
                prices.add(price);
            }
            history.put(assetId, new ArrayList<>(prices.subList(0, turn)));
            current.put(assetId, prices.get(turn));
            scales.put(assetId, r.below(5));
        }
        Map<Long, BigDecimal> attention = new LinkedHashMap<>();
        Map<Long, BigDecimal> previousAttention = new LinkedHashMap<>();
        for (Map<String, Object> a : pool) {
            long assetId = (Long) a.get("id");
            BigDecimal now = attentionValue(r, regime);
            BigDecimal before = regime == 5 ? now : attentionValue(r, regime);
            if (now != null) {
                attention.put(assetId, now);
            }
            if (before != null) {
                previousAttention.put(assetId, before);
            }
        }
        // The block statement reads every asset of the block, so ids outside the pool are normal; they must not move ranks.
        if (regime != 3) {
            for (int k = 0; k < 1 + r.below(3); k++) {
                attention.put(900L + k, new BigDecimal(1 + r.below(90)).movePointLeft(1));
                previousAttention.put(900L + k, new BigDecimal(1 + r.below(90)).movePointLeft(1));
            }
        }
        List<Map<String, Object>> previousFeed = new ArrayList<>();
        if (turn >= 2) {
            for (Map<String, Object> a : pool) {
                int rows = r.below(3);
                for (int k = 0; k < rows; k++) {
                    String kind = List.of("RISE", "FALL", "ECHO", "ECHO", "FADE", "RISE", "FALL").get(r.below(7));
                    String direction = "FADE".equals(kind) ? null : (r.below(2) == 0 ? "UP" : "DOWN");
                    previousFeed.add(map("assetId", a.get("id"), "kind", kind, "direction", direction,
                            "repeat", "ECHO".equals(kind) ? 1 + r.below(3) : 0));
                }
            }
        }
        return new Raw(turn, seed, pool, history, current, attention, previousAttention, previousFeed, scales);
    }

    private static Map<String, Object> rawJson(Raw raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pool", raw.pool());
        Map<String, Object> hist = new LinkedHashMap<>();
        raw.history().forEach((k, v) -> hist.put(Long.toString(k), v.stream().map(p -> priceText(p, raw.scales().get(k))).toList()));
        out.put("history", hist);
        Map<String, Object> cur = new LinkedHashMap<>();
        raw.current().forEach((k, v) -> cur.put(Long.toString(k), priceText(v, raw.scales().get(k))));
        out.put("current", cur);
        out.put("attention", decimalMap(raw.attention()));
        out.put("previousAttention", decimalMap(raw.previousAttention()));
        out.put("previousFeed", raw.previousFeed());
        return out;
    }

    /** The stored price at the shortest scale of at least {@code minimum} digits that is still exact (prices arrive at any scale). */
    private static String priceText(BigDecimal price, int minimum) {
        if (price.scale() > 4) {
            return price.toPlainString();
        }
        int needed = Math.max(0, price.stripTrailingZeros().scale());
        return price.setScale(Math.max(needed, Math.min(minimum, 4))).toPlainString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> assembleJson(Map<String, Object> raw, int turn) {
        List<FeedInputReader.AssetRow> pool = new ArrayList<>();
        for (Object o : (List<Object>) raw.get("pool")) {
            Map<String, Object> a = (Map<String, Object>) o;
            pool.add(new FeedInputReader.AssetRow((Long) a.get("id"), (String) a.get("market"), (String) a.get("ticker"),
                    (String) a.get("name"), (Long) a.get("groupId"), (String) a.get("groupCode"), (String) a.get("groupName")));
        }
        Map<Long, List<BigDecimal>> history = new HashMap<>();
        ((Map<String, Object>) raw.get("history")).forEach((k, v) -> history.put(Long.parseLong(k),
                ((List<Object>) v).stream().map(x -> new BigDecimal((String) x)).toList()));
        Map<Long, BigDecimal> current = new HashMap<>();
        ((Map<String, Object>) raw.get("current")).forEach((k, v) -> current.put(Long.parseLong(k), new BigDecimal((String) v)));
        Map<Long, BigDecimal> attention = new HashMap<>();
        ((Map<String, Object>) raw.get("attention")).forEach((k, v) -> attention.put(Long.parseLong(k), new BigDecimal((String) v)));
        Map<Long, BigDecimal> previous = new HashMap<>();
        ((Map<String, Object>) raw.get("previousAttention")).forEach((k, v) -> previous.put(Long.parseLong(k), new BigDecimal((String) v)));
        List<FeedInputReader.PreviousRow> rows = new ArrayList<>();
        for (Object o : (List<Object>) raw.get("previousFeed")) {
            Map<String, Object> row = (Map<String, Object>) o;
            rows.add(new FeedInputReader.PreviousRow((Long) row.get("assetId"), antlab.season.FeedKind.valueOf((String) row.get("kind")),
                    (String) row.get("direction"), (Integer) row.get("repeat")));
        }
        try {
            List<AssetFacts> facts = FeedInputReader.assemble(turn, pool, history, current, attention, previous, rows);
            return map("facts", facts.stream().map(FeedVectorsTest::factsJson).toList());
        } catch (FeedGenerationException e) {
            return map("error", e.failure().name());
        } catch (IllegalArgumentException e) {
            return map("error", "MONEY_INVALID");
        }
    }

    private static Map<String, Object> factsJson(AssetFacts a) {
        return map("asOfTurn", a.asOfTurn(), "id", a.assetId(), "market", a.market(), "ticker", a.ticker(), "name", a.name(),
                "groupId", a.groupId(), "groupCode", a.groupCode(), "groupName", a.groupName(), "ret", a.ret().toPlainString(),
                "prevRet", a.prevRet() == null ? null : a.prevRet().toPlainString(), "streak", a.streak(),
                "attention", a.attention() == null ? null : a.attention().toPlainString(), "attentionRank", a.attentionRank(),
                "attentionN", a.attentionN(), "prevAttention", a.prevAttention() == null ? null : a.prevAttention().toPlainString(),
                "prevAttentionRank", a.prevAttentionRank(),
                "prevStory", a.prevStoryDirection() == null ? null : a.prevStoryDirection().name(),
                "prevEchoRepeat", a.prevEchoRepeat(), "focusName", a.focusName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> randomCase(SplitMix64 r, int id, int index) {
        Raw raw = randomRaw(r, index);
        int turn = raw.turn();
        Map<String, Object> rawJson = rawJson(raw);
        Map<String, Object> assembly = assembleJson(rawJson, turn);
        List<Object> facts = (List<Object>) assembly.get("facts");
        List<Map<String, Object>> events = new ArrayList<>();
        int regime = index % 8;
        int eventCount = r.below(8) == 0 ? 0 : r.below(4);
        if (regime == 5 && r.below(2) == 0) {
            eventCount = 0;
        }
        TreeSet<String> keys = new TreeSet<>();
        for (int e = 0; e < eventCount; e++) {
            String key;
            do {
                key = "ev" + String.format("%04d", r.below(10000));
            } while (!keys.add(key));
            boolean hidden = r.below(4) == 0;
            events.add(map("asOfTurn", turn, "id", 9000L + e, "key", key, "category", hidden ? null : CATEGORIES.get(r.below(CATEGORIES.size())),
                    "reaction", new BigDecimal(r.below(21) - 10).movePointLeft(2).setScale(8).toPlainString(),
                    "strength", r.below(4) == 0 ? null : 1 + r.below(3), "hidden", hidden));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("seed", Long.toString(raw.seed()));
        input.put("turn", turn);
        input.put("assets", facts);
        input.put("events", events);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("input", input);
        return evaluate(id, spec, index % 3 == 0 ? rawJson : null, assembly);
    }

    /** Hand-corrupted raw inputs: each must be refused the same way by the port. */
    private static List<Map<String, Object>> assemblyRefusals(SplitMix64 r, int firstId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int kind = 0; kind < 4; kind++) {
            Raw raw = randomRaw(r, 3 + 8 * (kind + 1));
            long victim = (Long) raw.pool().get(raw.pool().size() / 2).get("id");
            switch (kind) {
                case 0 -> raw.history().get(victim).set(0, new BigDecimal("100000.00001"));
                case 1 -> raw.history().get(victim).remove(raw.history().get(victim).size() - 1);
                case 2 -> raw.current().remove(victim);
                default -> raw.history().remove(victim);
            }
            Map<String, Object> rawJson = rawJson(raw);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", firstId + kind);
            line.put("turn", raw.turn());
            line.put("raw", rawJson);
            line.put("assembly", assembleJson(rawJson, raw.turn()));
            out.add(line);
        }
        return out;
    }

    private static List<Map<String, Object>> dedupeTickers(List<Map<String, Object>> pool) {
        Set<String> seen = new TreeSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            Map<String, Object> a = new LinkedHashMap<>(pool.get(i));
            String ticker = (String) a.get("ticker");
            while (!seen.add(a.get("market") + ":" + ticker)) {
                ticker = ticker + "x";
            }
            a.put("ticker", ticker);
            out.add(a);
        }
        out.sort(Comparator.<Map<String, Object>, String>comparing(a -> (String) a.get("market"), FeedHash::compareBytes)
                .thenComparing(a -> (String) a.get("ticker"), FeedHash::compareBytes));
        return out;
    }

    /** One price step in basis points: zero changes, moves below and exactly at half a percent, and ordinary moves. */
    private static BigDecimal step(SplitMix64 r, BigDecimal price, int regime) {
        int bp;
        int pick = r.below(100);
        if (regime == 5 || (regime == 0 && pick < 70)) {
            bp = 0;
        } else if (regime == 1) {
            bp = 20 + r.below(400);
        } else if (regime == 2) {
            bp = -(20 + r.below(400));
        } else if (pick < 12) {
            bp = 0;
        } else if (pick < 28) {
            bp = (r.below(2) == 0 ? 1 : -1) * (List.of(1, 25, 49, 50, 51).get(r.below(5)));
        } else {
            bp = (r.below(2) == 0 ? 1 : -1) * (50 + r.below(1500));
        }
        BigDecimal next = price.multiply(BigDecimal.valueOf(10_000L + bp)).divide(BigDecimal.valueOf(10_000L), 4, RoundingMode.HALF_UP);
        return next.signum() <= 0 ? price : next;
    }

    private static BigDecimal attentionValue(SplitMix64 r, int regime) {
        if (regime == 3) {
            return null;
        }
        if (r.below(100) < 15) {
            return null;
        }
        if (regime == 4) {
            return new BigDecimal("2.5");
        }
        int tenths = 5 + r.below(30);
        BigDecimal value = new BigDecimal(tenths).movePointLeft(1);
        return r.below(5) == 0 ? value.setScale(3) : value;
    }

    private static Map<String, Object> decimalMap(Map<Long, BigDecimal> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(Long.toString(k), v.toPlainString()));
        return out;
    }

    // ---- evaluation with the real classes ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evaluate(int id, Map<String, Object> spec, Map<String, Object> raw, Map<String, Object> assembly) {
        Map<String, Object> input = (Map<String, Object>) spec.get("input");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("input", input);
        if (raw != null) {
            out.put("raw", raw);
            out.put("assembly", assembly);
        }
        int turn = (Integer) input.get("turn");
        long seed = Long.parseLong((String) input.get("seed"));
        List<AssetFacts> assets = new ArrayList<>();
        String assetError = null;
        try {
            for (Object a : (List<Object>) input.get("assets")) {
                assets.add(assetOf((Map<String, Object>) a));
            }
        } catch (FeedGenerationException e) {
            assetError = e.failure().name();
        }
        List<EventFacts> events = new ArrayList<>();
        String eventError = null;
        try {
            for (Object e : (List<Object>) input.get("events")) {
                events.add(eventOf((Map<String, Object>) e));
            }
        } catch (FeedGenerationException e) {
            eventError = e.failure().name();
        }
        if (assetError != null || eventError != null) {
            out.put("feed", map("error", assetError != null ? assetError : eventError));
        } else {
            try {
                FeedInput feedInput = new FeedInput(seed, turn, assets, events);
                List<GeneratedFeedRow> rows = GENERATOR.generate(feedInput);
                out.put("feed", map("rows", rows.stream().map(FeedVectorsTest::rowJson).toList()));
                out.put("signals", signalsOf(turn, assets, rows));
            } catch (FeedGenerationException e) {
                out.put("feed", map("error", e.failure().name()));
            }
        }
        if (assetError != null) {
            out.put("board", map("error", assetError));
        } else {
            try {
                out.put("board", boardJson(RankingBoardCalculator.board(new RankingInput(turn, assets))));
            } catch (FeedGenerationException e) {
                out.put("board", map("error", e.failure().name()));
            }
        }
        return out;
    }

    private static AssetFacts assetOf(Map<String, Object> a) {
        return new AssetFacts((Integer) a.get("asOfTurn"), (Long) a.get("id"), (String) a.get("market"), (String) a.get("ticker"),
                (String) a.get("name"), (Long) a.get("groupId"), (String) a.get("groupCode"), (String) a.get("groupName"),
                dec(a.get("ret")), dec(a.get("prevRet")), (Integer) a.get("streak"), dec(a.get("attention")), (Integer) a.get("attentionRank"),
                (Integer) a.get("attentionN"), dec(a.get("prevAttention")), (Integer) a.get("prevAttentionRank"),
                a.get("prevStory") == null ? null : MoveDirection.valueOf((String) a.get("prevStory")), (Integer) a.get("prevEchoRepeat"),
                (String) a.get("focusName"));
    }

    private static EventFacts eventOf(Map<String, Object> e) {
        return new EventFacts((Integer) e.get("asOfTurn"), (Long) e.get("id"), (String) e.get("key"), (String) e.get("category"),
                dec(e.get("reaction")), (Integer) e.get("strength"), (Boolean) e.get("hidden"));
    }

    private static BigDecimal dec(Object value) {
        return value == null ? null : new BigDecimal((String) value);
    }

    private static Map<String, Object> rowJson(GeneratedFeedRow row) {
        return map("seq", row.seq(), "kind", row.kind().name(), "asset", row.assetId() == null ? null : row.assetId().toString(),
                "group", row.assetGroupId() == null ? null : row.assetGroupId().toString(),
                "event", row.monthEventId() == null ? null : row.monthEventId().toString(), "hidden", row.categoryHidden(),
                "direction", row.direction() == null ? null : row.direction().name(), "strength", row.strength(),
                "repeat", row.repeatCount(), "template", row.templateId(), "vars", row.templateVarsJson());
    }

    private static Map<String, Object> signalsOf(int turn, List<AssetFacts> assets, List<GeneratedFeedRow> rows) {
        List<TurnSignals.FeedRow> feedRows = new ArrayList<>();
        for (GeneratedFeedRow row : rows) {
            feedRows.add(new TurnSignals.FeedRow(turn, row.kind(), row.assetId(), row.direction()));
        }
        try {
            TurnSignals s = TurnSignals.of(turn, feedRows, new RankingInput(turn, assets));
            return map("up", sorted(s.up()), "down", sorted(s.down()), "topAttention", sorted(s.topAttention()));
        } catch (FeedGenerationException e) {
            return map("error", e.failure().name());
        }
    }

    private static List<Long> sorted(Set<Long> ids) {
        return ids.stream().sorted().toList();
    }

    private static Map<String, Object> boardJson(RankingBoard b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gainers", b.gainers().stream().map(a -> map("rank", a.rank(), "asset", a.assetId(), "rate", a.changeRate().toPlainString())).toList());
        out.put("losers", b.losers().stream().map(a -> map("rank", a.rank(), "asset", a.assetId(), "rate", a.changeRate().toPlainString())).toList());
        out.put("attention", b.attention().stream().map(a -> map("rank", a.rank(), "asset", a.assetId(), "grade", a.grade())).toList());
        out.put("risingStreaks", b.risingStreaks().stream()
                .map(a -> map("rank", a.rank(), "asset", a.assetId(), "streak", a.streak(), "rate", a.changeRate().toPlainString())).toList());
        out.put("fallingStreaks", b.fallingStreaks().stream()
                .map(a -> map("rank", a.rank(), "asset", a.assetId(), "streak", a.streak(), "rate", a.changeRate().toPlainString())).toList());
        out.put("groupGainers", b.groupGainers().stream().map(a -> map("rank", a.rank(), "group", a.groupId(), "rate", a.changeRate().toPlainString())).toList());
        out.put("groupLosers", b.groupLosers().stream().map(a -> map("rank", a.rank(), "group", a.groupId(), "rate", a.changeRate().toPlainString())).toList());
        return out;
    }

    // ---- hand-made refusals ----

    private static Map<String, Object> asset(long id, String ticker, String name, String ret, int turn) {
        return map("asOfTurn", turn, "id", id, "market", "KR", "ticker", ticker, "name", name, "groupId", 1L, "groupCode", "FEED_TECH",
                "groupName", "기술", "ret", ret, "prevRet", null, "streak", 1, "attention", null, "attentionRank", null, "attentionN", 0,
                "prevAttention", null, "prevAttentionRank", null, "prevStory", null, "prevEchoRepeat", 1, "focusName", null);
    }

    private static Map<String, Object> event(int turn, long id, String key, String category, String reaction, boolean hidden) {
        return map("asOfTurn", turn, "id", id, "key", key, "category", category, "reaction", reaction, "strength", 2, "hidden", hidden);
    }

    private static Map<String, Object> spec(int turn, List<Object> assets, List<Object> events) {
        return map("input", map("seed", "42", "turn", turn, "assets", assets, "events", events));
    }

    private static List<Map<String, Object>> errorSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5), asset(2, "A1", "다라", "0.02000000", 5)), List.of()));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 4)), List.of()));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.0100", 5)), List.of()));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)), List.of(event(4, 9, "evt001", "TRADE", "0.02000000", false))));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)),
                List.of(event(5, 9, "evt001", "TRADE", "0.02000000", false), event(5, 10, "evt001", "POLICY", "0.02000000", false))));
        List<Object> noReaction = new ArrayList<>();
        noReaction.add(event(5, 9, "evt001", "TRADE", null, false));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)), noReaction));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)), List.of(event(5, 9, "evt001", "TRADE", "0.02000000", true))));
        List<Object> publicNoCategory = new ArrayList<>();
        publicNoCategory.add(event(5, 9, "evt001", null, "0.02000000", false));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)), publicNoCategory));
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)), List.of(event(5, 9, "evt001", "MYSTERY", "0.02000000", false))));
        List<Object> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            sixteen.add(event(5, 100 + i, String.format("evt%03d", i), "TRADE", "0.02000000", false));
        }
        specs.add(spec(5, List.of(), sixteen));
        List<Object> fifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fifteen.add(event(5, 100 + i, String.format("evt%03d", i), "HEALTH", "-0.03000000", false));
        }
        specs.add(spec(5, List.of(asset(1, "A1", "가나", "0.01000000", 5)), fifteen));
        specs.add(spec(1, List.of(), List.of()));
        return specs;
    }

    // ---- helpers ----

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static final class SplitMix64 {
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

        int below(int bound) {
            return (int) Long.remainderUnsigned(next(), bound);
        }
    }
}

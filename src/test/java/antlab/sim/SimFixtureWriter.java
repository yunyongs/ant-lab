package antlab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.feed.FeedRules;
import antlab.scoring.DisciplineRules;
import antlab.scoring.ReportRules;
import antlab.scoring.SeasonScoreCalculator;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads, merges, writes and compares the tracked parity fixture {@code seasons-v1.json}. The file is one JSON object, with
 * the header on one line and one compact line per season, so a changed season shows up as one changed line. The 30 M and the
 * 100 M seasons run in two Spring contexts; each context owns its own seasons, so writing merges them into the file by index.
 *
 * <p>Decimals are written as plain strings (the scale is part of the value), integers as numbers, absent values as null.
 */
final class SimFixtureWriter {

    static final Path FILE = Path.of("src/test/resources/sim/parity/seasons-v1.json");
    static final String SCHEMA = "sim-parity/1";
    static final Path WORLDS_FILE = Path.of("src/test/resources/sim/parity/worlds-v1.json");
    static final String WORLDS_SCHEMA = "sim-world-parity/1";
    static final int SEASONS = 20;
    static final JsonMapper MAPPER = JsonMapper.shared();

    private SimFixtureWriter() {
    }

    static boolean writeMode() {
        boolean write = "1".equals(System.getenv("ANTLAB_PARITY_WRITE"));
        if (write && (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null)) {
            throw new IllegalStateException("ANTLAB_PARITY_WRITE must not be set on CI: write mode would hide a stale fixture");
        }
        return write;
    }

    static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** The rule constants the Python simulator mirrors (Java is canonical); Python asserts that its own equal these. */
    static ObjectNode header(long masterSeed, int pathCount, String baseDate) {
        ObjectNode h = MAPPER.createObjectNode();
        h.put("schema", SCHEMA);
        h.put("scoreVersion", SeasonScoreCalculator.VERSION);
        h.put("masterSeed", Long.toString(masterSeed));
        h.put("pathCount", pathCount);
        h.put("baseDate", baseDate);
        h.put("seasonMinMonths", ReportRules.SEASON_MIN_MONTHS);
        h.put("seasonMinUp", ReportRules.SEASON_MIN_UP);
        h.put("seasonMinDown", ReportRules.SEASON_MIN_DOWN);
        h.put("annualTurns", ReportRules.ANNUAL_TURNS);
        h.put("band", plain(ReportRules.BAND));
        h.put("topRatio", plain(ReportRules.TOP_RATIO));
        h.put("benchKr", ReportRules.BENCH_KR_MARKET + ":" + ReportRules.BENCH_KR_TICKER);
        h.put("benchUs", ReportRules.BENCH_US_MARKET + ":" + ReportRules.BENCH_US_TICKER);
        h.put("benchKrWeight", plain(ReportRules.BENCH_KR_WEIGHT));
        h.put("benchUsWeight", plain(ReportRules.BENCH_US_WEIGHT));
        h.set("nudgeSources", sorted(ReportRules.NUDGE_SOURCES));
        h.set("platformSources", sorted(ReportRules.PLATFORM_SOURCES));
        h.put("goalkeeperDownMax", plain(ReportRules.GOALKEEPER_DOWN_MAX));
        h.put("defenderDownBelow", plain(ReportRules.DEFENDER_DOWN_BELOW));
        h.put("breakoutUpAbove", plain(ReportRules.BREAKOUT_UP_ABOVE));
        h.put("breakoutMddFactor", plain(ReportRules.BREAKOUT_MDD_FACTOR));
        h.put("defensiveUpMin", plain(ReportRules.DEFENSIVE_UP_MIN));
        h.put("defensiveDownBelow", plain(ReportRules.DEFENSIVE_DOWN_BELOW));
        h.put("targetUpAbove", plain(ReportRules.TARGET_UP_ABOVE));
        h.put("targetDownMax", plain(ReportRules.TARGET_DOWN_MAX));
        h.put("dropPercent", DisciplineRules.DROP_PERCENT);
        h.put("risePercent", DisciplineRules.RISE_PERCENT);
        h.put("notStartedBelowTurn", notStartedBelowTurn());
        return h;
    }

    /** Pins the feed rule version and the catalog the stored rows were generated with. */
    static ObjectNode worldsHeader(long masterSeed, String baseDate) {
        ObjectNode h = MAPPER.createObjectNode();
        h.put("schema", WORLDS_SCHEMA);
        h.put("masterSeed", Long.toString(masterSeed));
        h.put("baseDate", baseDate);
        h.put("rule", FeedRules.RULE_VERSION);
        ObjectNode sha = h.putObject("catalogSha256");
        for (String name : List.of("denylist.csv", "kinds.csv", "notices.csv", "templates.csv", "variables.csv")) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of("feed", name));
                sha.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new IllegalStateException("feed/" + name + " is not readable", e);
            }
        }
        return h;
    }

    private static ArrayNode sorted(Set<String> values) {
        ArrayNode out = MAPPER.createArrayNode();
        new TreeSet<>(values).forEach(out::add);
        return out;
    }

    /** {@code AbandonRules} keeps its threshold package-private; the header must still carry the Java value. */
    private static int notStartedBelowTurn() {
        try {
            Field field = Class.forName("antlab.season.AbandonRules").getDeclaredField("NOT_STARTED_BELOW_TURN");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AbandonRules.NOT_STARTED_BELOW_TURN is not readable", e);
        }
    }

    /** Writes (merging by season index into what the file holds) or compares, depending on the write switch. */
    static void writeOrCompare(ObjectNode builtHeader, Map<Integer, ObjectNode> built) throws IOException {
        writeOrCompare(FILE, SCHEMA, builtHeader, built);
    }

    /** The feed-world companion file; same layout, one line per season, merged by index. */
    static void writeOrCompareWorlds(ObjectNode builtHeader, Map<Integer, ObjectNode> built) throws IOException {
        writeOrCompare(WORLDS_FILE, WORLDS_SCHEMA, builtHeader, built);
    }

    static void writeOrCompare(Path file, String schema, ObjectNode builtHeader, Map<Integer, ObjectNode> built) throws IOException {
        // Round trip through text so integer widths and key order are exactly what the file will hold.
        JsonNode header = MAPPER.readTree(MAPPER.writeValueAsString(builtHeader));
        Map<Integer, JsonNode> mine = new TreeMap<>();
        for (Map.Entry<Integer, ObjectNode> entry : built.entrySet()) {
            mine.put(entry.getKey(), MAPPER.readTree(MAPPER.writeValueAsString(entry.getValue())));
        }
        if (writeMode()) {
            Map<Integer, JsonNode> merged = new TreeMap<>();
            if (Files.exists(file)) {
                JsonNode existing = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (existing.get("header").equals(header)) {
                    existing.get("seasons").forEach(s -> merged.put(s.get("index").asInt(), s));
                }
            }
            merged.putAll(mine);
            write(file, schema, header, merged);
        }
        JsonNode tracked = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(firstDifference(header, tracked.get("header"), "header")).as("fixture header").isNull();
        Map<Integer, JsonNode> bySeason = new TreeMap<>();
        tracked.get("seasons").forEach(s -> bySeason.put(s.get("index").asInt(), s));
        for (Map.Entry<Integer, JsonNode> entry : mine.entrySet()) {
            JsonNode there = bySeason.get(entry.getKey());
            assertThat(there).as("season " + entry.getKey() + " is in the fixture").isNotNull();
            assertThat(firstDifference(entry.getValue(), there, "season " + entry.getKey()))
                    .as("fixture is stale; regenerate with ANTLAB_PARITY_WRITE=1").isNull();
        }
    }

    private static void write(Path file, String schema, JsonNode header, Map<Integer, JsonNode> seasons) throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonNode season : seasons.values()) {
            lines.add(MAPPER.writeValueAsString(season));
        }
        String text = "{\"schema\":\"" + schema + "\",\n\"header\":" + MAPPER.writeValueAsString(header)
                + ",\n\"seasons\":[\n" + String.join(",\n", lines) + "\n]}\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** The path of the first difference between two trees (values are not printed), or null when they are equal. */
    static String firstDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected == null || actual == null) {
            return expected == actual ? null : path + " (missing on one side)";
        }
        if (expected.isObject() && actual.isObject()) {
            Set<String> names = new TreeSet<>();
            expected.propertyNames().forEach(names::add);
            actual.propertyNames().forEach(names::add);
            for (String name : names) {
                String inner = firstDifference(expected.get(name), actual.get(name), path + "." + name);
                if (inner != null) {
                    return inner;
                }
            }
            return null;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                return path + " (length " + expected.size() + " vs " + actual.size() + ")";
            }
            for (int i = 0; i < expected.size(); i++) {
                String inner = firstDifference(expected.get(i), actual.get(i), path + "[" + i + "]");
                if (inner != null) {
                    return inner;
                }
            }
            return null;
        }
        return expected.equals(actual) ? null : path;
    }
}

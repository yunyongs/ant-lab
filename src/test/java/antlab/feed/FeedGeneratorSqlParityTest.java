package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.money.MoneyMath;
import antlab.season.FeedKind;
import antlab.support.GoldenCleanup;
import antlab.support.GoldenFixtureSource;
import antlab.support.PsqlScriptRunner;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario.Kind;
import antlab.support.SqlGoldenScenario.ScriptStep;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real {@code db/24_turn_feed_def.sql} runs turn by turn on a synthetic season (no sample CSV) in a disposable PostgreSQL 17, and
 * {@link FeedGenerator}, fed with the very input {@code tf_input} assembled, must return the rows {@code tf_append} stored: same
 * order, ids, strength, repeat, template, variable text and body, for every turn. Always on, so CI proves SQL = Java on purpose-built
 * edge cases. The same session also checks the hash vectors that {@code FeedHashTest} pins.
 */
class FeedGeneratorSqlParityTest {

    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(2);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final List<String> HASH_INPUTS = List.of("", "1:1:p0-18:v2:count:", "7531902468135790246:7:p0-18:v2:tpl:event:aaaaaa_01:1",
            "-4096209173561902478:14:p0-18:v2:pick:group:FEED_TECH:3:2", "가나다:order:\"\\");

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static RowSet result;
    static DatabaseHandle database;

    @BeforeAll
    static void start() throws Exception {
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, GoldenFixtureSource.fromEnvironment());
        PsqlScriptRunner runner = harness.runner();
        for (String file : new String[] {"db/01_schema_history.sql", "db/02_schema_season.sql", "db/24_turn_feed_def.sql",
                "feed/templates.csv", "feed/variables.csv"}) {
            runner.putFile(file, Path.of(file));
        }
        runner.putFile("feed-parity/run.sql", Path.of("src/test/resources/feed-parity/run.sql"));
        runner.putFile("synthetic/feed_history.sql", SyntheticFeedHistory.standard().script());
        DatabaseHandle db = harness.createSqlDatabase("feed_parity");
        database = db;
        for (String file : new String[] {"db/01_schema_history.sql", "db/02_schema_season.sql", "synthetic/feed_history.sql"}) {
            SqlGoldenHarness.ScriptResult step = harness.run(db, new ScriptStep(file, Kind.RUN, null, STEP_TIMEOUT));
            assertThat(step.process().exitCode()).as(file).isZero();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < HASH_INPUTS.size(); i++) {
            values.add("(" + i + ", '" + HASH_INPUTS.get(i).replace("'", "''") + "')");
        }
        String sql = "SELECT 'turn' AS what, season_id AS season_id, turn_no::bigint AS turn_no, input::text AS input, stored::text AS stored"
                + " FROM pg_temp.parity_out UNION ALL SELECT 'hash', 0, v.i, v.t, pg_temp.tf_hash(v.t)::text FROM (VALUES "
                + String.join(", ", values) + ") AS v(i, t) ORDER BY 1, 2, 3";
        result = harness.queryWithDefinitions(db, "feed-parity/run.sql", new RowQuery("feed_parity", sql,
                List.of(new RowColumn("what", ColumnType.TEXT), new RowColumn("season_id", ColumnType.LONG),
                        new RowColumn("turn_no", ColumnType.LONG), new RowColumn("input", ColumnType.TEXT),
                        new RowColumn("stored", ColumnType.TEXT))), 0L);
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }

    private static String line(String seq, String kind, String asset, String group, String event, String hidden, String direction,
            String strength, String repeat, String template, String vars, String body) {
        return String.join("|", seq, kind, asset, group, event, hidden, direction, strength, repeat, template, vars, body);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? "-" : value.asString();
    }

    private static String sqlLine(JsonNode row) {
        return line(text(row, "seq"), text(row, "kind"), text(row, "asset_id"), text(row, "asset_group_id"), text(row, "month_event_id"),
                text(row, "category_hidden"), text(row, "direction"), text(row, "strength"), text(row, "repeat_count"),
                text(row, "template_id"), text(row, "template_vars_text"), text(row, "body"));
    }

    private static String javaLine(GeneratedFeedRow row) {
        return line(Integer.toString(row.seq()), row.kind().name(), String.valueOf(row.assetId() == null ? "-" : row.assetId()),
                String.valueOf(row.assetGroupId() == null ? "-" : row.assetGroupId()),
                String.valueOf(row.monthEventId() == null ? "-" : row.monthEventId()), Boolean.toString(row.categoryHidden()),
                row.direction() == null ? "-" : row.direction().name(), row.strength() == null ? "-" : row.strength().toString(),
                Integer.toString(row.repeatCount()), row.templateId(), row.templateVarsJson(), row.body());
    }

    @Test
    void the_java_generator_returns_the_rows_the_sql_generator_stored_on_every_turn() {
        FeedGenerator generator = new FeedGenerator(new FeedTemplateCatalog());
        Map<FeedKind, Integer> kinds = new EnumMap<>(FeedKind.class);
        int turns = 0;
        int zeroRowTurns = 0;
        int eventsOnlyTurns = 0;
        int hiddenEvents = 0;
        int echoRepeatAtLeast2 = 0;
        int zeroPercentBodies = 0;
        int tiedAttention = 0;
        int zeroMeanGroups = 0;
        int secondRoundPicks = 0;
        int weightedNoiseRows = 0;
        int rows = 0;
        Pattern zeroPercent = Pattern.compile("(^|[^0-9+\\-])0%");
        Map<String, Integer> byTemplate = new LinkedHashMap<>();
        for (int i = 0; i < result.size(); i++) {
            if (!"turn".equals(result.get(i, "what"))) {
                continue;
            }
            String where = "season " + result.get(i, "season_id") + " turn " + result.get(i, "turn_no");
            FeedInput input = FeedInputJson.read((String) result.get(i, "input"));
            List<String> expected = new ArrayList<>();
            for (JsonNode row : MAPPER.readTree((String) result.get(i, "stored"))) {
                expected.add(sqlLine(row));
            }
            List<GeneratedFeedRow> generated = generator.generate(input);
            assertThat(generated.stream().map(FeedGeneratorSqlParityTest::javaLine).toList()).as(where).isEqualTo(expected);
            assertThat(generated).as(where).hasSizeLessThanOrEqualTo(15);

            turns++;
            rows += generated.size();
            if (generated.isEmpty()) {
                zeroRowTurns++;
            }
            if (!generated.isEmpty() && generated.stream().allMatch(r -> r.kind() == FeedKind.EVENT)) {
                eventsOnlyTurns++;
            }
            Set<String> candidates = new HashSet<>();
            for (GeneratedFeedRow r : generated) {
                String candidate = r.kind() + "/" + r.assetId() + "/" + r.assetGroupId() + "/" + r.monthEventId();
                if (!candidates.add(candidate)) {
                    secondRoundPicks++;
                }
                if (r.kind() != FeedKind.EVENT && r.strength() != null && r.strength() >= 2) {
                    weightedNoiseRows++;
                }
                kinds.merge(r.kind(), 1, Integer::sum);
                byTemplate.merge(r.templateId(), 1, Integer::sum);
                if (r.categoryHidden()) {
                    hiddenEvents++;
                }
                if (r.kind() == FeedKind.ECHO && r.repeatCount() >= 2) {
                    echoRepeatAtLeast2++;
                }
                if (zeroPercent.matcher(r.body()).find()) {
                    zeroPercentBodies++;
                }
            }
            tiedAttention += (int) input.assets().stream().filter(a -> a.attentionRank() != null && a.attentionRank() > 1
                    && input.assets().stream().anyMatch(o -> o != a && a.attentionRank().equals(o.attentionRank()))).count();
            Map<Long, List<AssetFacts>> groups = new LinkedHashMap<>();
            input.assets().forEach(a -> groups.computeIfAbsent(a.groupId(), k -> new ArrayList<>()).add(a));
            for (List<AssetFacts> members : groups.values()) {
                BigDecimal mean = MoneyMath.round8(MoneyMath.pgAvg(members.stream().map(AssetFacts::ret).toList()));
                if (mean.signum() == 0 && members.stream().anyMatch(a -> a.ret().signum() != 0)) {
                    zeroMeanGroups++;
                }
            }
        }
        System.out.println("[sql-parity] feed synthetic turns=" + turns + " rows=" + rows + " kinds=" + kinds + " zeroRowTurns="
                + zeroRowTurns + " eventsOnlyTurns=" + eventsOnlyTurns + " hiddenEvents=" + hiddenEvents + " echoRepeat>=2="
                + echoRepeatAtLeast2 + " zeroPercentBodies=" + zeroPercentBodies + " tiedAttention=" + tiedAttention
                + " zeroMeanGroups=" + zeroMeanGroups + " secondRoundPicks=" + secondRoundPicks + " weightedNoiseRows="
                + weightedNoiseRows + " templatesUsed=" + byTemplate.size());
        assertThat(turns).as("turns compared").isEqualTo(SyntheticFeedHistory.SEEDS.size() * SyntheticFeedHistory.TURNS);
        assertThat(zeroRowTurns).as("zero-candidate turns").isEqualTo(SyntheticFeedHistory.SEEDS.size());
        assertThat(eventsOnlyTurns).as("events-only turns").isEqualTo(SyntheticFeedHistory.SEEDS.size());
        assertThat(hiddenEvents).as("hidden event cards").isPositive();
        for (FeedKind kind : List.of(FeedKind.RISE, FeedKind.FALL, FeedKind.ECHO, FeedKind.SPREAD, FeedKind.FADE, FeedKind.EVENT)) {
            assertThat(kinds.getOrDefault(kind, 0)).as("rows of kind " + kind).isPositive();
        }
        assertThat(echoRepeatAtLeast2).as("echo chain of 2 or more").isPositive();
        assertThat(zeroPercentBodies).as("sentences that show 0%").isPositive();
        assertThat(tiedAttention).as("assets tied on attention rank").isPositive();
        assertThat(zeroMeanGroups).as("groups whose mean return rounds to 0 while members move").isPositive();
        assertThat(secondRoundPicks).as("a candidate drawn again in a later round").isPositive();
        assertThat(weightedNoiseRows).as("noise rows with weight 2 or 3").isPositive();
    }

    /**
     * The Java reader on the same database: the input it assembles from the tables for every turn equals the JSON {@code tf_input}
     * assembled, fact by fact (returns, streaks, ranks with ties, previous attention, previous story, focus, events). The turn's own
     * prices are handed over as the advance does; the stored feed of the previous turn was written by the SQL generator.
     */
    @Test
    void the_java_reader_assembles_the_input_that_tf_input_assembled() {
        org.springframework.jdbc.datasource.DriverManagerDataSource source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database.name(),
                container.getUsername(), container.getPassword());
        JdbcClient jdbc = JdbcClient.create(source);
        FeedInputReader reader = new FeedInputReader(jdbc);
        int checked = 0;
        int withStory = 0;
        for (int i = 0; i < result.size(); i++) {
            if (!"turn".equals(result.get(i, "what"))) {
                continue;
            }
            long season = (Long) result.get(i, "season_id");
            int turn = ((Long) result.get(i, "turn_no")).intValue();
            FeedInput expected = FeedInputJson.read((String) result.get(i, "input"));
            Map<Long, BigDecimal> current = new LinkedHashMap<>();
            jdbc.sql("SELECT asset_id, price FROM turn_price WHERE season_id = :season AND turn_no = :turn")
                    .param("season", season).param("turn", turn).query(rs -> {
                        current.put(rs.getLong("asset_id"), rs.getBigDecimal("price"));
                    });
            FeedInput actual = reader.read(season, expected.seed(), turn, current);
            assertThat(actual).as("season " + season + " turn " + turn).isEqualTo(expected);
            withStory += (int) actual.assets().stream().filter(a -> a.prevStoryDirection() != null).count();
            checked++;
        }
        assertThat(checked).isEqualTo(SyntheticFeedHistory.SEEDS.size() * SyntheticFeedHistory.TURNS);
        assertThat(withStory).as("assets with a previous story, so the feed read is exercised").isPositive();
    }

    @Test
    void the_hash_matches_postgresql_for_every_vector() {
        int seen = 0;
        for (int i = 0; i < result.size(); i++) {
            if ("hash".equals(result.get(i, "what"))) {
                String input = (String) result.get(i, "input");
                assertThat(FeedHash.hash(input)).as("hash of vector " + i).isEqualTo(Long.parseLong((String) result.get(i, "stored")));
                seen++;
            }
        }
        assertThat(seen).isEqualTo(HASH_INPUTS.size());
    }
}

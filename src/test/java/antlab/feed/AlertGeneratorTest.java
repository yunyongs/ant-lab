package antlab.feed;

import static antlab.feed.FeedFacts.asset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.feed.AlertRules.Family;
import antlab.ranking.RankingBoard;
import antlab.ranking.RankingBoardCalculator;
import antlab.ranking.RankingInput;
import antlab.season.FeedKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The alert rules of {@code mvp-12:v1} on hand-made facts: count, slots, families, determinism, row shape and the refusals. */
class AlertGeneratorTest {

    private static final int TURN = 9;
    private static final int FIRST_SEQ = 7;
    private static final String CASH_HIGH = "0.50000000";
    private static final String CASH_LOW = "0.10000000";
    /** Numbers stay decimals, so scale 8 survives the round trip. */
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final FeedTemplateCatalog catalog = new FeedTemplateCatalog();
    private final AlertGenerator generator = new AlertGenerator(catalog);

    /**
     * Eight assets in three groups. B1 has no attention figure, A6 does not move but holds the top attention (so it is a candidate
     * that is on neither the gainers nor the losers list), the others are movers.
     */
    private static List<AssetFacts> assets() {
        return List.of(
                asset(1, "A1", "가나").turn(TURN).group(1, "G1", "기술").ret("0.08000000").streak(3).attention("8", 2, 7, null, null).build(),
                asset(2, "A2", "다라").turn(TURN).group(1, "G1", "기술").ret("0.05000000").streak(1).attention("7", 3, 7, null, null).build(),
                asset(3, "A3", "마바").turn(TURN).group(2, "G2", "금융").ret("0.02000000").streak(2).attention("6", 4, 7, null, null).build(),
                asset(4, "A4", "사아").turn(TURN).group(2, "G2", "금융").ret("-0.03000000").streak(2).attention("5", 5, 7, null, null).build(),
                asset(5, "A5", "자차").turn(TURN).group(3, "G3", "에너지").ret("-0.06000000").streak(1).attention("4", 6, 7, null, null).build(),
                asset(6, "A6", "카타").turn(TURN).group(3, "G3", "에너지").ret("0.00000000").streak(1).attention("9", 1, 7, null, null).build(),
                asset(7, "A7", "파하").turn(TURN).group(3, "G3", "에너지").ret("-0.01000000").streak(1).attention("3", 7, 7, null, null).build(),
                asset(8, "B1", "거너").turn(TURN).group(1, "G1", "기술").ret("0.01000000").streak(1).build());
    }

    private static PlayerFacts player(int idle, String cash) {
        return new PlayerFacts(TURN, cash == null ? null : new BigDecimal(cash), idle == 0 ? 2 : 0, idle);
    }

    private List<GeneratedFeedRow> rows(long seed, int idle, String cash) {
        return generator.generate(new AlertInput(seed, TURN, assets(), player(idle, cash)), FIRST_SEQ);
    }

    private Family family(GeneratedFeedRow row) {
        return AlertRules.familyOf(catalog.active(row.templateId()).orElseThrow()).orElseThrow();
    }

    private static List<String> picks(List<GeneratedFeedRow> rows) {
        return rows.stream().map(r -> r.assetId() + "/" + r.templateId()).toList();
    }

    private List<Family> families(List<GeneratedFeedRow> rows) {
        return rows.stream().map(this::family).toList();
    }

    // ---- T-A1 count ----

    @Test
    void the_count_is_one_plus_the_idle_intervals_and_at_most_four() {
        int[] idle = {0, 1, 2, 3, 7};
        int[] expected = {1, 2, 3, 4, 4};
        for (int seed = 1; seed <= 10; seed++) {
            for (int i = 0; i < idle.length; i++) {
                assertThat(rows(seed, idle[i], CASH_HIGH)).as("seed " + seed + " idle " + idle[i]).hasSize(expected[i]);
            }
        }
        assertThat(AlertRules.MAX_ALERTS).isEqualTo(4);
        assertThat(AlertRules.count(0)).isEqualTo(1);
        assertThat(AlertRules.count(3)).isEqualTo(4);
        assertThat(AlertRules.count(99)).isEqualTo(4);
    }

    // ---- T-A2 slots and families ----

    @Test
    void slots_come_in_the_fixed_order_and_only_when_eligible() {
        for (int seed = 1; seed <= 20; seed++) {
            assertThat(families(rows(seed, 0, CASH_HIGH))).as("traded last interval").containsExactly(Family.ASSET);
            assertThat(families(rows(seed, 3, CASH_HIGH))).as("idle, cash high").containsExactly(Family.ASSET, Family.IDLE,
                    Family.CASH, Family.FREE);
            List<GeneratedFeedRow> lowCash = rows(seed, 3, CASH_LOW);
            assertThat(families(lowCash)).as("idle, cash low").containsExactly(Family.ASSET, Family.IDLE, Family.FREE, Family.ASSET);
            assertThat(lowCash.get(0).assetId()).as("the second asset slot names a different asset").isNotEqualTo(lowCash.get(3).assetId());
            assertThat(families(rows(seed, 1, CASH_LOW))).containsExactly(Family.ASSET, Family.IDLE);
        }
    }

    @Test
    void the_cash_alert_needs_a_cash_share_of_at_least_twenty_percent() {
        for (int seed = 1; seed <= 10; seed++) {
            assertThat(families(rows(seed, 2, "0.19999999"))).containsExactly(Family.ASSET, Family.IDLE, Family.FREE);
            assertThat(families(rows(seed, 2, "0.20000000"))).containsExactly(Family.ASSET, Family.IDLE, Family.CASH);
        }
        assertThat(AlertRules.CASH_NUDGE_MIN).isEqualByComparingTo("0.20");
    }

    @Test
    void a_cash_share_that_is_unknown_gives_no_cash_row_and_no_error() {
        for (int seed = 1; seed <= 10; seed++) {
            assertThat(families(rows(seed, 2, null))).containsExactly(Family.ASSET, Family.IDLE, Family.FREE);
        }
    }

    // ---- T-A3 monotone ----

    @Test
    void a_quieter_player_gets_more_rows_and_the_earlier_rows_do_not_change() {
        for (int seed = 1; seed <= 30; seed++) {
            for (String cash : List.of(CASH_HIGH, CASH_LOW)) {
                List<GeneratedFeedRow> traded = rows(seed, 0, cash);
                List<GeneratedFeedRow> idle = rows(seed, 2, cash);
                assertThat(idle.size()).isGreaterThan(traded.size());
                assertThat(idle.get(0)).as("seed " + seed).isEqualTo(traded.get(0));
                // The idle sentence shows the idle count, so only the pick (asset and template) is compared.
                assertThat(picks(rows(seed, 3, cash).subList(0, idle.size()))).as("a longer idle run keeps the shorter one's picks")
                        .isEqualTo(picks(idle));
            }
        }
    }

    // ---- T-A4 determinism and the version in the hash ----

    @Test
    void the_same_input_gives_the_same_rows_and_other_seeds_give_other_picks() {
        Set<List<GeneratedFeedRow>> distinct = new HashSet<>();
        for (long seed = 1; seed <= 20; seed++) {
            assertThat(rows(seed, 3, CASH_HIGH)).isEqualTo(rows(seed, 3, CASH_HIGH));
            distinct.add(rows(seed, 3, CASH_HIGH));
        }
        assertThat(distinct.size()).as("twenty seeds give more than one result").isGreaterThan(1);
    }

    @Test
    void the_alert_rule_version_is_part_of_the_hash_and_the_feed_version_is_not_used() {
        assertThat(AlertRules.RULE_VERSION).isEqualTo("mvp-12:v1");
        assertThat(FeedRules.RULE_VERSION).isEqualTo("p0-18:v2");
        assertThat(AlertRules.pick(5, 9, "alert-pick", "k")).isEqualTo(FeedHash.hash("5:9:mvp-12:v1:alert-pick:k"));
        assertThat(AlertRules.pick(5, 9, "alert-pick", "k")).isNotEqualTo(FeedHash.pick(5, 9, "alert-pick", "k"));
    }

    // ---- T-A5 row shape ----

    @Test
    void every_row_is_a_complete_nudge_row_whose_text_is_its_template_rendered_with_its_variables() throws Exception {
        FeedRenderer renderer = new FeedRenderer(catalog.variables());
        RankingBoard board = RankingBoardCalculator.board(new RankingInput(TURN, assets()));
        Map<Long, Integer> listRank = new LinkedHashMap<>();
        board.gainers().forEach(r -> listRank.put(r.assetId(), r.rank()));
        board.losers().forEach(r -> listRank.put(r.assetId(), r.rank()));
        boolean sawRank = false;
        boolean sawNoRank = false;
        boolean sawFree = false;
        for (long seed = 1; seed <= 60; seed++) {
            for (int idle : new int[] {0, 1, 2, 3, 5}) {
                for (String cash : List.of(CASH_HIGH, CASH_LOW)) {
                    List<GeneratedFeedRow> rows = rows(seed, idle, cash);
                    for (int i = 0; i < rows.size(); i++) {
                        GeneratedFeedRow row = rows.get(i);
                        FeedTemplate template = catalog.active(row.templateId()).orElseThrow();
                        assertThat(row.seq()).isEqualTo(FIRST_SEQ + i);
                        assertThat(row.kind()).isEqualTo(FeedKind.NUDGE);
                        assertThat(template.kind()).isEqualTo(FeedKind.NUDGE);
                        assertThat(row.direction()).isNull();
                        assertThat(row.repeatCount()).isZero();
                        assertThat(row.assetGroupId()).isNull();
                        assertThat(row.monthEventId()).isNull();
                        assertThat(row.categoryHidden()).isFalse();
                        assertThat(row.body().codePointCount(0, row.body().length())).isLessThanOrEqualTo(300);

                        JsonNode vars = JSON.readTree(row.templateVarsJson());
                        assertThat(new TreeSet<>(vars.propertyNames())).as(row.templateId()).isEqualTo(new TreeSet<>(template.placeholders()));
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (String name : template.placeholders()) {
                            values.put(name, vars.get(name).isString() ? vars.get(name).asString() : vars.get(name).decimalValue());
                        }
                        assertThat(renderer.render(template.text(), values)).isEqualTo(row.body());
                        assertThat(row.templateVarsJson()).isEqualTo(FeedVarsJson.canonical(values));

                        if (family(row) == Family.ASSET) {
                            assertThat(row.assetId()).isNotNull();
                            AssetFacts named = assets().stream().filter(a -> a.assetId() == row.assetId()).findFirst().orElseThrow();
                            assertThat(row.strength()).isEqualTo(FeedRules.grade(named.attentionRank(), named.attentionN()));
                            if (template.placeholders().contains("rank")) {
                                sawRank = true;
                                assertThat(vars.get("rank").decimalValue()).isEqualByComparingTo(
                                        BigDecimal.valueOf(listRank.get(named.assetId())));
                            } else if (!listRank.containsKey(named.assetId())) {
                                sawNoRank = true;
                            }
                        } else {
                            assertThat(row.assetId()).isNull();
                            assertThat(row.strength()).isNull();
                        }
                        if (family(row) == Family.FREE) {
                            sawFree = true;
                            assertThat(row.templateVarsJson()).isEqualTo("{}");
                        }
                    }
                }
            }
        }
        assertThat(sawRank).as("a rank sentence was produced").isTrue();
        assertThat(sawNoRank).as("an asset off both lists was named without a rank").isTrue();
        assertThat(sawFree).isTrue();
    }

    @Test
    void an_asset_that_is_on_neither_list_never_gets_a_sentence_with_a_rank() {
        for (long seed = 1; seed <= 200; seed++) {
            for (GeneratedFeedRow row : rows(seed, 3, CASH_LOW)) {
                if (row.assetId() != null && row.assetId() == 6L) {
                    assertThat(catalog.active(row.templateId()).orElseThrow().placeholders()).doesNotContain("rank");
                }
            }
        }
    }

    // ---- T-A6 refusals ----

    private static void assertInputRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(FeedGenerationException.class)
                .extracting(e -> ((FeedGenerationException) e).failure()).isEqualTo(FeedFailure.FEED_INPUT);
    }

    @Test
    void facts_of_another_turn_and_impossible_counts_are_refused_before_anything_is_generated() {
        BigDecimal cash = new BigDecimal(CASH_HIGH);
        assertInputRefused(() -> new AlertInput(1, TURN, assets(), new PlayerFacts(TURN + 1, cash, 0, 1)));
        assertInputRefused(() -> new AlertInput(1, TURN, List.of(asset(1, "A1", "가나").turn(TURN + 1).build()), player(0, CASH_HIGH)));
        assertInputRefused(() -> new AlertInput(1, 0, List.of(), new PlayerFacts(1, cash, 0, 0)));
        assertInputRefused(() -> new AlertInput(1, TURN, null, player(0, CASH_HIGH)));
        assertInputRefused(() -> new AlertInput(1, TURN, assets(), null));
        assertInputRefused(() -> new AlertInput(1, TURN, java.util.Arrays.asList(assets().get(0), null), player(0, CASH_HIGH)));
        assertInputRefused(() -> new AlertInput(1, TURN, List.of(assets().get(0), assets().get(0)), player(0, CASH_HIGH)));
        assertInputRefused(() -> new PlayerFacts(TURN, cash, 0, TURN + 1));
        assertInputRefused(() -> new PlayerFacts(TURN, cash, 2, 1));
        assertInputRefused(() -> new PlayerFacts(TURN, cash, -1, 0));
        assertInputRefused(() -> new PlayerFacts(TURN, cash, 0, -1));
        assertInputRefused(() -> new PlayerFacts(0, cash, 0, 0));
        assertInputRefused(() -> new PlayerFacts(TURN, new BigDecimal("0.5"), 0, 0));
        assertInputRefused(() -> new PlayerFacts(TURN, new BigDecimal("1.00000001"), 0, 0));
        assertInputRefused(() -> new PlayerFacts(TURN, new BigDecimal("-0.00000001"), 0, 0));
        assertThat(new PlayerFacts(TURN, cash, 0, TURN).idleTurns()).as("idle for every interval so far is allowed").isEqualTo(TURN);
        assertInputRefused(() -> generator.generate(new AlertInput(1, TURN, assets(), player(0, CASH_HIGH)), 0));
    }

    // ---- T-A7 nothing to pick ----

    @Test
    void without_asset_candidates_the_asset_slots_are_skipped_and_the_other_families_fill() {
        for (long seed = 1; seed <= 10; seed++) {
            assertThat(families(generator.generate(new AlertInput(seed, TURN, List.of(), player(3, CASH_HIGH)), 1)))
                    .containsExactly(Family.IDLE, Family.CASH, Family.FREE);
            assertThat(families(generator.generate(new AlertInput(seed, TURN, List.of(), player(0, CASH_LOW)), 1)))
                    .containsExactly(Family.FREE);
        }
    }

    @Test
    void a_catalog_without_nudge_templates_gives_an_empty_list_and_no_error(@TempDir Path dir) throws IOException {
        for (String file : List.of("kinds.csv", "variables.csv", "templates.csv", "denylist.csv", "notices.csv")) {
            Path source = Path.of("feed", file);
            if (file.equals("templates.csv")) {
                List<String> kept = new ArrayList<>();
                for (String line : Files.readAllLines(source)) {
                    if (!line.startsWith("nudge.")) {
                        kept.add(line);
                    }
                }
                Files.write(dir.resolve(file), kept);
            } else {
                Files.copy(source, dir.resolve(file));
            }
        }
        AlertGenerator bare = new AlertGenerator(FeedTemplateCatalog.fromDirectory(dir));
        assertThat(bare.generate(new AlertInput(1, TURN, assets(), player(3, CASH_HIGH)), 1)).isEmpty();
    }

    // ---- attention weight of the asset draw ----

    @Test
    void the_first_asset_is_the_candidate_with_the_smallest_ticket_where_a_higher_attention_grade_buys_more_tickets() {
        List<AssetFacts> pool = assets();
        RankingBoard board = RankingBoardCalculator.board(new RankingInput(TURN, pool));
        Set<Long> ids = new LinkedHashSet<>();
        board.gainers().stream().limit(AlertRules.CANDIDATES_PER_LIST).forEach(r -> ids.add(r.assetId()));
        board.losers().stream().limit(AlertRules.CANDIDATES_PER_LIST).forEach(r -> ids.add(r.assetId()));
        board.attention().stream().limit(AlertRules.CANDIDATES_PER_LIST).forEach(r -> ids.add(r.assetId()));
        Set<Long> firsts = new HashSet<>();
        long topAttentionAsset = pool.stream().filter(a -> a.attentionRank() != null && a.attentionRank() == 1).findFirst().orElseThrow()
                .assetId();
        int topWins = 0;
        for (long seed = 1; seed <= 300; seed++) {
            long bestTicket = Long.MAX_VALUE;
            String bestRef = null;
            long bestId = -1;
            for (AssetFacts a : pool) {
                if (!ids.contains(a.assetId())) {
                    continue;
                }
                Integer grade = FeedRules.grade(a.attentionRank(), a.attentionN());
                int weight = Math.max(1, grade == null ? 1 : grade);
                String ref = a.market() + ":" + a.ticker();
                long ticket = Long.MAX_VALUE;
                for (int i = 1; i <= weight; i++) {
                    ticket = Math.min(ticket, AlertRules.pick(seed, TURN, "alert-pick", "asset:" + ref + ":" + i));
                }
                if (ticket < bestTicket || (ticket == bestTicket && FeedHash.compareBytes(ref, bestRef) < 0)) {
                    bestTicket = ticket;
                    bestRef = ref;
                    bestId = a.assetId();
                }
            }
            long first = rows(seed, 0, CASH_HIGH).get(0).assetId();
            assertThat(first).as("seed " + seed + ": the asset of the smallest weighted ticket").isEqualTo(bestId);
            firsts.add(first);
            if (first == topAttentionAsset) {
                topWins++;
            }
        }
        assertThat(firsts.size()).as("the draw is not constant").isGreaterThan(2);
        // grade 3 holds three tickets, a grade 1 or 2 asset one or two of them
        assertThat(topWins).as("the top-attention asset wins more than a fair share").isGreaterThan(300 / ids.size());
    }

    // ---- T-A10 text safety of what is generated ----

    @Test
    void generated_bodies_hold_no_year_no_date_no_denied_word_and_name_only_the_chosen_asset() {
        List<AssetFacts> pool = assets();
        for (long seed = 1; seed <= 40; seed++) {
            for (GeneratedFeedRow row : rows(seed, 3, CASH_HIGH)) {
                String body = row.body();
                assertThat(body).doesNotContainPattern("\\d{4}");
                assertThat(body).doesNotContain("년").doesNotContain("월");
                for (String word : catalog.denyWords()) {
                    assertThat(body).doesNotContain(word);
                }
                String rest = body;
                if (row.assetId() != null) {
                    String chosen = pool.stream().filter(a -> a.assetId() == row.assetId()).findFirst().orElseThrow().name();
                    rest = body.replace(chosen, "");
                }
                for (AssetFacts a : pool) {
                    assertThat(rest).as("an asset name outside the name variable").doesNotContain(a.name());
                }
            }
        }
    }
}

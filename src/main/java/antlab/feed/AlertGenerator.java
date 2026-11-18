package antlab.feed;

import antlab.feed.AlertRules.Family;
import antlab.ranking.RankingBoard;
import antlab.ranking.RankingBoardCalculator;
import antlab.ranking.RankingInput;
import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nudge alerts of one turn (rule version {@code mvp-12:v1}). The same input gives the same rows, in the same order, with the same text;
 * the only data it reads is {@link AlertInput}. No clock, no randomness of its own, no database.
 *
 * <p>The number of alerts is {@code min(4, 1 + idle intervals)}. Candidate slots are tried in a fixed order — an asset, the idle
 * reminder, the cash reminder, a free sentence, a second asset — and the first slots that are eligible and have a template are kept, so
 * a quieter player gets more rows and the earlier rows never change. Assets come from the top of the gainers, losers and attention lists
 * of the ranking board of that turn, drawn by attention grade.
 */
public final class AlertGenerator {

    private static final String USAGE_PICK = "alert-pick";
    private static final String USAGE_TEMPLATE = "alert-tpl";

    private final FeedTemplateCatalog catalog;
    private final FeedRenderer renderer;

    public AlertGenerator(FeedTemplateCatalog catalog) {
        this.catalog = catalog;
        this.renderer = new FeedRenderer(catalog.variables());
    }

    /** An asset that may be named by an alert, with its drawn ticket (smaller comes first). */
    private record Candidate(AssetFacts asset, String ref, Integer strength, Map<String, Object> values, List<FeedTemplate> pool,
            long ticket) {
    }

    /** A slot that can be filled: the key that drives the template pick, its templates, their values and the asset (null for none). */
    private record Slot(String key, List<FeedTemplate> pool, Map<String, Object> values, AssetFacts asset, Integer strength) {
    }

    /**
     * @param firstSeq {@code seq} of the first alert row, the number after the last feed row of the turn
     * @return 1 to {@link AlertRules#MAX_ALERTS} rows in display order; empty when nothing is eligible
     */
    public List<GeneratedFeedRow> generate(AlertInput input, int firstSeq) {
        if (firstSeq < 1) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "the first alert number is out of range");
        }
        RankingBoard board = RankingBoardCalculator.board(new RankingInput(input.turnNo(), input.assets()));
        List<Candidate> assets = assetCandidates(input, board);
        PlayerFacts player = input.player();

        List<Slot> slots = new ArrayList<>();
        addIfPresent(slots, assets.size() >= 1 ? assetSlot(assets.get(0)) : null);
        if (player.idleTurns() >= 1) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("idle_turns", BigDecimal.valueOf(player.idleTurns()));
            values.put("trade_count", BigDecimal.valueOf(player.tradeCount()));
            addIfPresent(slots, familySlot("idle", Family.IDLE, AlertRules.IDLE_SLOTS, values));
        }
        if (player.cashWeight() != null && player.cashWeight().compareTo(AlertRules.CASH_NUDGE_MIN) >= 0) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("cash_weight", player.cashWeight());
            addIfPresent(slots, familySlot("cash", Family.CASH, AlertRules.CASH_SLOTS, values));
        }
        addIfPresent(slots, familySlot("free", Family.FREE, Set.of(), Map.of()));
        addIfPresent(slots, assets.size() >= 2 ? assetSlot(assets.get(1)) : null);

        int wanted = Math.min(AlertRules.count(player.idleTurns()), slots.size());
        List<GeneratedFeedRow> rows = new ArrayList<>();
        for (int i = 0; i < wanted; i++) {
            rows.add(row(input, slots.get(i), firstSeq + i));
        }
        return List.copyOf(rows);
    }

    private static void addIfPresent(List<Slot> slots, Slot slot) {
        if (slot != null) {
            slots.add(slot);
        }
    }

    private Slot familySlot(String key, Family family, Set<String> offered, Map<String, Object> values) {
        List<FeedTemplate> pool = catalog.pool(FeedKind.NUDGE, "NONE", null, null, offered, false, null).stream()
                .filter(t -> AlertRules.familyOf(t).orElse(null) == family).toList();
        return pool.isEmpty() ? null : new Slot(key, pool, values, null, null);
    }

    private Slot assetSlot(Candidate candidate) {
        return new Slot("asset:" + candidate.ref(), candidate.pool(), candidate.values(), candidate.asset(), candidate.strength());
    }

    /** Top assets of the gainers, losers and attention lists, deduplicated, drawn by attention grade; those without a template drop out. */
    private List<Candidate> assetCandidates(AlertInput input, RankingBoard board) {
        Map<Long, AssetFacts> byId = new HashMap<>();
        for (AssetFacts a : input.assets()) {
            byId.put(a.assetId(), a);
        }
        Map<Long, Integer> listRank = new HashMap<>();
        board.gainers().forEach(r -> listRank.put(r.assetId(), r.rank()));
        board.losers().forEach(r -> listRank.put(r.assetId(), r.rank()));

        Set<Long> ids = new LinkedHashSet<>();
        board.gainers().stream().limit(AlertRules.CANDIDATES_PER_LIST).forEach(r -> ids.add(r.assetId()));
        board.losers().stream().limit(AlertRules.CANDIDATES_PER_LIST).forEach(r -> ids.add(r.assetId()));
        board.attention().stream().limit(AlertRules.CANDIDATES_PER_LIST).forEach(r -> ids.add(r.assetId()));

        List<Candidate> out = new ArrayList<>();
        for (long id : ids) {
            AssetFacts a = byId.get(id);
            String ref = a.market() + ":" + a.ticker();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("asset_name", a.name());
            values.put("ret", a.ret());
            if (listRank.containsKey(id)) {
                values.put("rank", BigDecimal.valueOf(listRank.get(id)));
            }
            List<FeedTemplate> pool = catalog.pool(FeedKind.NUDGE, "ASSET", null, null, values.keySet(), false, null).stream()
                    .filter(t -> AlertRules.familyOf(t).orElse(null) == Family.ASSET).toList();
            if (pool.isEmpty()) {
                continue;
            }
            Integer strength = FeedRules.grade(a.attentionRank(), a.attentionN());
            int weight = Math.max(1, strength == null ? 1 : strength);
            long smallest = Long.MAX_VALUE;
            for (int i = 1; i <= weight; i++) {
                smallest = Math.min(smallest, AlertRules.pick(input.seed(), input.turnNo(), USAGE_PICK, "asset:" + ref + ":" + i));
            }
            out.add(new Candidate(a, ref, strength, values, pool, smallest));
        }
        out.sort(Comparator.comparingLong(Candidate::ticket).thenComparing(Candidate::ref, FeedHash::compareBytes));
        return out;
    }

    private GeneratedFeedRow row(AlertInput input, Slot slot, int seq) {
        FeedTemplate template = slot.pool()
                .get((int) (AlertRules.pick(input.seed(), input.turnNo(), USAGE_TEMPLATE, slot.key()) % slot.pool().size()));
        Map<String, Object> vars = new LinkedHashMap<>();
        for (String name : template.placeholders()) {
            vars.put(name, slot.values().get(name));
        }
        String body = renderer.render(template.text(), vars);
        return new GeneratedFeedRow(seq, FeedKind.NUDGE, slot.asset() == null ? null : slot.asset().assetId(), null, null, false, null,
                slot.strength(), 0, template.id(), FeedVarsJson.canonical(vars), body);
    }
}

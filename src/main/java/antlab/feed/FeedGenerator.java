package antlab.feed;

import antlab.common.money.MoneyMath;
import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure port of {@code tf_generate} (rule version {@code p0-18:v2}) from {@code db/24_turn_feed_def.sql}: the same input gives the same
 * rows, in the same order, with the same text. The only data it reads is {@link FeedInput}; it has no clock, no randomness of its own
 * and no database. Candidates are events (all of them), asset and group rises and falls, spreads, echoes and fades; noise candidates
 * without a usable template are dropped, an event without one is an error.
 */
public final class FeedGenerator {

    private static final String USAGE_COUNT = "count";
    private static final String USAGE_PICK = "pick";
    private static final String USAGE_TEMPLATE = "tpl";
    private static final String USAGE_ORDER = "order";

    private final FeedTemplateCatalog catalog;
    private final FeedRenderer renderer;

    public FeedGenerator(FeedTemplateCatalog catalog) {
        this.catalog = catalog;
        this.renderer = new FeedRenderer(catalog.variables());
    }

    /** A candidate story with the templates it may use; {@code weight} is the number of pick tickets. */
    private record Candidate(FeedKind kind, Long assetId, Long groupId, Long eventId, boolean hidden, MoveDirection direction,
            Integer strength, int weight, int repeat, String key, Map<String, Object> vars, List<FeedTemplate> pool) {
    }

    private record Ticket(Candidate candidate, int round, long ticket) {
    }

    /** A rendered row before the final order is known. */
    private record Built(long sortKey, Candidate candidate, int round, FeedTemplate template, String varsJson, String body) {
    }

    private record GroupFacts(long id, String code, String name, BigDecimal ret, BigDecimal attention, BigDecimal prevAttention,
            Integer rank, int ranked) {
    }

    public List<GeneratedFeedRow> generate(FeedInput input) {
        int target = FeedRules.MIN_ROWS + (int) (FeedHash.pick(input.seed(), input.turnNo(), USAGE_COUNT, "") % FeedRules.ROW_SPAN);
        List<Candidate> events = new ArrayList<>();
        List<Candidate> noise = new ArrayList<>();
        for (EventFacts e : input.events()) {
            events.add(eventCandidate(e));
        }
        collectNoise(input, noise);
        if (events.isEmpty() && noise.isEmpty()) {
            return List.of();
        }
        events.sort(Comparator.comparing(Candidate::key, FeedHash::compareBytes));
        List<Built> built = new ArrayList<>();
        for (Candidate event : events) {
            built.add(build(input, event, 1));
        }
        for (Ticket pick : pickNoise(input, noise, target, Math.max(0, target - events.size()))) {
            built.add(build(input, pick.candidate(), pick.round()));
        }
        built.sort(Comparator.comparingLong(Built::sortKey).thenComparing(b -> b.candidate().kind().name())
                .thenComparing(b -> b.candidate().key(), FeedHash::compareBytes).thenComparingInt(Built::round));
        if (built.size() > FeedRules.MAX_ROWS) {
            throw new FeedGenerationException(FeedFailure.FEED_RENDER, "more than " + FeedRules.MAX_ROWS + " rows");
        }
        List<GeneratedFeedRow> rows = new ArrayList<>();
        for (int i = 0; i < built.size(); i++) {
            Built b = built.get(i);
            Candidate c = b.candidate();
            boolean event = c.kind() == FeedKind.EVENT;
            rows.add(new GeneratedFeedRow(i + 1, c.kind(), c.assetId(), c.groupId(), c.eventId(), event && c.hidden(), c.direction(),
                    c.strength(), c.repeat(), b.template().id(), b.varsJson(), b.body()));
        }
        return List.copyOf(rows);
    }

    private Candidate eventCandidate(EventFacts e) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("reaction", e.reaction());
        List<FeedTemplate> pool = catalog.pool(FeedKind.EVENT, "NONE", null, null, vars.keySet(), e.hidden(), e.category());
        return new Candidate(FeedKind.EVENT, null, null, e.monthEventId(), e.hidden(), null, e.strength(), 1, 0,
                "event:" + e.eventKey(), vars, pool);
    }

    private void collectNoise(FeedInput input, List<Candidate> noise) {
        for (AssetFacts a : input.assets()) {
            String ref = a.market() + ":" + a.ticker();
            Integer grade = FeedRules.grade(a.attentionRank(), a.attentionN());
            if (a.ret().signum() != 0) {
                boolean up = a.ret().signum() > 0;
                MoveDirection direction = up ? MoveDirection.UP : MoveDirection.DOWN;
                add(noise, up ? FeedKind.RISE : FeedKind.FALL, "ASSET", a.assetId(), null, direction, grade, 0, "asset:" + ref,
                        assetVars(a, false), percent(a.ret()));
                if (a.prevRet() != null && a.prevRet().signum() == a.ret().signum() && a.prevStoryDirection() == direction
                        && !(a.attention() != null && a.prevAttention() != null && a.attention().compareTo(a.prevAttention()) < 0)) {
                    add(noise, FeedKind.ECHO, "ASSET", a.assetId(), null, direction, grade, a.prevEchoRepeat(), "echo:" + ref,
                            assetVars(a, true), percent(a.ret()));
                }
            }
            if (a.attention() != null && a.prevAttention() != null && a.attention().compareTo(a.prevAttention()) < 0
                    && a.prevAttentionRank() != null && a.prevAttentionRank() <= FeedRules.FADE_TOP && a.focusName() != null
                    && !a.focusName().equals(a.name())) {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("asset_name", a.name());
                vars.put("focus_name", a.focusName());
                if (a.attentionRank() != null) {
                    vars.put("attention_rank", BigDecimal.valueOf(a.attentionRank()));
                }
                add(noise, FeedKind.FADE, "ASSET", a.assetId(), null, null, grade, 0, "fade:" + ref, vars, null);
            }
        }
        for (GroupFacts g : groups(input)) {
            if (g.ret().signum() == 0) {
                continue;
            }
            boolean up = g.ret().signum() > 0;
            MoveDirection direction = up ? MoveDirection.UP : MoveDirection.DOWN;
            Integer grade = FeedRules.grade(g.rank(), g.ranked());
            add(noise, up ? FeedKind.RISE : FeedKind.FALL, "GROUP", null, g.id(), direction, grade, 0, "group:" + g.code(),
                    groupVars(g), percent(g.ret()));
            if (g.attention() != null && g.prevAttention() != null && g.attention().compareTo(g.prevAttention()) > 0) {
                add(noise, FeedKind.SPREAD, "GROUP", null, g.id(), direction, grade, 0, "spread:" + g.code(), groupVars(g),
                        percent(g.ret()));
            }
        }
    }

    private void add(List<Candidate> noise, FeedKind kind, String target, Long assetId, Long groupId, MoveDirection direction,
            Integer strength, int repeat, String key, Map<String, Object> vars, Integer r) {
        List<FeedTemplate> pool = catalog.pool(kind, target, direction, r, vars.keySet(), false, null);
        if (!pool.isEmpty()) {
            int weight = Math.max(1, strength == null ? 1 : strength);
            noise.add(new Candidate(kind, assetId, groupId, null, false, direction, strength, weight, repeat, key, vars, pool));
        }
    }

    private static Map<String, Object> assetVars(AssetFacts a, boolean echo) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("asset_name", a.name());
        vars.put("group_name", a.groupName());
        vars.put("ret", a.ret());
        if (a.prevRet() != null) {
            vars.put("prev_ret", a.prevRet());
        }
        if (a.streak() >= 2) {
            vars.put("streak", BigDecimal.valueOf(a.streak()));
        }
        if (!echo && a.attentionRank() != null) {
            vars.put("attention_rank", BigDecimal.valueOf(a.attentionRank()));
        }
        if (echo) {
            vars.put("repeat", BigDecimal.valueOf(a.prevEchoRepeat()));
        }
        return vars;
    }

    private static Map<String, Object> groupVars(GroupFacts g) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("group_name", g.name());
        vars.put("group_ret", g.ret());
        return vars;
    }

    /** Whole percent of a ratio, as the pool's r = 0 rule sees it. */
    private static Integer percent(BigDecimal ratio) {
        try {
            return MoneyMath.percentPoints(ratio).intValueExact();
        } catch (ArithmeticException e) {
            throw new FeedGenerationException(FeedFailure.FEED_RENDER, "percent out of range");
        }
    }

    /** Group figures as {@code tf_input} derives them: mean return rounded once to 8 digits, mean attention, rank among groups. */
    private static List<GroupFacts> groups(FeedInput input) {
        Map<Long, List<AssetFacts>> byGroup = new LinkedHashMap<>();
        for (AssetFacts a : input.assets()) {
            byGroup.computeIfAbsent(a.groupId(), k -> new ArrayList<>()).add(a);
        }
        List<GroupFacts> raw = new ArrayList<>();
        for (Map.Entry<Long, List<AssetFacts>> entry : byGroup.entrySet()) {
            List<AssetFacts> members = entry.getValue();
            List<BigDecimal> rets = members.stream().map(AssetFacts::ret).toList();
            List<BigDecimal> attentions = members.stream().map(AssetFacts::attention).filter(x -> x != null).toList();
            List<BigDecimal> previous = members.stream().map(AssetFacts::prevAttention).filter(x -> x != null).toList();
            String name = members.stream().map(AssetFacts::groupName).max(String::compareTo).orElseThrow();
            raw.add(new GroupFacts(entry.getKey(), members.get(0).groupCode(), name, MoneyMath.round8(MoneyMath.pgAvg(rets)),
                    MoneyMath.pgAvg(attentions), MoneyMath.pgAvg(previous), null, 0));
        }
        int ranked = (int) raw.stream().filter(g -> g.attention() != null).count();
        List<GroupFacts> out = new ArrayList<>();
        for (GroupFacts g : raw) {
            Integer rank = g.attention() == null ? null
                    : 1 + (int) raw.stream().filter(o -> o.attention() != null && o.attention().compareTo(g.attention()) > 0).count();
            out.add(new GroupFacts(g.id(), g.code(), g.name(), g.ret(), g.attention(), g.prevAttention(), rank, ranked));
        }
        return out;
    }

    /**
     * Weighted draw without replacement: each candidate holds {@code weight} tickets per round and its smallest ticket is its key; a round
     * is used up before the next starts. Order is (round, key, candidate key), cut at {@code limit}.
     */
    private static List<Ticket> pickNoise(FeedInput input, List<Candidate> noise, int rounds, int limit) {
        List<Ticket> picked = new ArrayList<>();
        for (int round = 1; round <= rounds && picked.size() < limit && !noise.isEmpty(); round++) {
            List<Ticket> tickets = new ArrayList<>();
            for (Candidate c : noise) {
                long smallest = Long.MAX_VALUE;
                for (int i = 1; i <= c.weight(); i++) {
                    smallest = Math.min(smallest, FeedHash.pick(input.seed(), input.turnNo(), USAGE_PICK, c.key() + ":" + round + ":" + i));
                }
                tickets.add(new Ticket(c, round, smallest));
            }
            tickets.sort(Comparator.comparingLong(Ticket::ticket).thenComparing(t -> t.candidate().key(), FeedHash::compareBytes));
            for (Ticket t : tickets) {
                if (picked.size() == limit) {
                    break;
                }
                picked.add(t);
            }
        }
        return picked;
    }

    private Built build(FeedInput input, Candidate c, int round) {
        if (c.pool().isEmpty()) {
            throw new FeedGenerationException(FeedFailure.FEED_RENDER, "an event has no usable template");
        }
        String key = c.key() + ":" + round;
        FeedTemplate template = c.pool().get((int) (FeedHash.pick(input.seed(), input.turnNo(), USAGE_TEMPLATE, key) % c.pool().size()));
        Map<String, Object> vars = new LinkedHashMap<>();
        for (String slot : template.placeholders()) {
            vars.put(slot, c.vars().get(slot));
        }
        if (vars.isEmpty()) {
            // jsonb_object_agg over no slots is NULL in the SQL reference, and tf_render rejects a NULL variable object.
            throw new FeedGenerationException(FeedFailure.FEED_RENDER, "a chosen template has no slot");
        }
        String body = renderer.render(template.text(), vars);
        return new Built(FeedHash.pick(input.seed(), input.turnNo(), USAGE_ORDER, key), c, round, template, FeedVarsJson.canonical(vars),
                body);
    }
}

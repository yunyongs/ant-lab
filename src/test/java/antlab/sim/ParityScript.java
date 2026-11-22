package antlab.sim;

import antlab.account.OrderRejection;
import antlab.common.error.ErrorCode;
import antlab.season.FormationRejection;
import antlab.sim.SimMoneyVectorsTest.SplitMix64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The seeded player of one parity season. It sends every unit through an {@link Arena} (the REST API in the real test) and
 * writes the {@code sim-decisions/1} list the Python simulator will replay.
 *
 * <p>The verdict recorded as {@code expect} is what the script <em>intended</em> (derived from what it sent), never read back
 * from the HTTP answer. The answer is only a cross-check: a unit meant to be accepted must come back 2xx, and a refusal must
 * come back with the human error code of that refusal. A disagreement is a script bug and fails the run.
 *
 * <p>Rules the script keeps so the two paths cannot disagree: a turn-0 formation change never carries bad shares or a bad
 * reason (REST answers 400 before the turn rule), only an asset on the ranking board is sent as a RANKING source, and a feed
 * reference is always an item already shown at or before the current turn.
 */
final class ParityScript {

    static final String OUTSIDE = "KR:999999";
    static final String[] POSITIONS = {"GOALKEEPER", "DEFENDER", "MIDFIELDER", "FORWARD_TARGET", "FORWARD_BREAKOUT",
            "FORWARD_DEFENSIVE"};

    /** One season of the plan: the master seed table gives {@code seed} (the season) and {@code scriptSeed} (the player). */
    record Spec(int index, int pathLength, int abandonAt, long scriptSeed) {
    }

    /** A feed item or alert shown so far; {@code assetKey} is null when it names no single asset. */
    record FeedRef(int turn, int seq, String assetKey, boolean alert) {
    }

    /** The HTTP answer reduced to what the cross-check needs: the status and the problem {@code code} ("" when 2xx). */
    record Reply(int status, String code) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    interface Arena {
        List<String> pool();

        long cash();

        Map<String, Integer> held();

        Map<String, String> openPositions();

        int[] formation();

        Map<String, Long> prices();

        List<FeedRef> feed();

        Set<String> board();

        Reply order(int turn, String asset, String side, int quantity, String position, ObjectNode source);

        Reply formation(int turn, int[] shares, String reason);

        Reply lineup(int turn, String asset, String position);

        void advance(int turn);

        void abandon(int turn);

        void snapshot(int turn);
    }

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final Spec spec;
    private final Arena arena;
    private final SplitMix64 rng;
    private final ArrayNode turns = MAPPER.createArrayNode();
    private ArrayNode units;
    private int turn;

    ParityScript(Spec spec, Arena arena) {
        this.spec = spec;
        this.arena = arena;
        this.rng = new SplitMix64(spec.scriptSeed());
    }

    /** Plays the whole season and returns its decision list. */
    ObjectNode play() {
        for (turn = 0; turn < spec.pathLength(); turn++) {
            units = MAPPER.createArrayNode();
            if (turn == 0) {
                buildTeam();
            } else {
                int count = rng.below(5);
                for (int k = 0; k < count; k++) {
                    oneUnit();
                }
            }
            arena.snapshot(turn);
            if (turn == spec.abandonAt()) {
                ObjectNode entry = turns.addObject();
                entry.put("turn", turn);
                entry.set("units", units);
                entry.put("abandon", true);
                arena.abandon(turn);
                break;
            }
            if (units.size() > 0) {
                ObjectNode entry = turns.addObject();
                entry.put("turn", turn);
                entry.set("units", units);
            }
            arena.advance(turn);
        }
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("schema", "sim-decisions/1");
        doc.set("turns", turns);
        return doc;
    }

    // ---- turn 0 -------------------------------------------------------------------------------------------------

    private void buildTeam() {
        int wanted = 6 + rng.below(5);
        List<String> order = shuffled(arena.pool());
        // A refusal at turn 0 changes nothing, so it can sit anywhere in the team building.
        if (rng.chance(40)) {
            lineupAtTurnZero(order.get(0));
        }
        if (rng.chance(25)) {
            formationAtTurnZero();
        }
        int bought = 0;
        for (String asset : order) {
            if (bought >= wanted) {
                break;
            }
            long budget = arena.cash() * (4 + rng.below(8)) / 100;
            int quantity = affordable(asset, budget);
            if (quantity < 1) {
                continue;
            }
            if (rng.chance(8)) {
                missingPosition(asset, quantity);
            }
            buy(asset, quantity, POSITIONS[rng.below(POSITIONS.length)], direct());
            bought++;
        }
        turnZeroSells();
    }

    /** Turn-0 sells are allowed: they change the team fee and the start team the percentile request freezes. */
    private void turnZeroSells() {
        if (rng.chance(40)) {
            String asset = pickHeld();
            int held = asset == null ? 0 : arena.held().get(asset);
            if (held >= 2) {
                sellOrder(asset, 1 + rng.below(held - 1), direct());
            }
        }
        if (rng.chance(35)) {
            // Buy and sell out completely: the asset must leave the start team.
            for (String asset : shuffled(notHeld())) {
                int quantity = affordable(asset, arena.cash() * 3 / 100);
                if (quantity >= 1) {
                    buy(asset, quantity, POSITIONS[rng.below(POSITIONS.length)], direct());
                    sellOrder(asset, quantity, direct());
                    break;
                }
            }
        }
    }

    private void lineupAtTurnZero(String asset) {
        lineup(asset, POSITIONS[rng.below(POSITIONS.length)], ErrorCode.TURN_MISMATCH.name());
    }

    private void formationAtTurnZero() {
        int[] shares = randomFormation();
        formation(shares, null, FormationRejection.TURN_ZERO_FORMATION.name());
    }

    private void missingPosition(String asset, int quantity) {
        order(asset, "BUY", quantity, null, direct(), OrderRejection.POSITION_REQUIRED.name());
    }

    // ---- turns 1.. ----------------------------------------------------------------------------------------------

    private void oneUnit() {
        int roll = rng.below(100);
        if (roll < 10) {
            invalidUnit();
        } else if (roll < 30) {
            buyNew();
        } else if (roll < 40) {
            buyMore();
        } else if (roll < 55) {
            sell(false);
        } else if (roll < 62) {
            sell(true);
        } else if (roll < 70) {
            validFormation();
        } else if (roll < 77) {
            validLineup();
        } else {
            sourced();
        }
    }

    private void buyNew() {
        List<String> candidates = new ArrayList<>();
        for (String asset : arena.pool()) {
            if (!arena.held().containsKey(asset)) {
                candidates.add(asset);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        String asset = candidates.get(rng.below(candidates.size()));
        int quantity = affordable(asset, arena.cash() * (3 + rng.below(10)) / 100);
        if (quantity >= 1) {
            buy(asset, quantity, POSITIONS[rng.below(POSITIONS.length)], direct());
        }
    }

    private void buyMore() {
        String asset = pickHeld();
        if (asset == null) {
            return;
        }
        int quantity = affordable(asset, arena.cash() * (2 + rng.below(8)) / 100);
        if (quantity >= 1) {
            // A held asset may repeat its position or leave it out.
            buy(asset, quantity, rng.chance(50) ? arena.openPositions().get(asset) : null, direct());
        }
    }

    private void sell(boolean everything) {
        String asset = pickHeld();
        if (asset == null) {
            return;
        }
        int held = arena.held().get(asset);
        int quantity = everything || held == 1 ? held : 1 + rng.below(held - 1);
        sellOrder(asset, quantity, direct());
    }

    private void validFormation() {
        int[] shares = randomFormation();
        if (java.util.Arrays.equals(shares, arena.formation())) {
            return;
        }
        formation(shares, rng.chance(30) ? "rebalance " + turn : null, "ACCEPTED");
    }

    private void validLineup() {
        String asset = pickHeld();
        if (asset == null) {
            return;
        }
        // Sometimes the same position again: an accepted no-op.
        String position = rng.chance(20) ? arena.openPositions().get(asset) : POSITIONS[rng.below(POSITIONS.length)];
        lineup(asset, position, "ACCEPTED");
    }

    /** An order that starts from a ranking board entry, a feed item or an alert the player can see. */
    private void sourced() {
        List<Object> options = new ArrayList<>();
        for (String asset : shuffled(new ArrayList<>(arena.board()))) {
            options.add(asset);
        }
        for (FeedRef item : arena.feed()) {
            options.add(item);
        }
        if (options.isEmpty()) {
            return;
        }
        Object pick = options.get(rng.below(options.size()));
        String asset;
        ObjectNode source = MAPPER.createObjectNode();
        if (pick instanceof FeedRef item) {
            source.put("kind", item.alert() ? "ALERT" : "FEED");
            source.put("feedTurn", item.turn());
            source.put("feedSeq", item.seq());
            asset = item.assetKey() != null ? item.assetKey() : arena.pool().get(rng.below(arena.pool().size()));
        } else {
            source.put("kind", "RANKING");
            asset = (String) pick;
        }
        int held = arena.held().getOrDefault(asset, 0);
        if (held > 0 && rng.chance(45)) {
            sellOrder(asset, 1 + rng.below(held), source);
            return;
        }
        int quantity = affordable(asset, arena.cash() * (2 + rng.below(8)) / 100);
        if (quantity >= 1) {
            String position = held > 0 ? null : POSITIONS[rng.below(POSITIONS.length)];
            buy(asset, quantity, position, source);
        }
    }

    // ---- units meant to be refused ------------------------------------------------------------------------------

    private void invalidUnit() {
        switch (rng.below(13)) {
            case 0 -> {
                // More than the cash buys; the cash check comes before the position check, so a missing position hides behind it.
                String asset = arena.pool().get(rng.below(arena.pool().size()));
                int quantity = (int) (arena.cash() / arena.prices().get(asset)) + 1 + rng.below(5);
                String position = arena.held().containsKey(asset) || rng.chance(50) ? positionFor(asset) : null;
                order(asset, "BUY", quantity, position, direct(), OrderRejection.INSUFFICIENT_CASH.name());
            }
            case 1 -> {
                String asset = pickHeld();
                if (asset != null) {
                    sellRefused(asset, arena.held().get(asset) + 1 + rng.below(3), OrderRejection.INSUFFICIENT_HOLDING);
                }
            }
            case 2 -> {
                List<String> free = notHeld();
                if (!free.isEmpty()) {
                    sellRefused(free.get(rng.below(free.size())), 1 + rng.below(5), OrderRejection.INSUFFICIENT_HOLDING);
                }
            }
            case 3 -> {
                List<String> free = notHeld();
                if (!free.isEmpty()) {
                    String asset = free.get(rng.below(free.size()));
                    int quantity = affordable(asset, arena.cash() * 3 / 100);
                    if (quantity >= 1) {
                        order(asset, "BUY", quantity, null, direct(), OrderRejection.POSITION_REQUIRED.name());
                    }
                }
            }
            case 4 -> {
                String asset = pickHeld();
                if (asset != null) {
                    int quantity = affordable(asset, arena.cash() * 3 / 100);
                    if (quantity >= 1) {
                        order(asset, "BUY", quantity, otherPosition(arena.openPositions().get(asset)), direct(),
                                OrderRejection.POSITION_CONFLICT.name());
                    }
                }
            }
            case 5 -> order(arena.pool().get(rng.below(arena.pool().size())), "BUY", 0, "DEFENDER", direct(),
                    OrderRejection.QUANTITY_INVALID.name());
            case 6 -> order(OUTSIDE, "BUY", 1 + rng.below(3), "DEFENDER", direct(), OrderRejection.ASSET_NOT_IN_POOL.name());
            case 7 -> {
                ObjectNode bench = MAPPER.createObjectNode();
                bench.put("kind", "BENCH");
                String asset = arena.pool().get(rng.below(arena.pool().size()));
                order(asset, "BUY", 1, positionFor(asset), bench, OrderRejection.SOURCE_NOT_ALLOWED.name());
            }
            case 8 -> formation(arena.formation().clone(), null, FormationRejection.FORMATION_UNCHANGED.name());
            case 9 -> {
                int[] shares = randomFormation();
                shares[1] += 1 + rng.below(3);
                formation(shares, null, FormationRejection.FORMATION_INVALID.name());
            }
            case 10 -> {
                int[] shares = randomFormation();
                if (java.util.Arrays.equals(shares, arena.formation())) {
                    shares[1] += 1;
                    shares[2] -= 1;
                }
                if (shares[2] >= 0) {
                    formation(shares, "x".repeat(201 + rng.below(20)), FormationRejection.FORMATION_INVALID.name());
                }
            }
            case 11 -> {
                // A new asset with no position and more shares than the cash buys: the cash check answers first.
                List<String> free = notHeld();
                if (!free.isEmpty()) {
                    String asset = free.get(rng.below(free.size()));
                    int quantity = (int) (arena.cash() / arena.prices().get(asset)) + 1 + rng.below(5);
                    order(asset, "BUY", quantity, null, direct(), OrderRejection.INSUFFICIENT_CASH.name());
                }
            }
            default -> {
                List<String> free = notHeld();
                if (!free.isEmpty()) {
                    lineup(free.get(rng.below(free.size())), "DEFENDER", ErrorCode.INSUFFICIENT_HOLDING.name());
                }
            }
        }
    }

    // ---- sending ------------------------------------------------------------------------------------------------

    private void buy(String asset, int quantity, String position, ObjectNode source) {
        order(asset, "BUY", quantity, position, source, "ACCEPTED");
    }

    private void sellOrder(String asset, int quantity, ObjectNode source) {
        order(asset, "SELL", quantity, null, source, "ACCEPTED");
    }

    private void sellRefused(String asset, int quantity, OrderRejection why) {
        order(asset, "SELL", quantity, null, direct(), why.name());
    }

    private void order(String asset, String side, int quantity, String position, ObjectNode source, String expect) {
        Reply reply = arena.order(turn, asset, side, quantity, position, source);
        String human = "ACCEPTED".equals(expect) ? null : OrderRejection.valueOf(expect).humanCode().name();
        crossCheck("order " + side + " " + asset + " x" + quantity, expect, human, reply);
        ObjectNode unit = units.addObject();
        unit.put("kind", "ORDER");
        unit.put("asset", asset);
        unit.put("side", side);
        unit.put("quantity", quantity);
        if (position != null) {
            unit.put("position", position);
        }
        if (!"DIRECT".equals(source.get("kind").asString())) {
            unit.set("source", source);
        }
        unit.put("expect", expect);
    }

    private void formation(int[] shares, String reason, String expect) {
        Reply reply = arena.formation(turn, shares, reason);
        String human = "ACCEPTED".equals(expect) ? null : FormationRejection.valueOf(expect).humanCode().name();
        crossCheck("formation " + java.util.Arrays.toString(shares), expect, human, reply);
        ObjectNode unit = units.addObject();
        unit.put("kind", "FORMATION");
        unit.put("goalkeeper", shares[0]);
        unit.put("defender", shares[1]);
        unit.put("midfielder", shares[2]);
        unit.put("forward", shares[3]);
        if (reason != null) {
            unit.put("reason", reason);
        }
        unit.put("expect", expect);
    }

    private void lineup(String asset, String position, String expect) {
        Reply reply = arena.lineup(turn, asset, position);
        crossCheck("lineup " + asset, expect, "ACCEPTED".equals(expect) ? null : expect, reply);
        ObjectNode unit = units.addObject();
        unit.put("kind", "LINEUP");
        unit.put("asset", asset);
        unit.put("position", position);
        unit.put("expect", expect);
    }

    private void crossCheck(String what, String expect, String humanCode, Reply reply) {
        boolean agrees = humanCode == null ? reply.ok() : !reply.ok() && humanCode.equals(reply.code());
        if (!agrees) {
            throw new AssertionError("season " + spec.index() + " turn " + turn + ": " + what + " was meant to be " + expect
                    + " but the API answered " + reply.status() + " " + reply.code());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static ObjectNode direct() {
        ObjectNode source = MAPPER.createObjectNode();
        source.put("kind", "DIRECT");
        return source;
    }

    /** Whole shares that fit the budget with a fee margin above every rate (1 %). */
    private int affordable(String asset, long budget) {
        long price = arena.prices().get(asset);
        long quantity = budget * 100 / (price * 101);
        long cap = arena.cash() * 100 / (price * 101);
        return (int) Math.min(Math.min(quantity, cap), 5_000);
    }

    private String positionFor(String asset) {
        String open = arena.openPositions().get(asset);
        return open != null ? open : "DEFENDER";
    }

    private String otherPosition(String current) {
        String pick = POSITIONS[rng.below(POSITIONS.length)];
        return pick.equals(current) ? POSITIONS[(java.util.Arrays.asList(POSITIONS).indexOf(pick) + 1) % POSITIONS.length] : pick;
    }

    private String pickHeld() {
        List<String> held = new ArrayList<>(arena.pool());
        held.removeIf(a -> !arena.held().containsKey(a));
        return held.isEmpty() ? null : held.get(rng.below(held.size()));
    }

    private List<String> notHeld() {
        List<String> free = new ArrayList<>(arena.pool());
        free.removeIf(a -> arena.held().containsKey(a));
        return free;
    }

    private int[] randomFormation() {
        int goalkeeper = 5 + rng.below(26);
        int defender = rng.below(41);
        int midfielder = rng.below(41);
        int forward = 100 - goalkeeper - defender - midfielder;
        if (forward < 0) {
            forward = 0;
            defender = 100 - goalkeeper - midfielder;
            if (defender < 0) {
                defender = 0;
                midfielder = 100 - goalkeeper;
            }
        }
        return new int[] {goalkeeper, defender, midfielder, forward};
    }

    private List<String> shuffled(List<String> items) {
        List<String> copy = new ArrayList<>(items);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = rng.below(i + 1);
            String t = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, t);
        }
        return copy;
    }
}

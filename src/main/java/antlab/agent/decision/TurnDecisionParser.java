package antlab.agent.decision;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.asset.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Check 1 of the decision: the whole reply must have the shape of {@code docs/api/turn-decision-1.schema.json}.
 * Only types, required keys, enums, patterns and length bounds are judged here; value rules (quantity of zero, shares
 * that do not sum to 100, an unknown asset) are unit checks, so one bad order does not void the turn. The violations name
 * pointers and keywords only, so a reply cannot echo its own text through them (an unknown key is reported at its parent).
 */
public final class TurnDecisionParser {

    public static final String SCHEMA = "turn-decision/1";
    static final int MAX_ORDERS = 40;
    static final int MAX_THESES = 20;
    static final int MAX_FORMATION_REASON = 200;
    static final int MAX_REASON = 500;
    private static final int MAX_VIOLATIONS = 20;
    private static final Pattern ASSET = Pattern.compile("(KR|US):[A-Z0-9.]{1,12}");
    private static final Set<String> TOP = Set.of("schema", "formation_change", "orders", "theses", "reason");
    private static final Set<String> FORMATION = Set.of("goalkeeper", "defender", "midfielder", "forward", "reason");
    private static final Set<String> ORDER = Set.of("asset", "side", "quantity", "position", "source");
    private static final Set<String> SOURCE = Set.of("kind", "feed_seq", "ranking_rank");

    /** Exactly one of a decision or a non-empty violation list. */
    public record Result(TurnDecision decision, List<SchemaViolation> violations) {
        public boolean valid() {
            return decision != null;
        }
    }

    private final List<SchemaViolation> violations = new ArrayList<>();

    private TurnDecisionParser() {
    }

    public static Result parse(JsonNode root) {
        TurnDecisionParser parser = new TurnDecisionParser();
        TurnDecision decision = parser.top(root);
        if (!parser.violations.isEmpty()) {
            return new Result(null, List.copyOf(parser.violations));
        }
        return new Result(decision, List.of());
    }

    private void violate(String pointer, String keyword) {
        if (violations.size() < MAX_VIOLATIONS) {
            violations.add(new SchemaViolation(pointer, keyword));
        }
    }

    private TurnDecision top(JsonNode root) {
        if (root == null || !root.isObject()) {
            violate("", "type");
            return null;
        }
        unknownKeys(root, TOP, "");
        for (String key : List.of("schema", "formation_change", "orders", "theses", "reason")) {
            if (!root.has(key)) {
                violate("/" + key, "required");
            }
        }
        JsonNode schema = root.get("schema");
        if (schema != null && !(schema.isString() && SCHEMA.equals(schema.asString()))) {
            violate("/schema", "const");
        }
        TurnDecision.FormationProposal formation = formation(root.get("formation_change"));
        List<TurnDecision.OrderProposal> orders = orders(root.get("orders"));
        int theses = theses(root.get("theses"));
        String reason = string(root.get("reason"), "/reason", MAX_REASON);
        return violations.isEmpty() ? new TurnDecision(formation, orders, theses, reason) : null;
    }

    private TurnDecision.FormationProposal formation(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            violate("/formation_change", "type");
            return null;
        }
        unknownKeys(node, FORMATION, "/formation_change");
        int[] shares = new int[4];
        String[] names = {"goalkeeper", "defender", "midfielder", "forward"};
        for (int i = 0; i < 4; i++) {
            shares[i] = integer(node.get(names[i]), "/formation_change/" + names[i], true);
        }
        if (!node.has("reason")) {
            violate("/formation_change/reason", "required");
        }
        String reason = string(node.get("reason"), "/formation_change/reason", MAX_FORMATION_REASON);
        return new TurnDecision.FormationProposal(shares[0], shares[1], shares[2], shares[3], reason);
    }

    private List<TurnDecision.OrderProposal> orders(JsonNode node) {
        List<TurnDecision.OrderProposal> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (!node.isArray()) {
            violate("/orders", "type");
            return out;
        }
        if (node.size() > MAX_ORDERS) {
            violate("/orders", "maxItems");
        }
        for (int i = 0; i < node.size() && i < MAX_ORDERS; i++) {
            TurnDecision.OrderProposal order = order(node.get(i), "/orders/" + i);
            out.add(order);
        }
        return out;
    }

    private TurnDecision.OrderProposal order(JsonNode node, String at) {
        if (!node.isObject()) {
            violate(at, "type");
            return null;
        }
        unknownKeys(node, ORDER, at);
        for (String key : List.of("asset", "side", "quantity", "source")) {
            if (!node.has(key)) {
                violate(at + "/" + key, "required");
            }
        }
        String asset = null;
        JsonNode assetNode = node.get("asset");
        if (assetNode != null) {
            if (!assetNode.isString()) {
                violate(at + "/asset", "type");
            } else if (!ASSET.matcher(assetNode.asString()).matches()) {
                violate(at + "/asset", "pattern");
            } else {
                asset = assetNode.asString();
            }
        }
        OrderSide side = enumValue(node.get("side"), at + "/side", OrderSide.class);
        int quantity = integer(node.get("quantity"), at + "/quantity", false);
        Position position = null;
        if (node.has("position")) {
            position = enumValue(node.get("position"), at + "/position", Position.class);
        }
        OrderSource source = source(node.get("source"), at + "/source");
        return new TurnDecision.OrderProposal(asset, side, quantity, position, source);
    }

    private OrderSource source(JsonNode node, String at) {
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            violate(at, "type");
            return null;
        }
        unknownKeys(node, SOURCE, at);
        if (!node.has("kind")) {
            violate(at + "/kind", "required");
        }
        OrderSource kind = enumValue(node.get("kind"), at + "/kind", OrderSource.class);
        for (String key : List.of("feed_seq", "ranking_rank")) {
            if (node.has(key)) {
                integer(node.get(key), at + "/" + key, false);
            }
        }
        return kind;
    }

    private int theses(JsonNode node) {
        if (node == null) {
            return 0;
        }
        if (!node.isArray()) {
            violate("/theses", "type");
            return 0;
        }
        if (node.size() > MAX_THESES) {
            violate("/theses", "maxItems");
        }
        for (int i = 0; i < node.size(); i++) {
            if (!node.get(i).isObject()) {
                violate("/theses/" + i, "type");
            }
        }
        return node.size();
    }

    private void unknownKeys(JsonNode node, Set<String> allowed, String at) {
        if (node.propertyNames().stream().anyMatch(name -> !allowed.contains(name))) {
            violate(at, "additionalProperties");
        }
    }

    private int integer(JsonNode node, String at, boolean requiredHere) {
        if (node == null) {
            if (requiredHere) {
                violate(at, "required");
            }
            return 0;
        }
        if (!node.isIntegralNumber()) {
            violate(at, "type");
            return 0;
        }
        if (!node.canConvertToInt()) {
            violate(at, node.decimalValue().signum() < 0 ? "minimum" : "maximum");
            return 0;
        }
        return node.asInt();
    }

    private String string(JsonNode node, String at, int maxCodePoints) {
        if (node == null) {
            return null;
        }
        if (!node.isString()) {
            violate(at, "type");
            return null;
        }
        String value = node.asString();
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            violate(at, "maxLength");
            return null;
        }
        return value;
    }

    private <E extends Enum<E>> E enumValue(JsonNode node, String at, Class<E> type) {
        if (node == null) {
            return null;
        }
        if (!node.isString()) {
            violate(at, "type");
            return null;
        }
        return Arrays.stream(type.getEnumConstants()).filter(c -> c.name().equals(node.asString())).findFirst()
                .orElseGet(() -> {
                    violate(at, "enum");
                    return null;
                });
    }
}

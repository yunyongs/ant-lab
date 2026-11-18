package antlab.feed;

import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Policy constants of the nudge alerts, rule version {@code mvp-12:v1} (builder values). The version is part of every alert hash; the
 * feed keeps {@link FeedRules#RULE_VERSION}. Changing a constant or a family rule below bumps the version.
 */
public final class AlertRules {

    public static final String RULE_VERSION = "mvp-12:v1";
    /** Alerts of one turn: {@code min(MAX_ALERTS, 1 + idle intervals)}. */
    public static final int MAX_ALERTS = 4;
    /** The cash alert needs a cash share of at least this ratio. */
    public static final BigDecimal CASH_NUDGE_MIN = new BigDecimal("0.20");
    /** Assets taken from each of the gainers, losers and attention lists as alert candidates. */
    public static final int CANDIDATES_PER_LIST = 3;

    /** Variables an ASSET alert may use ({@code rank} only when the asset is on the gainers or losers list). */
    static final Set<String> ASSET_SLOTS = Set.of("asset_name", "ret", "rank");
    static final Set<String> IDLE_SLOTS = Set.of("idle_turns", "trade_count");
    static final Set<String> CASH_SLOTS = Set.of("cash_weight");

    private AlertRules() {
    }

    /** Templates belong to a family by their shape: target and placeholders. */
    enum Family {
        ASSET, IDLE, CASH, FREE
    }

    /** The one family a template belongs to; empty when it fits none (it could never be picked). */
    static Optional<Family> familyOf(FeedTemplate template) {
        if (template.kind() != FeedKind.NUDGE) {
            return Optional.empty();
        }
        List<String> slots = template.placeholders();
        return switch (template.target()) {
            case "ASSET" -> ASSET_SLOTS.containsAll(slots) ? Optional.of(Family.ASSET) : Optional.empty();
            case "NONE" -> {
                if (slots.isEmpty()) {
                    yield Optional.of(Family.FREE);
                }
                if (Set.copyOf(slots).equals(CASH_SLOTS)) {
                    yield Optional.of(Family.CASH);
                }
                yield IDLE_SLOTS.containsAll(slots) ? Optional.of(Family.IDLE) : Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /** Number of alerts for a player who has been idle for {@code idleTurns} intervals. */
    static int count(int idleTurns) {
        return Math.min(MAX_ALERTS, 1 + idleTurns);
    }

    /** Pick hash of an alert: seed, turn, the alert rule version, usage and key, colon separated. */
    static long pick(long seed, int turnNo, String usage, String key) {
        return FeedHash.hash(Long.toString(seed) + ":" + turnNo + ":" + RULE_VERSION + ":" + usage + ":" + key);
    }
}

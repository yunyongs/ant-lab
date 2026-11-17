package antlab.feed;

import java.math.BigDecimal;

/**
 * What the alert generator may know about the player at turn {@code asOfTurn}: the cash share of that turn's snapshot and how much the
 * player traded in the intervals before it. Scalars only; nothing here can carry a later turn.
 *
 * @param cashWeight cash / total value of the turn's snapshot, scale 8; null when the total is 0
 * @param tradeCount executions of the interval that ended at this turn (orders placed while the turn before was current)
 * @param idleTurns  how many consecutive intervals before this turn, newest first, had no execution
 */
public record PlayerFacts(int asOfTurn, BigDecimal cashWeight, int tradeCount, int idleTurns) {

    public PlayerFacts {
        if (asOfTurn < 1) {
            throw refused("the turn is out of range");
        }
        if (cashWeight != null && (cashWeight.scale() != 8 || cashWeight.signum() < 0 || cashWeight.compareTo(BigDecimal.ONE) > 0)) {
            throw refused("a cash share must be a ratio of scale 8");
        }
        if (tradeCount < 0 || idleTurns < 0 || idleTurns > asOfTurn) {
            throw refused("trade counts out of range");
        }
        if (idleTurns >= 1 && tradeCount != 0) {
            throw refused("an idle interval cannot hold a trade");
        }
    }

    private static FeedGenerationException refused(String reason) {
        return new FeedGenerationException(FeedFailure.FEED_INPUT, reason);
    }
}

package antlab.agent.decision;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.asset.Position;
import java.util.List;

/** A reply that passed the shape check of {@code turn-decision/1}. Value rules are the unit checks, not this record. */
public record TurnDecision(FormationProposal formationChange, List<OrderProposal> orders, int thesesCount,
        String reason) {

    /** The four shares as sent; they may still break the sum and range rules, which the formation unit judges. */
    public record FormationProposal(int goalkeeper, int defender, int midfielder, int forward, String reason) {
    }

    /**
     * One order as sent: {@code asset} is {@code MARKET:TICKER}; the quantity may still be zero or negative.
     * {@code feedSeq} is the reply's {@code source.feed_seq} when it was sent; whether the kind needs it is a unit check.
     */
    public record OrderProposal(String asset, OrderSide side, int quantity, Position position, OrderSource source,
            Integer feedSeq) {
    }
}

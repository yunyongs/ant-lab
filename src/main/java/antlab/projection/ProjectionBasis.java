package antlab.projection;

import java.math.BigDecimal;
import java.util.List;

/** What a projection starts from: the cash and the priced holdings of a season at one turn. */
public record ProjectionBasis(int turnNo, BigDecimal cash, List<PricedHolding> holdings) {

    public ProjectionBasis {
        holdings = List.copyOf(holdings);
    }
}

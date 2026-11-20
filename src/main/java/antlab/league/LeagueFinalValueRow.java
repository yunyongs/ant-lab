package antlab.league;

import java.math.BigDecimal;

/** The valuation of a finished season at its last turn. */
public record LeagueFinalValueRow(Long seasonId, BigDecimal totalValue) {
}

package antlab.season;

import antlab.history.AxisDirection;
import java.math.BigDecimal;

/** The twelve exposed axis and probability columns of one turn's month. No block id, month or label is carried. */
public record TurnMacroRow(
        AxisDirection rateDir, BigDecimal rateStrength,
        AxisDirection growthDir, BigDecimal growthStrength,
        AxisDirection fxDir, BigDecimal fxStrength,
        AxisDirection sentimentDir, BigDecimal sentimentStrength,
        BigDecimal probP1, BigDecimal probP2, BigDecimal probP3, BigDecimal probP4) {
}

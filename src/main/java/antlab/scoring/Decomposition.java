package antlab.scoring;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Three-way split of the excess over the market baseline, exact in won: {@code selection + tactic + structure = total}, because the three
 * terms telescope through the two formation baselines. Each ratio is the won amount divided once by the initial cash (8 decimals), so
 * the three ratios may differ from the total ratio by rounding; the won identity is the exact one.
 *
 * @param selectionWon user final minus the formation-following baseline
 * @param tacticWon    formation-following baseline minus the fixed-formation baseline
 * @param structureWon fixed-formation baseline minus the market baseline
 * @param totalWon     user final minus the market baseline
 */
public record Decomposition(BigDecimal selectionWon, BigDecimal tacticWon, BigDecimal structureWon, BigDecimal totalWon,
        BigDecimal selectionReturn, BigDecimal tacticReturn, BigDecimal structureReturn, BigDecimal totalReturn) {

    public static Decomposition of(BigDecimal userFinal, BigDecimal followFinal, BigDecimal fixedFinal,
            BigDecimal marketFinal, BigDecimal initialCash) {
        Objects.requireNonNull(userFinal, "userFinal");
        Objects.requireNonNull(followFinal, "followFinal");
        Objects.requireNonNull(fixedFinal, "fixedFinal");
        Objects.requireNonNull(marketFinal, "marketFinal");
        Objects.requireNonNull(initialCash, "initialCash");
        if (initialCash.signum() <= 0) {
            throw BaselineSimulator.invalid("the initial cash must be positive");
        }
        BigDecimal selection = MoneyMath.exactWon(userFinal.subtract(followFinal));
        BigDecimal tactic = MoneyMath.exactWon(followFinal.subtract(fixedFinal));
        BigDecimal structure = MoneyMath.exactWon(fixedFinal.subtract(marketFinal));
        BigDecimal total = MoneyMath.exactWon(userFinal.subtract(marketFinal));
        return new Decomposition(selection, tactic, structure, total,
                MoneyMath.ratio(selection, initialCash), MoneyMath.ratio(tactic, initialCash),
                MoneyMath.ratio(structure, initialCash), MoneyMath.ratio(total, initialCash));
    }
}

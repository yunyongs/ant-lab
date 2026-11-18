package antlab.scoring;

import antlab.asset.Position;
import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.List;

/**
 * Defender goals of a finished season (PLAN §6.2, MVP-19). Turn t (1..T) is a down-market turn when the market pair (069500 and SPY,
 * 50/50) fell from t-1 to t; the sign is decided exactly by cross-multiplication, never by a division. A goal is a holding at the start
 * of such a turn, declared defender or goalkeeper as of turn t-1, whose price rose; its contribution is quantity times the price rise
 * in whole won. Pure.
 */
public final class DefenderGoalCalculator {

    private DefenderGoalCalculator() {
    }

    /**
     * One holding at the start of turn {@code turnNo} (quantity from fills of earlier turns) with the asset's price at the turn before
     * and at the turn, and the position declared as of the turn before.
     */
    public record HeldTurn(int turnNo, long assetId, long quantity, BigDecimal previousPrice, BigDecimal price,
            Position declared) {
    }

    /**
     * @param downMarketTurns turns of 1..T where the market pair fell
     * @param goals           goals counted (one per holding and down-market turn)
     * @param goalWon         sum of the goals' won gains
     * @param goalReturn      goal won divided once by the initial cash, 8 decimals
     */
    public record Result(int downMarketTurns, int goals, BigDecimal goalWon, BigDecimal goalReturn) {
    }

    /**
     * @param krPrices prices of the first market asset, turns 0..T
     * @param usPrices prices of the second market asset, turns 0..T (equal weights)
     * @throws IllegalStateException when the series, a holding or the declared position contradict the season
     */
    public static Result calculate(int reportTurn, BigDecimal initialCash, List<BigDecimal> krPrices, List<BigDecimal> usPrices,
            List<HeldTurn> holdings) {
        if (reportTurn < 1 || krPrices == null || usPrices == null || krPrices.size() != reportTurn + 1
                || usPrices.size() != reportTurn + 1) {
            throw inconsistent("시장 대용 가격이 0턴부터 보고 턴까지 이어지지 않는다");
        }
        if (initialCash == null || initialCash.signum() <= 0) {
            throw inconsistent("초기 자금이 양수가 아니다");
        }
        boolean[] down = new boolean[reportTurn + 1];
        int downTurns = 0;
        for (int t = 1; t <= reportTurn; t++) {
            if (marketFell(krPrices.get(t - 1), usPrices.get(t - 1), krPrices.get(t), usPrices.get(t))) {
                down[t] = true;
                downTurns++;
            }
        }
        BigDecimal won = BigDecimal.ZERO;
        int goals = 0;
        for (HeldTurn held : holdings) {
            if (held.turnNo() < 1 || held.turnNo() > reportTurn || held.quantity() <= 0 || held.previousPrice() == null
                    || held.price() == null || held.previousPrice().signum() <= 0 || held.price().signum() <= 0) {
                throw inconsistent("보유 행이 범위를 벗어났거나 수량·가격이 양수가 아니다");
            }
            if (held.declared() == null) {
                throw inconsistent("보유한 턴에 선언 구간이 하나가 아니다");
            }
            boolean defensive = held.declared() == Position.DEFENDER || held.declared() == Position.GOALKEEPER;
            BigDecimal rise = held.price().subtract(held.previousPrice());
            if (down[held.turnNo()] && defensive && rise.signum() > 0) {
                goals++;
                won = won.add(rise.multiply(BigDecimal.valueOf(held.quantity())));
            }
        }
        BigDecimal goalWon = MoneyMath.exactWon(won);
        return new Result(downTurns, goals, goalWon, MoneyMath.ratio(goalWon, MoneyMath.exactWon(initialCash)));
    }

    /** (a1/a0 + b1/b0) / 2 &lt; 1, multiplied through by the positive 2·a0·b0: a1·b0 + b1·a0 &lt; 2·a0·b0. */
    static boolean marketFell(BigDecimal a0, BigDecimal b0, BigDecimal a1, BigDecimal b1) {
        if (a0 == null || b0 == null || a1 == null || b1 == null || a0.signum() <= 0 || b0.signum() <= 0 || a1.signum() <= 0
                || b1.signum() <= 0) {
            throw inconsistent("시장 대용 가격이 없거나 양수가 아니다");
        }
        BigDecimal left = a1.multiply(b0).add(b1.multiply(a0));
        BigDecimal right = a0.multiply(b0).multiply(BigDecimal.valueOf(2));
        return left.compareTo(right) < 0;
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("수비수 득점 원천이 모순이다: " + reason);
    }
}

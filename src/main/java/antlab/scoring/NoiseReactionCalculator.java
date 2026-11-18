package antlab.scoring;

import antlab.account.OrderSide;
import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reaction to noise of a finished season (PLAN §6.2, MVP-19): a pure function of the judged turns' {@link TurnSignals} and the filled
 * orders. In a turn a buy follows the noise when its asset is in that turn's {@code up} set and a sell when it is in {@code down}.
 * The turn's value is followed amount ÷ traded amount; the season's value is the mean over the turns that traded, as one exact
 * fraction mean rounded once to 8 decimals. Turns 1..T-1 only; turn 0 is team building and is never judged.
 */
public final class NoiseReactionCalculator {

    private NoiseReactionCalculator() {
    }

    /**
     * @param noiseShare           mean over trading turns of followed ÷ traded, null when no turn traded
     * @param tradingTurns         turns of 1..T-1 with at least one filled order
     * @param followedBuyNotional  buys that followed the noise, whole won
     * @param followedSellNotional sells that followed the noise, whole won
     * @param tradeNotional        all buys and sells of turns 1..T-1, whole won (the report's traded amount)
     */
    public record Result(BigDecimal noiseShare, int tradingTurns, BigDecimal followedBuyNotional,
            BigDecimal followedSellNotional, BigDecimal tradeNotional) {
    }

    /** The one place that drops the team purchase (turn 0) before judging; the analysis and the score both call it. */
    public static Result ofSeason(int reportTurn, List<TurnSignals> signals, List<BehaviourOrder> allOrders) {
        return calculate(reportTurn, signals, allOrders.stream().filter(o -> o.turnNo() >= 1).toList());
    }

    /** @throws IllegalStateException when an order is outside turns 1..T-1, has no positive whole-won amount or has no signals */
    public static Result calculate(int reportTurn, List<TurnSignals> signals, List<BehaviourOrder> orders) {
        if (reportTurn < 1) {
            throw inconsistent("보고 턴이 범위를 벗어났다");
        }
        Map<Integer, TurnSignals> byTurn = new HashMap<>();
        for (TurnSignals s : signals) {
            if (s.turnNo() >= reportTurn || byTurn.put(s.turnNo(), s) != null) {
                throw inconsistent("턴 신호가 보고 범위 밖이거나 겹친다");
            }
        }
        Map<Integer, BigDecimal> tradedByTurn = new HashMap<>();
        Map<Integer, BigDecimal> followedByTurn = new HashMap<>();
        BigDecimal followedBuy = BigDecimal.ZERO;
        BigDecimal followedSell = BigDecimal.ZERO;
        BigDecimal trade = BigDecimal.ZERO;
        for (BehaviourOrder order : orders) {
            if (order.turnNo() < 1 || order.turnNo() >= reportTurn) {
                throw inconsistent("보고 범위 밖의 주문이 있다");
            }
            requireWon(order.notional());
            TurnSignals turn = byTurn.get(order.turnNo());
            if (turn == null) {
                throw inconsistent("주문 턴의 신호가 없다");
            }
            boolean followed = order.side() == OrderSide.BUY ? turn.up().contains(order.assetId())
                    : turn.down().contains(order.assetId());
            trade = trade.add(order.notional());
            tradedByTurn.merge(order.turnNo(), order.notional(), BigDecimal::add);
            if (followed) {
                followedByTurn.merge(order.turnNo(), order.notional(), BigDecimal::add);
                if (order.side() == OrderSide.BUY) {
                    followedBuy = followedBuy.add(order.notional());
                } else {
                    followedSell = followedSell.add(order.notional());
                }
            }
        }
        List<BigInteger> numerators = new ArrayList<>();
        List<BigInteger> denominators = new ArrayList<>();
        for (int turn = 1; turn < reportTurn; turn++) {
            BigDecimal traded = tradedByTurn.get(turn);
            if (traded != null) {
                numerators.add(MoneyMath.exactWon(followedByTurn.getOrDefault(turn, BigDecimal.ZERO)).toBigIntegerExact());
                denominators.add(MoneyMath.exactWon(traded).toBigIntegerExact());
            }
        }
        BigDecimal share = numerators.isEmpty() ? null : MoneyMath.fractionMean(numerators, denominators);
        return new Result(share, numerators.size(), MoneyMath.exactWon(followedBuy), MoneyMath.exactWon(followedSell),
                MoneyMath.exactWon(trade));
    }

    private static void requireWon(BigDecimal notional) {
        if (notional == null || notional.signum() <= 0 || notional.remainder(BigDecimal.ONE).signum() != 0) {
            throw inconsistent("주문 대금이 양의 원 단위 정수가 아니다");
        }
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("소음 반응 원천이 모순이다: " + reason);
    }
}

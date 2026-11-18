package antlab.scoring;

import antlab.account.OrderSide;
import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attention following of a finished season (PLAN §6.2, MVP-19): the buys of assets that were in the top attention grade at the buying
 * turn against all other buys, by the price change over the next {@link BehaviourRules#WINDOW_TURNS} turns. A group's value is the
 * mean of the buys' returns weighted by amount, rounded once to 8 decimals. A buy whose window runs past the last turn is only
 * counted and compared in neither group. Pure; sells and turn 0 are not judged.
 */
public final class AttentionFollowingCalculator {

    private AttentionFollowingCalculator() {
    }

    /**
     * @param attentionBuys        buys of top-attention assets with a complete window
     * @param attentionBuyNotional their amount, whole won
     * @param attentionReturn      amount-weighted mean return over the window, null when there is no such buy
     * @param otherBuys            all other buys with a complete window
     * @param otherBuyNotional     their amount, whole won
     * @param otherReturn          amount-weighted mean return over the window, null when there is no such buy
     * @param incompleteBuys       buys of turns 1..T-1 whose window ends after the last turn (in neither group)
     */
    public record Result(int attentionBuys, BigDecimal attentionBuyNotional, BigDecimal attentionReturn, int otherBuys,
            BigDecimal otherBuyNotional, BigDecimal otherReturn, int incompleteBuys) {
    }

    /** @throws IllegalStateException when an order is outside turns 1..T-1, has no signals or a window price contradicts the turn */
    public static Result calculate(int reportTurn, List<TurnSignals> signals, List<BehaviourOrder> orders) {
        if (reportTurn < 1) {
            throw inconsistent("보고 턴이 범위를 벗어났다");
        }
        Map<Integer, TurnSignals> byTurn = signals.stream()
                .collect(Collectors.toMap(TurnSignals::turnNo, Function.identity(), (a, b) -> {
                    throw inconsistent("턴 신호가 겹친다");
                }));
        Group attention = new Group();
        Group other = new Group();
        int incomplete = 0;
        for (BehaviourOrder order : orders) {
            if (order.turnNo() < 1 || order.turnNo() >= reportTurn) {
                throw inconsistent("보고 범위 밖의 주문이 있다");
            }
            if (order.side() != OrderSide.BUY) {
                continue;
            }
            TurnSignals turn = byTurn.get(order.turnNo());
            if (turn == null) {
                throw inconsistent("주문 턴의 신호가 없다");
            }
            if (order.notional() == null || order.notional().signum() <= 0 || order.notional().remainder(BigDecimal.ONE).signum() != 0) {
                throw inconsistent("주문 대금이 양의 원 단위 정수가 아니다");
            }
            boolean complete = order.turnNo() + BehaviourRules.WINDOW_TURNS <= reportTurn;
            if (!complete) {
                if (order.priceAfter() != null) {
                    throw inconsistent("창이 끝나지 않은 주문에 뒤 가격이 있다");
                }
                incomplete++;
                continue;
            }
            if (order.price() == null || order.price().signum() <= 0 || order.priceAfter() == null
                    || order.priceAfter().signum() <= 0) {
                throw inconsistent("창이 끝난 주문의 가격이 없거나 양수가 아니다");
            }
            (turn.topAttention().contains(order.assetId()) ? attention : other).add(order);
        }
        return new Result(attention.count, MoneyMath.exactWon(attention.notional), attention.mean(), other.count,
                MoneyMath.exactWon(other.notional), other.mean(), incomplete);
    }

    /**
     * Sums amount × (after − before) ÷ before as one exact fraction; the division by the total amount and the only rounding happen at the
     * end. Prices are stored whole-won values.
     */
    private static final class Group {
        int count;
        BigDecimal notional = BigDecimal.ZERO;
        BigInteger numerator = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ONE;

        void add(BehaviourOrder order) {
            BigInteger before = wholePrice(order.price());
            BigInteger after = wholePrice(order.priceAfter());
            BigInteger term = order.notional().toBigIntegerExact().multiply(after.subtract(before));
            numerator = numerator.multiply(before).add(term.multiply(denominator));
            denominator = denominator.multiply(before);
            BigInteger gcd = numerator.gcd(denominator);
            if (gcd.signum() > 0) {
                numerator = numerator.divide(gcd);
                denominator = denominator.divide(gcd);
            }
            count++;
            notional = notional.add(order.notional());
        }

        BigDecimal mean() {
            if (count == 0) {
                return null;
            }
            return new BigDecimal(numerator).divide(new BigDecimal(denominator.multiply(notional.toBigIntegerExact())), 8,
                    RoundingMode.HALF_UP);
        }
    }

    private static BigInteger wholePrice(BigDecimal price) {
        try {
            return price.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw inconsistent("가격이 원 단위 정수가 아니다");
        }
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("관심 추종 원천이 모순이다: " + reason);
    }
}

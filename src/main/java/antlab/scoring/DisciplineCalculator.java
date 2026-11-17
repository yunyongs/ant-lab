package antlab.scoring;

import antlab.account.OrderSide;
import antlab.common.money.MoneyMath;
import antlab.scoring.DisciplineFacts.ChangeFact;
import antlab.scoring.DisciplineFacts.OrderFact;
import antlab.scoring.DisciplineFacts.TurnValue;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trading discipline of a finished season (PLAN §6.2, MVP-17): the panic-selling and chase-buying shares and the count of
 * formation changes made right after a fall. A pure function of {@link DisciplineFacts}; read-only, no Spring.
 *
 * <ul>
 *   <li>Range: orders of turns 1..T-1 (turn 0 is team building and is never judged).</li>
 *   <li>Asset drop / rise at turn t: the traded asset's won price moved by -8 % or less / +8 % or more from turn t-1.</li>
 *   <li>Team drop at turn t: the stored team value fell by 8 % or more from turn t-1 (turn 0 = initial cash).</li>
 *   <li>Panic formation change: a turn with a team drop whose forward target at its end is lower than at its start.</li>
 *   <li>Panic sell: a sell of an asset that dropped at that turn, or a sell bound to a change of a panic turn.</li>
 *   <li>Chase buy: a buy of an asset that rose at that turn.</li>
 * </ul>
 * Amounts are whole won sums; each share is one division by {@link MoneyMath#ratio}, null when its denominator is 0.
 */
public final class DisciplineCalculator {

    private DisciplineCalculator() {
    }

    /**
     * @param sellNotional       all sells of turns 1..T-1
     * @param panicSellNotional  the panic part of them
     * @param panicSellShare     panic / sells
     * @param buyNotional        all buys of turns 1..T-1
     * @param chaseBuyNotional   the chase part of them
     * @param chaseBuyShare      chase / buys
     * @param tradeNotional      sells + buys (the report's trade amount)
     * @param undisciplinedShare (panic + chase) / trade amount
     * @param formationChanges   formation change rows made in turns 1..T-1 (a turn with two changes counts twice)
     * @param panicFormationChanges those rows that belong to a panic turn; counted by row, not by turn, so every row of a
     *                           panic turn counts even when only the whole turn lowered the forward target
     */
    public record Result(BigDecimal sellNotional, BigDecimal panicSellNotional, BigDecimal panicSellShare,
            BigDecimal buyNotional, BigDecimal chaseBuyNotional, BigDecimal chaseBuyShare, BigDecimal tradeNotional,
            BigDecimal undisciplinedShare, int formationChanges, int panicFormationChanges) {
    }

    public static Result calculate(DisciplineFacts facts) {
        validate(facts);
        int reportTurn = facts.reportTurn();

        Map<Integer, BigDecimal> teamValue = new HashMap<>();
        for (TurnValue turn : facts.turnValues()) {
            teamValue.put(turn.turnNo(), turn.totalValue());
        }
        List<ChangeFact> changes = facts.changes().stream()
                .filter(c -> c.turnNo() >= 1 && c.turnNo() < reportTurn)
                .sorted(Comparator.comparingLong(ChangeFact::id)).toList();
        Set<Integer> panicTurns = panicTurns(facts.initialCash(), teamValue, changes, reportTurn);
        Map<Long, ChangeFact> changeById = new HashMap<>();
        int panicChanges = 0;
        for (ChangeFact change : changes) {
            changeById.put(change.id(), change);
            if (panicTurns.contains(change.turnNo())) {
                panicChanges++;
            }
        }

        BigDecimal sell = BigDecimal.ZERO;
        BigDecimal panicSell = BigDecimal.ZERO;
        BigDecimal buy = BigDecimal.ZERO;
        BigDecimal chaseBuy = BigDecimal.ZERO;
        for (OrderFact order : facts.orders()) {
            if (order.turnNo() < 1) {
                continue;
            }
            requireJudgeable(order, changeById);
            if (order.side() == OrderSide.SELL) {
                sell = sell.add(order.notional());
                if (DisciplineRules.dropped(order.previousPrice(), order.price())
                        || boundToPanicChange(order, changeById, panicTurns)) {
                    panicSell = panicSell.add(order.notional());
                }
            } else {
                buy = buy.add(order.notional());
                if (DisciplineRules.rose(order.previousPrice(), order.price())) {
                    chaseBuy = chaseBuy.add(order.notional());
                }
            }
        }

        BigDecimal trade = sell.add(buy);
        return new Result(won(sell), won(panicSell), share(panicSell, sell), won(buy), won(chaseBuy),
                share(chaseBuy, buy), won(trade), share(panicSell.add(chaseBuy), trade), changes.size(), panicChanges);
    }

    /** Turns with a team drop and a lower forward target at the end of the turn than at its start. */
    private static Set<Integer> panicTurns(BigDecimal initialCash, Map<Integer, BigDecimal> teamValue,
            List<ChangeFact> changes, int reportTurn) {
        Map<Integer, Integer> startForward = new HashMap<>();
        Map<Integer, Integer> endForward = new HashMap<>();
        for (ChangeFact change : changes) {
            startForward.putIfAbsent(change.turnNo(), change.beforeForward());
            endForward.put(change.turnNo(), change.afterForward());
        }
        Set<Integer> panic = new HashSet<>();
        for (int turn = 1; turn < reportTurn; turn++) {
            BigDecimal previous = turn == 1 ? initialCash : teamValue.get(turn - 1);
            if (startForward.containsKey(turn) && DisciplineRules.dropped(previous, teamValue.get(turn))
                    && endForward.get(turn) < startForward.get(turn)) {
                panic.add(turn);
            }
        }
        return panic;
    }

    private static boolean boundToPanicChange(OrderFact order, Map<Long, ChangeFact> changeById, Set<Integer> panicTurns) {
        return order.formationChangeId() != null && panicTurns.contains(changeById.get(order.formationChangeId()).turnNo());
    }

    private static BigDecimal won(BigDecimal sum) {
        return MoneyMath.exactWon(sum);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null : MoneyMath.ratio(MoneyMath.exactWon(part), MoneyMath.exactWon(whole));
    }

    private static void requireJudgeable(OrderFact order, Map<Long, ChangeFact> changeById) {
        if (order.notional() == null || order.notional().signum() <= 0
                || order.notional().remainder(BigDecimal.ONE).signum() != 0) {
            throw inconsistent("주문 대금이 양의 원 단위 정수가 아니다");
        }
        if (order.price() == null || order.previousPrice() == null || order.price().signum() <= 0
                || order.previousPrice().signum() <= 0) {
            throw inconsistent("주문 종목의 턴 가격이 없거나 양수가 아니다");
        }
        if (order.formationChangeId() != null) {
            ChangeFact change = changeById.get(order.formationChangeId());
            if (change == null || change.turnNo() != order.turnNo()) {
                throw inconsistent("주문에 묶인 전술 변경이 없거나 다른 턴이다");
            }
        }
    }

    private static void validate(DisciplineFacts facts) {
        int reportTurn = facts.reportTurn();
        if (reportTurn < 1) {
            throw inconsistent("보고 턴이 범위를 벗어났다");
        }
        if (facts.initialCash() == null || facts.initialCash().signum() <= 0) {
            throw inconsistent("초기 자금이 양수가 아니다");
        }
        List<TurnValue> values = facts.turnValues();
        if (values.size() != reportTurn) {
            throw inconsistent("턴 스냅샷이 첫 턴부터 보고 턴까지 이어지지 않는다");
        }
        for (int i = 0; i < values.size(); i++) {
            TurnValue value = values.get(i);
            if (value.turnNo() != i + 1 || value.totalValue() == null || value.totalValue().signum() < 0) {
                throw inconsistent("턴 스냅샷이 첫 턴부터 보고 턴까지 이어지지 않는다");
            }
        }
        for (OrderFact order : facts.orders()) {
            if (order.turnNo() < 0 || order.turnNo() >= reportTurn) {
                throw inconsistent("보고 범위 밖의 주문이 있다");
            }
        }
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("매매 규율 원천이 모순이다: " + reason);
    }
}

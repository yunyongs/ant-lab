package antlab.scoring;

import antlab.common.money.MoneyMath;
import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Effect of each formation change over the following {@link BehaviourRules#WINDOW_TURNS} turns (PLAN §6.2, MVP-19), in the
 * representative-baseline world of the three-way split. For the i-th change (by id) of turns 1..T-1, {@code with} is the
 * formation-following baseline holding changes 1..i and {@code without} the one holding changes 1..i-1; both are simulated from turn 0
 * on the season's prices, fees included, and the effect is their difference at {@code min(turn + 6, T)}. Changes at turn 0 or at the
 * last turn are not judged. Pure; only the schedule differs between the two runs.
 */
public final class ChangeEffectCalculator {

    private ChangeEffectCalculator() {
    }

    /**
     * @param turnNo       turn of the change
     * @param windowTurns  turns from the change to the measuring turn, 1..6 (shorter near the season end)
     * @param effectWon    with minus without at the measuring turn, whole won
     * @param effectReturn the won effect divided once by the initial cash, 8 decimals
     */
    public record Effect(int turnNo, int windowTurns, BigDecimal effectWon, BigDecimal effectReturn) {
    }

    /**
     * @param changes the season's formation changes with the target after each; any order, sorted here by id
     * @return one effect per judged change in id order; empty when there is none
     */
    public static List<Effect> calculate(BaselineSpec followSpec, List<Integer> startTarget, List<TargetSchedule.Change> changes,
            Map<AssetKey, List<BigDecimal>> prices, BigDecimal initialCash, FeeRates fees, int reportTurn) {
        List<TargetSchedule.Change> judged = changes.stream().filter(c -> c.turnNo() >= 1 && c.turnNo() < reportTurn)
                .sorted(Comparator.comparingLong(TargetSchedule.Change::id)).toList();
        if (judged.isEmpty()) {
            return List.of();
        }
        // runs.get(i) holds changes 1..i; it is the "with" run of change i and the "without" run of change i+1
        List<List<BigDecimal>> runs = new ArrayList<>();
        for (int i = 0; i <= judged.size(); i++) {
            TargetSchedule schedule = TargetSchedule.fromChanges(startTarget, judged.subList(0, i));
            runs.add(BaselineSimulator.simulate(followSpec, schedule, prices, initialCash, fees).turnValues());
        }
        List<Effect> out = new ArrayList<>();
        for (int i = 1; i <= judged.size(); i++) {
            int turn = judged.get(i - 1).turnNo();
            int measuring = Math.min(turn + BehaviourRules.WINDOW_TURNS, reportTurn);
            BigDecimal won = MoneyMath.exactWon(runs.get(i).get(measuring).subtract(runs.get(i - 1).get(measuring)));
            out.add(new Effect(turn, measuring - turn, won, MoneyMath.ratio(won, MoneyMath.exactWon(initialCash))));
        }
        return out;
    }
}

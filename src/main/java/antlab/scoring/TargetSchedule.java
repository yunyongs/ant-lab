package antlab.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Target percentage per slot over time: a start target and an ordered list of switches. A switch at turn t is in force from turn t
 * (the rebalance of turn t already uses it). Turns are strictly increasing, start at 1, and every switch changes the target.
 * The upper bound (turn below the report turn) is checked by the simulator, which knows the length of the season.
 */
public record TargetSchedule(List<Integer> start, List<Switch> switches) {

    public record Switch(int turnNo, List<Integer> target) {
        public Switch {
            target = copyChecked(target);
        }
    }

    /** One stored formation change: its turn, its id (order within a turn) and the target after it. */
    public record Change(int turnNo, long id, List<Integer> target) {
    }

    /**
     * Builds a schedule from stored changes: several changes in one turn collapse to the last by id, and a turn whose final target equals the
     * target in force before it (A to B to A within one turn) is dropped, since the target did not change. No forced trade is made for it.
     */
    public static TargetSchedule fromChanges(List<Integer> start, List<Change> changes) {
        List<Change> ordered = new ArrayList<>(Objects.requireNonNull(changes, "changes"));
        ordered.sort(java.util.Comparator.comparingInt(Change::turnNo).thenComparingLong(Change::id));
        List<Switch> switches = new ArrayList<>();
        List<Integer> current = copyChecked(start);
        for (int i = 0; i < ordered.size(); i++) {
            Change change = ordered.get(i);
            boolean lastOfTurn = i + 1 == ordered.size() || ordered.get(i + 1).turnNo() != change.turnNo();
            if (!lastOfTurn) {
                continue;
            }
            List<Integer> target = copyChecked(change.target());
            if (!target.equals(current)) {
                switches.add(new Switch(change.turnNo(), target));
                current = target;
            }
        }
        return new TargetSchedule(start, switches);
    }

    private static List<Integer> copyChecked(List<Integer> list) {
        Objects.requireNonNull(list, "target");
        if (list.stream().anyMatch(Objects::isNull)) {
            throw BaselineSimulator.invalid("a target percentage is outside the allowed range");
        }
        return List.copyOf(list);
    }

    public TargetSchedule {
        start = copyChecked(start);
        switches = List.copyOf(Objects.requireNonNull(switches, "switches"));
        checkTarget(start, start.size());
        List<Integer> previous = start;
        int previousTurn = 0;
        for (Switch sw : switches) {
            if (sw.turnNo() < 1 || sw.turnNo() <= previousTurn) {
                throw BaselineSimulator.invalid("switch turns must start at the first turn and strictly increase");
            }
            checkTarget(sw.target(), start.size());
            if (sw.target().equals(previous)) {
                throw BaselineSimulator.invalid("a switch must change the target");
            }
            previous = sw.target();
            previousTurn = sw.turnNo();
        }
    }

    public static TargetSchedule constant(int... target) {
        List<Integer> list = new ArrayList<>();
        for (int value : target) {
            list.add(value);
        }
        return new TargetSchedule(list, List.of());
    }

    /** The target in force at turn t: the last switch with turn at or before t, else the start target. */
    public List<Integer> targetAt(int turnNo) {
        List<Integer> current = start;
        for (Switch sw : switches) {
            if (sw.turnNo() <= turnNo) {
                current = sw.target();
            }
        }
        return current;
    }

    public boolean switchesAt(int turnNo) {
        return switches.stream().anyMatch(sw -> sw.turnNo() == turnNo);
    }

    private static void checkTarget(List<Integer> target, int size) {
        if (target.size() != size || target.isEmpty()) {
            throw BaselineSimulator.invalid("every target needs one percentage per slot");
        }
        int sum = 0;
        for (Integer value : target) {
            if (value == null || value < 0 || value > 100) {
                throw BaselineSimulator.invalid("a target percentage is outside the allowed range");
            }
            sum += value;
        }
        if (sum != 100) {
            throw BaselineSimulator.invalid("a target must add up to the whole");
        }
    }
}

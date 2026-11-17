package antlab.season;

import antlab.member.MemberKind;

/** The one place that decides whether an abandoned season counts as "not started" (PLAN §4.1-7, §6.9). */
public final class AbandonRules {

    /** An abandon at turn 0, 1 or 2 (abandoned_turn_no below this) is "not started". */
    static final int NOT_STARTED_BELOW_TURN = 3;

    private AbandonRules() {
    }

    /**
     * Derived, never stored. Only a human member can abandon without it counting; an agent season has no 3-turn
     * exception. A season that was not abandoned (null) is never "not started".
     */
    public static boolean notStarted(MemberKind kind, Integer abandonedTurnNo) {
        return kind == MemberKind.HUMAN && abandonedTurnNo != null && abandonedTurnNo < NOT_STARTED_BELOW_TURN;
    }
}

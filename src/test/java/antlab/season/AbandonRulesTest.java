package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.MemberKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** "Not started" is a human member who abandoned at turn 0, 1 or 2 (PLAN §4.1-7, §6.9); nothing else is. */
class AbandonRulesTest {

    @ParameterizedTest
    @CsvSource({"HUMAN,0,true", "HUMAN,1,true", "HUMAN,2,true", "HUMAN,3,false", "HUMAN,4,false", "HUMAN,59,false",
            "AGENT,0,false", "AGENT,2,false", "AGENT,3,false"})
    void byKindAndAbandonTurn(MemberKind kind, int abandonedTurnNo, boolean expected) {
        assertThat(AbandonRules.notStarted(kind, abandonedTurnNo)).isEqualTo(expected);
    }

    @Test
    void aSeasonThatWasNotAbandonedIsNeverNotStarted() {
        assertThat(AbandonRules.notStarted(MemberKind.HUMAN, null)).isFalse();
        assertThat(AbandonRules.notStarted(MemberKind.AGENT, null)).isFalse();
    }
}

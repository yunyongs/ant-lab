package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The reader of the stored reject list: the shapes the engine writes pass, any other shape is damaged data. */
class AgentTurnViewRejectsTest {

    @Test
    void the_shapes_of_a_unit_with_and_without_an_index_are_read() {
        List<RejectDto> out = AgentTurnViewService.parseRejects("""
                [{"unit": "FORMATION", "index": null, "code": "FORMATION_UNCHANGED"},
                 {"unit": "ORDER", "index": 3, "code": "INSUFFICIENT_CASH"},
                 {"unit": "THESES", "code": "THESES_NOT_SUPPORTED"}]""");
        assertThat(out).containsExactly(new RejectDto("FORMATION", null, "FORMATION_UNCHANGED"),
                new RejectDto("ORDER", 3, "INSUFFICIENT_CASH"), new RejectDto("THESES", null, "THESES_NOT_SUPPORTED"));
    }

    @Test
    void an_empty_list_is_empty() {
        assertThat(AgentTurnViewService.parseRejects("[]")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "\"x\"", "[1]", "[{}]", "[{\"unit\": \"ORDER\"}]", "[{\"code\": \"X\"}]",
            "[{\"unit\": 1, \"code\": \"X\"}]", "[{\"unit\": \"ORDER\", \"code\": 2}]",
            "[{\"unit\": \"ORDER\", \"index\": \"1\", \"code\": \"X\"}]",
            "[{\"unit\": \"ORDER\", \"index\": 1.5, \"code\": \"X\"}]"})
    void any_other_shape_is_refused(String stored) {
        assertThatThrownBy(() -> AgentTurnViewService.parseRejects(stored)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void a_window_of_less_than_one_turn_is_refused_at_start(int turns) {
        assertThatThrownBy(() -> new AgentTurnViewService(null, null, null, null, null, null, null, turns))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

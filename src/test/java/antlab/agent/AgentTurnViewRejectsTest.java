package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.agent.AgentTurnViewService.ParsedRejects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The reader of the stored reject list: the shapes the engine writes pass, anything else is left out (and counted)
 * instead of stopping the turn.
 */
class AgentTurnViewRejectsTest {

    @Test
    void unit_rejects_and_a_reply_level_reject_are_read() {
        ParsedRejects out = AgentTurnViewService.parseRejects("""
                [{"unit": "FORMATION", "index": null, "code": "FORMATION_UNCHANGED"},
                 {"unit": "ORDER", "index": 3, "code": "INSUFFICIENT_CASH"},
                 {"unit": "THESES", "code": "THESES_NOT_SUPPORTED"},
                 {"code": "SCHEMA_INVALID", "pointer": "/orders/0/quantity"},
                 {"unit": null, "index": null, "code": "SCHEMA_INVALID", "pointer": "/price"}]""");
        assertThat(out.dropped()).isZero();
        assertThat(out.items()).containsExactly(new RejectDto("FORMATION", null, "FORMATION_UNCHANGED", null),
                new RejectDto("ORDER", 3, "INSUFFICIENT_CASH", null),
                new RejectDto("THESES", null, "THESES_NOT_SUPPORTED", null),
                new RejectDto(null, null, "SCHEMA_INVALID", "/orders/0/quantity"),
                new RejectDto(null, null, "SCHEMA_INVALID", "/price"));
    }

    @Test
    void the_edges_of_the_index_range_pass() {
        ParsedRejects out = AgentTurnViewService.parseRejects(
                "[{\"unit\": \"ORDER\", \"index\": 0, \"code\": \"A\"}, {\"unit\": \"ORDER\", \"index\": 39, \"code\": \"A\"}]");
        assertThat(out.items()).hasSize(2);
        assertThat(out.dropped()).isZero();
    }

    @Test
    void an_empty_list_is_empty() {
        ParsedRejects out = AgentTurnViewService.parseRejects("[]");
        assertThat(out.items()).isEmpty();
        assertThat(out.dropped()).isZero();
    }

    static Stream<Arguments> unusableItems() {
        return Stream.of(
                Arguments.of("unknown unit", "{\"unit\": \"RESPONSE\", \"code\": \"X\"}"),
                Arguments.of("lower-case code", "{\"unit\": \"FORMATION\", \"code\": \"unchanged\"}"),
                Arguments.of("code with a space", "{\"unit\": \"FORMATION\", \"code\": \"A B\"}"),
                Arguments.of("model sentence as code", "{\"unit\": \"ORDER\", \"index\": 0, \"code\": \"Buy 5 shares of KR now\"}"),
                Arguments.of("code too long", "{\"unit\": \"FORMATION\", \"code\": \"" + "A".repeat(41) + "\"}"),
                Arguments.of("code missing", "{\"unit\": \"FORMATION\"}"),
                Arguments.of("code not text", "{\"unit\": \"FORMATION\", \"code\": 2}"),
                Arguments.of("ORDER without index", "{\"unit\": \"ORDER\", \"code\": \"X\"}"),
                Arguments.of("ORDER with null index", "{\"unit\": \"ORDER\", \"index\": null, \"code\": \"X\"}"),
                Arguments.of("index 40", "{\"unit\": \"ORDER\", \"index\": 40, \"code\": \"X\"}"),
                Arguments.of("negative index", "{\"unit\": \"ORDER\", \"index\": -1, \"code\": \"X\"}"),
                Arguments.of("index on FORMATION", "{\"unit\": \"FORMATION\", \"index\": 0, \"code\": \"X\"}"),
                Arguments.of("index as text", "{\"unit\": \"ORDER\", \"index\": \"1\", \"code\": \"X\"}"),
                Arguments.of("index fractional", "{\"unit\": \"ORDER\", \"index\": 1.5, \"code\": \"X\"}"),
                Arguments.of("index beyond int", "{\"unit\": \"ORDER\", \"index\": 99999999999, \"code\": \"X\"}"),
                Arguments.of("unit not text", "{\"unit\": 1, \"code\": \"X\"}"),
                Arguments.of("unit with a pointer", "{\"unit\": \"FORMATION\", \"code\": \"X\", \"pointer\": \"/a\"}"),
                Arguments.of("no unit, other code", "{\"code\": \"INSUFFICIENT_CASH\", \"pointer\": \"/a\"}"),
                Arguments.of("no unit, no pointer", "{\"code\": \"SCHEMA_INVALID\"}"),
                Arguments.of("no unit, with index", "{\"code\": \"SCHEMA_INVALID\", \"index\": 1, \"pointer\": \"/a\"}"),
                Arguments.of("pointer with a space", "{\"code\": \"SCHEMA_INVALID\", \"pointer\": \"/a b\"}"),
                Arguments.of("pointer with a model sentence", "{\"code\": \"SCHEMA_INVALID\", \"pointer\": \"/please sell everything\"}"),
                Arguments.of("pointer not starting with a slash", "{\"code\": \"SCHEMA_INVALID\", \"pointer\": \"orders\"}"),
                Arguments.of("pointer too deep", "{\"code\": \"SCHEMA_INVALID\", \"pointer\": \"/a/b/c/d/e/f/g\"}"),
                Arguments.of("pointer step too long", "{\"code\": \"SCHEMA_INVALID\", \"pointer\": \"/" + "a".repeat(41) + "\"}"),
                Arguments.of("not an object", "1"),
                Arguments.of("empty object", "{}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableItems")
    void an_unusable_item_is_left_out_and_counted_and_its_neighbours_stay(String label, String item) {
        ParsedRejects out = AgentTurnViewService.parseRejects(
                "[{\"unit\": \"FORMATION\", \"code\": \"FORMATION_UNCHANGED\"}, " + item + "]");
        assertThat(out.items()).as(label).containsExactly(new RejectDto("FORMATION", null, "FORMATION_UNCHANGED", null));
        assertThat(out.dropped()).as(label).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "\"x\"", "7", "[1", "", "not json", "{\"unit\": \"ORDER\"}"})
    void a_list_that_cannot_be_read_gives_no_items_and_does_not_throw(String stored) {
        ParsedRejects out = AgentTurnViewService.parseRejects(stored);
        assertThat(out.items()).isEmpty();
        assertThat(out.dropped()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void a_window_of_less_than_one_turn_is_refused_at_start(int turns) {
        assertThatThrownBy(() -> new AgentTurnViewService(null, null, null, null, null, null, null, turns))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

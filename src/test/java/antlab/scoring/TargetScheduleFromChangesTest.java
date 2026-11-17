package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.TargetSchedule.Change;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetScheduleFromChangesTest {

    private static final List<Integer> START = List.of(20, 30, 30, 20);
    private static final List<Integer> B = List.of(40, 30, 20, 10);
    private static final List<Integer> C = List.of(10, 40, 30, 20);

    @Test
    void several_changes_in_one_turn_collapse_to_the_last_by_id_whatever_the_input_order() {
        TargetSchedule schedule = TargetSchedule.fromChanges(START,
                List.of(new Change(5, 12, C), new Change(5, 11, B)));
        assertThat(schedule.switches()).hasSize(1);
        assertThat(schedule.switches().get(0).turnNo()).isEqualTo(5);
        assertThat(schedule.switches().get(0).target()).isEqualTo(C);
    }

    @Test
    void a_round_trip_inside_one_turn_is_dropped() {
        TargetSchedule schedule = TargetSchedule.fromChanges(START,
                List.of(new Change(5, 11, B), new Change(5, 12, START)));
        assertThat(schedule.switches()).isEmpty();
    }

    @Test
    void a_dropped_turn_does_not_change_what_the_next_turn_is_compared_with() {
        TargetSchedule schedule = TargetSchedule.fromChanges(START, List.of(
                new Change(2, 1, B), new Change(2, 2, START), new Change(4, 3, B)));
        assertThat(schedule.switches()).hasSize(1);
        assertThat(schedule.switches().get(0).turnNo()).isEqualTo(4);
        assertThat(schedule.targetAt(3)).isEqualTo(START);
        assertThat(schedule.targetAt(4)).isEqualTo(B);
    }

    @Test
    void a_later_turn_returning_to_the_start_target_is_kept() {
        TargetSchedule schedule = TargetSchedule.fromChanges(START, List.of(new Change(2, 1, B), new Change(4, 2, START)));
        assertThat(schedule.switches()).extracting(TargetSchedule.Switch::turnNo).containsExactly(2, 4);
    }

    @Test
    void no_changes_gives_a_constant_schedule() {
        assertThat(TargetSchedule.fromChanges(START, List.of()).switches()).isEmpty();
    }
}

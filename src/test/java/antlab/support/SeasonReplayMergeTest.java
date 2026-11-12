package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.SeasonReplayInput.ActionKind;
import antlab.support.SeasonReplayInput.AdvanceTurn;
import antlab.support.SeasonReplayInput.FormationChange;
import antlab.support.SeasonReplayInput.LineupChange;
import antlab.support.SeasonReplayInput.PlaceOrder;
import antlab.support.SeasonReplayInput.ReplayAction;
import antlab.support.SeasonReplayInput.Shape;
import antlab.support.SeasonReplayInput.Timed;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 재생 행동 병합(순수 함수) 단위 시험 — DB 없이 같은 턴 교차 순서·시각 병합·동률 실패를 본다. */
class SeasonReplayMergeTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static Timed order(long turn, int seconds, String key) {
        return new Timed(turn, at(seconds), new PlaceOrder(key, 1));
    }

    private static Timed change(long turn, long ordinal, int seconds) {
        return new Timed(turn, at(seconds), new FormationChange(turn, ordinal, new Shape(10, 30, 30, 30), null));
    }

    private static String label(ReplayAction action) {
        return switch (action) {
            case AdvanceTurn a -> "A" + a.toTurn();
            case PlaceOrder o -> "O:" + o.key();
            case FormationChange c -> "F" + c.turn() + "/" + c.ordinal();
            case LineupChange l -> "L" + l.turn();
        };
    }

    private static List<String> labels(List<ReplayAction> actions) {
        return actions.stream().map(SeasonReplayMergeTest::label).toList();
    }

    @Test
    void 같은_턴의_주문과_전술_변경은_입력_순서가_아니라_기록_시각_순으로_섞인다() {
        // 입력은 일부러 뒤섞어 둔다: 주문 a(1초) · 변경1(2초) · 주문 b(3초) · 변경2(4초) · 주문 c(5초)
        List<Timed> timed = List.of(order(5, 5, "c"), change(5, 2, 4), order(5, 1, "a"), change(5, 1, 2), order(5, 3, "b"));

        List<ReplayAction> merged = SeasonReplayInput.merge(5, timed, List.of());

        assertThat(labels(merged).subList(merged.size() - 5, merged.size()))
                .containsExactly("O:a", "F5/1", "O:b", "F5/2", "O:c");
    }

    @Test
    void 턴_전환은_앞_턴의_행동_뒤_새_턴의_행동_앞에_온다() {
        List<Timed> timed = List.of(order(0, 1, "z0"), order(1, 1, "o1"), change(2, 1, 1), order(2, 2, "o2"));

        List<ReplayAction> merged = SeasonReplayInput.merge(2, timed, List.of());

        assertThat(labels(merged)).containsExactly("O:z0", "A1", "O:o1", "A2", "F2/1", "O:o2");
    }

    @Test
    void 다른_턴의_같은_시각은_동률이_아니다() {
        List<Timed> timed = List.of(order(1, 7, "a"), order(2, 7, "b"));

        assertThat(labels(SeasonReplayInput.merge(2, timed, List.of()))).containsExactly("A1", "O:a", "A2", "O:b");
    }

    @Test
    void 같은_턴에_기록_시각이_같은_행동이_둘이면_입력_순서로_고르지_않고_실패한다() {
        List<Timed> orderThenChange = List.of(order(5, 3, "a"), change(5, 1, 3));
        List<Timed> changeThenOrder = List.of(change(5, 1, 3), order(5, 3, "a"));

        assertThatThrownBy(() -> SeasonReplayInput.merge(5, orderThenChange, List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("기록 시각이 같은").hasMessageContaining("턴 5");
        assertThatThrownBy(() -> SeasonReplayInput.merge(5, changeThenOrder, List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("기록 시각이 같은");
    }

    @Test
    void 라인업_변경은_그_턴의_시각_있는_행동_뒤에_입력_순서대로_온다() {
        List<Timed> timed = List.of(order(5, 1, "a"), change(5, 1, 2));
        LineupChange first = new LineupChange(5, "KR/AAA", "DEFENDER");
        LineupChange second = new LineupChange(5, "KR/BBB", "MIDFIELDER");
        LineupChange later = new LineupChange(6, "KR/AAA", "DEFENDER");

        List<ReplayAction> merged = SeasonReplayInput.merge(6, timed, List.of(first, second, later));

        assertThat(labels(merged)).containsExactly("A1", "A2", "A3", "A4", "A5", "O:a", "F5/1", "L5", "L5", "A6", "L6");
        assertThat(merged.indexOf(first)).isLessThan(merged.indexOf(second));
    }

    @Test
    void 현재_턴보다_늦은_턴의_행동이_있으면_실패한다() {
        assertThatThrownBy(() -> SeasonReplayInput.merge(3, List.of(order(4, 1, "x")), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("현재 턴보다 늦은");
        assertThatThrownBy(() -> SeasonReplayInput.merge(3, List.of(), List.of(new LineupChange(4, "KR/AAA", "DEFENDER"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("현재 턴보다 늦은");
    }

    @Test
    void 행동이_0턴_주문뿐이면_전환_없이_주문만_나오고_현재_턴이_앞서면_전환이_이어진다() {
        List<Timed> timed = List.of(order(0, 1, "a"), order(0, 2, "b"));

        assertThat(labels(SeasonReplayInput.merge(0, timed, List.of()))).containsExactly("O:a", "O:b");
        assertThat(labels(SeasonReplayInput.merge(2, timed, List.of()))).containsExactly("O:a", "O:b", "A1", "A2");
        assertThat(SeasonReplayInput.merge(0, List.of(), List.of())).isEmpty();
    }

    @Test
    void 입력_목록을_바꾸지_않고_결과는_고칠_수_없다() {
        List<Timed> timed = new java.util.ArrayList<>(List.of(order(1, 2, "b"), order(1, 1, "a")));

        List<ReplayAction> merged = SeasonReplayInput.merge(1, timed, List.of());

        assertThat(labels(timed.stream().map(Timed::action).toList())).containsExactly("O:b", "O:a");
        assertThatThrownBy(() -> merged.add(new AdvanceTurn(9))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(merged.stream().map(ReplayAction::kind).toList())
                .containsExactly(ActionKind.ADVANCE_TURN, ActionKind.PLACE_ORDER, ActionKind.PLACE_ORDER);
    }
}

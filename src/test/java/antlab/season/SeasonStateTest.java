package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 상태 변경 메서드의 불변식(DB 없음). 실패해야 정상인 경우를 앞세운다. */
class SeasonStateTest {

    private static Season season(int pathLength) {
        return new Season(1L, pathLength, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
    }

    @Test
    void 포메이션_합이_100이_아니면_거부한다() {
        assertThatThrownBy(() -> new Formation(25, 25, 25, 24)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Formation(25, 25, 25, 26)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 골키퍼_0은_거부한다() {
        assertThatThrownBy(() -> new Formation(0, 40, 30, 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("골키퍼");
    }

    @Test
    void 비중_범위를_벗어나면_거부한다() {
        assertThatThrownBy(() -> new Formation(110, -10, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Formation(101, 0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 올바른_포메이션은_만들어진다() {
        Formation f = new Formation(10, 20, 30, 40);
        assertThat(f.goalkeeper() + f.defender() + f.midfielder() + f.forward()).isEqualTo(100);
        assertThat(new Formation(100, 0, 0, 0)).isEqualTo(new Formation(100, 0, 0, 0));
    }

    @Test
    void 새_시즌은_진행_중_0턴이고_현재_포메이션은_시작_포메이션이다() {
        Season s = season(3);
        assertThat(s.getStatus()).isEqualTo(SeasonStatus.IN_PROGRESS);
        assertThat(s.getCurrentTurnNo()).isZero();
        assertThat(s.getAbandonedTurnNo()).isNull();
        assertThat(s.getCurrentFormation()).isEqualTo(s.getStartFormation());
        assertThat(s.getVersion()).as("저장 전 버전은 null").isNull();
    }

    @Test
    void 경로_길이가_0_이하면_거부한다() {
        assertThatThrownBy(() -> season(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 턴을_진행하다_마지막_턴에서_끝난_시즌이_된다() {
        Season s = season(2);
        s.advanceTurn();
        assertThat(s.getStatus()).isEqualTo(SeasonStatus.IN_PROGRESS);
        assertThat(s.getCurrentTurnNo()).isEqualTo(1);
        s.advanceTurn();
        assertThat(s.getStatus()).isEqualTo(SeasonStatus.FINISHED);
        assertThat(s.getCurrentTurnNo()).isEqualTo(2);
    }

    @Test
    void 끝난_시즌은_더_진행하거나_전술을_바꿀_수_없다() {
        Season s = season(1);
        s.advanceTurn();
        assertThatThrownBy(s::advanceTurn).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.changeFormation(new Formation(10, 20, 30, 40)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 전술_변경은_현재와_같은_값을_거부하고_다른_값이면_현재만_바꾼다() {
        Season s = season(5);
        assertThatThrownBy(() -> s.changeFormation(new Formation(25, 25, 25, 25)))
                .isInstanceOf(IllegalStateException.class);
        s.changeFormation(new Formation(10, 20, 30, 40));
        assertThat(s.getCurrentFormation()).isEqualTo(new Formation(10, 20, 30, 40));
        assertThat(s.getStartFormation()).as("시작 포메이션은 그대로").isEqualTo(new Formation(25, 25, 25, 25));
    }

    @Test
    void 라인업은_열린_구간을_한_번만_닫고_끝_턴은_시작_턴_이상이다() {
        Lineup l = new Lineup(season(5), 9L, Position.MIDFIELDER, 3);
        assertThat(l.getEndTurn()).isNull();
        assertThatThrownBy(() -> l.close(2)).isInstanceOf(IllegalStateException.class);
        l.close(3);
        assertThat(l.getEndTurn()).isEqualTo(3);
        assertThatThrownBy(() -> l.close(4)).as("두 번 닫기").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 포메이션_변경_기록은_이전과_이후가_같으면_거부한다() {
        Formation f = new Formation(25, 25, 25, 25);
        assertThatThrownBy(() -> new FormationChange(season(5), 1, f, f, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

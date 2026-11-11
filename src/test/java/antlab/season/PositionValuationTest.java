package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 포지션별 평가액: 선언 포지션별 합, 골키퍼는 총액에서 나머지 셋을 뺀 잔여 방식(선언이 빠진 보유도 총액에 든다). */
class PositionValuationTest {

    private static BigDecimal won(long v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void 골키퍼는_현금과_선언이_없는_보유를_포함한_잔여다() {
        PositionValuation.PositionValues v = PositionValuation.of(won(1000), List.of(
                new PositionValuation.Line(1L, 10, won(100), Position.DEFENDER),
                new PositionValuation.Line(2L, 2, won(50), Position.MIDFIELDER),
                new PositionValuation.Line(3L, 1, won(300), Position.FORWARD_BREAKOUT),
                new PositionValuation.Line(4L, 1, won(200), null)));
        assertThat(v.holdingsValue()).isEqualByComparingTo("1600");
        assertThat(v.total()).isEqualByComparingTo("2600");
        assertThat(v.defender()).isEqualByComparingTo("1000");
        assertThat(v.midfielder()).isEqualByComparingTo("100");
        assertThat(v.forward()).isEqualByComparingTo("300");
        assertThat(v.goalkeeper()).isEqualByComparingTo("1200");
        assertThat(v.goalkeeper().add(v.defender()).add(v.midfielder()).add(v.forward())).isEqualByComparingTo(v.total());
    }

    @Test
    void 공격수_세_종류는_모두_공격수로_더하고_골키퍼_선언_종목은_잔여에_남는다() {
        PositionValuation.PositionValues v = PositionValuation.of(won(0), List.of(
                new PositionValuation.Line(1L, 1, won(10), Position.FORWARD_TARGET),
                new PositionValuation.Line(2L, 1, won(20), Position.FORWARD_BREAKOUT),
                new PositionValuation.Line(3L, 1, won(40), Position.FORWARD_DEFENSIVE),
                new PositionValuation.Line(4L, 1, won(80), Position.GOALKEEPER)));
        assertThat(v.forward()).isEqualByComparingTo("70");
        assertThat(v.goalkeeper()).isEqualByComparingTo("80");
        assertThat(v.total()).isEqualByComparingTo("150");
    }

    @Test
    void 가격이_없는_보유는_조용히_빠지고_수량_0은_평가액_0이다() {
        PositionValuation.PositionValues v = PositionValuation.of(won(500), List.of(
                new PositionValuation.Line(1L, 3, null, Position.DEFENDER),
                new PositionValuation.Line(2L, 0, won(100), Position.MIDFIELDER)));
        assertThat(v.holdingsValue()).isEqualByComparingTo("0");
        assertThat(v.total()).isEqualByComparingTo("500");
        assertThat(v.goalkeeper()).isEqualByComparingTo("500");
    }

    @Test
    void 보유가_없으면_현금이_전부_골키퍼다() {
        PositionValuation.PositionValues v = PositionValuation.of(won(777), List.of());
        assertThat(v.total()).isEqualByComparingTo("777");
        assertThat(v.goalkeeper()).isEqualByComparingTo("777");
        assertThat(v.defender()).isEqualByComparingTo("0");
    }
}

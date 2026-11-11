package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 비중·목표 대비 차이·이탈: 정수 원천에서 한 번 나눈 값이고, 양수 = 목표보다 많음이다. */
class PositionRatiosTest {

    private static BigDecimal won(long v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void 목표와_같으면_차이와_이탈이_모두_0이다() {
        PositionRatios r = PositionRatios.of(won(100), won(300), won(300), won(300), won(1000), new Formation(10, 30, 30, 30));
        assertThat(r.goalkeeperWeight().toPlainString()).isEqualTo("0.10000000");
        assertThat(r.goalkeeperGap().toPlainString()).isEqualTo("0.00000000");
        assertThat(r.forwardGap().toPlainString()).isEqualTo("0.00000000");
        assertThat(r.formationDeviation().toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 차이는_양수가_목표보다_많음이고_이탈은_절댓값의_합이다() {
        // 총액 1000: 골키퍼 400(목표 10 → +0.30), 수비 100(목표 30 → -0.20), 미드 100(-0.20), 공격 400(+0.10)
        PositionRatios r = PositionRatios.of(won(400), won(100), won(100), won(400), won(1000), new Formation(10, 30, 30, 30));
        assertThat(r.goalkeeperGap().toPlainString()).isEqualTo("0.30000000");
        assertThat(r.defenderGap().toPlainString()).isEqualTo("-0.20000000");
        assertThat(r.midfielderGap().toPlainString()).isEqualTo("-0.20000000");
        assertThat(r.forwardGap().toPlainString()).isEqualTo("0.10000000");
        assertThat(r.formationDeviation().toPlainString()).isEqualTo("0.80000000");
    }

    @Test
    void 나누어떨어지지_않는_총액에서도_차이는_한_번에_나눈_값이고_비중_합은_1과_2e8_안이다() {
        // 총액 3: 비중 1/3 씩 — 각각 한 번 반올림해 합이 1 에서 벗어날 수 있다
        PositionRatios r = PositionRatios.of(won(1), won(1), won(1), won(0), won(3), new Formation(25, 25, 25, 25));
        assertThat(r.goalkeeperWeight().toPlainString()).isEqualTo("0.33333333");
        assertThat(r.forwardWeight().toPlainString()).isEqualTo("0.00000000");
        // (100·1 − 25·3) ÷ (100·3) = 25/300 = 0.08333333…
        assertThat(r.goalkeeperGap().toPlainString()).isEqualTo("0.08333333");
        assertThat(r.forwardGap().toPlainString()).isEqualTo("-0.25000000");
        BigDecimal sum = r.goalkeeperWeight().add(r.defenderWeight()).add(r.midfielderWeight()).add(r.forwardWeight());
        assertThat(sum.subtract(BigDecimal.ONE).abs()).isLessThanOrEqualTo(new BigDecimal("0.00000002"));
    }
}

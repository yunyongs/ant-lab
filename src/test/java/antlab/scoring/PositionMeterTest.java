package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.scoring.PositionMeter.Metrics;
import antlab.scoring.PositionMeter.Reason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 시즌 실측 포지션 측정기 — 경계(밴드 ±0.01 포함, 사유 순서, 순위 동률, CEIL(0.2·N), 판정 문턱 각 가지)를 손 계산으로 확인한다. */
class PositionMeterTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static List<BigDecimal> list(String... v) {
        List<BigDecimal> out = new ArrayList<>();
        for (String s : v) {
            out.add(s == null ? null : bd(s));
        }
        return out;
    }

    private static Metrics measure(List<BigDecimal> r, List<BigDecimal> m) {
        return PositionMeter.measure(r, m, 6, 3, 3);
    }

    // ---------------------------------------------------------------- 지표

    @Test
    void 참여율_변동성_낙폭을_손으로_계산한_값과_맞춘다() {
        List<BigDecimal> m = list("0.02", "0.03", "0.01", "-0.01", "-0.02", "-0.05");
        List<BigDecimal> r = list("0.04", "0.03", "0.02", "-0.005", "-0.01", "-0.03");
        Metrics metrics = measure(r, m);
        assertThat(metrics.reason()).isNull();
        assertThat(metrics.judgeable()).isTrue();
        assertThat(metrics.monthsUsed()).isEqualTo(6);
        assertThat(metrics.nUp()).isEqualTo(3);
        assertThat(metrics.nDown()).isEqualTo(3);
        // 오른 달 Σ종목 0.09 ÷ Σ시장 0.06 = 1.5, 빠진 달 −0.045 ÷ −0.08 = 0.5625
        assertThat(metrics.up().toPlainString()).isEqualTo("1.50000000");
        assertThat(metrics.down().toPlainString()).isEqualTo("0.56250000");
        assertThat(metrics.volatility()).isEqualByComparingTo(
                antlab.common.money.MoneyMath.sampleStddev8(r));
        assertThat(metrics.maxDrawdown()).isEqualByComparingTo(PositionMeter.maxDrawdown(r));
    }

    @Test
    void 밴드는_정확히_플러스_마이너스_영점영일을_포함한다() {
        // 시장 ±0.01 은 오른·빠진 달로 센다, ±0.0099999 는 중립(참여율에서만 빠진다)
        List<BigDecimal> m = list("0.01", "0.01", "0.01", "-0.01", "-0.01", "-0.01", "0.0099999", "-0.0099999");
        List<BigDecimal> r = list("0.01", "0.01", "0.01", "-0.01", "-0.01", "-0.01", "0.5", "-0.5");
        Metrics metrics = measure(r, m);
        assertThat(metrics.monthsUsed()).isEqualTo(8);
        assertThat(metrics.nUp()).isEqualTo(3);
        assertThat(metrics.nDown()).isEqualTo(3);
        assertThat(metrics.up().toPlainString()).isEqualTo("1.00000000");
        assertThat(metrics.down().toPlainString()).isEqualTo("1.00000000");
    }

    @Test
    void 한쪽이_빠진_달은_사용_달_수에서_제외한다() {
        List<BigDecimal> m = list("0.02", "0.03", "0.01", "-0.01", "-0.02", "-0.05", "0.02", null);
        List<BigDecimal> r = list("0.04", "0.03", "0.02", "-0.005", "-0.01", "-0.03", null, "0.01");
        Metrics metrics = measure(r, m);
        assertThat(metrics.monthsUsed()).isEqualTo(6);
        assertThat(metrics.nUp()).isEqualTo(3);
        assertThat(metrics.reason()).isNull();
    }

    @Test
    void 판정_불능_사유는_달수_오른달_빠진달_영분모_순서이고_숫자는_모두_널이다() {
        List<BigDecimal> flat = list("0.001", "0.001", "0.001", "0.001", "0.001", "0.001");
        // 달 수 부족이 먼저 (오른 달도 부족하지만 MONTHS)
        Metrics months = measure(flat.subList(0, 5), flat.subList(0, 5));
        assertThat(months.reason()).isEqualTo(Reason.MONTHS);
        assertThat(months.up()).isNull();
        assertThat(months.down()).isNull();
        assertThat(months.volatility()).isNull();
        assertThat(months.maxDrawdown()).isNull();
        // 달 수는 충분, 오른 달 부족 → N_UP (빠진 달도 부족하지만 N_UP 이 먼저)
        assertThat(measure(flat, flat).reason()).isEqualTo(Reason.N_UP);
        // 오른 달 3, 빠진 달 부족 → N_DOWN
        List<BigDecimal> ups = list("0.02", "0.02", "0.02", "0.001", "0.001", "0.001");
        assertThat(measure(ups, ups).reason()).isEqualTo(Reason.N_DOWN);
        // 최소 달 수를 0·0 으로 낮추면 오른 달·빠진 달이 없을 때 분모가 비어 ZERO_DEN
        assertThat(PositionMeter.measure(flat, flat, 6, 0, 0).reason()).isEqualTo(Reason.ZERO_DEN);
        // 사유가 있는 지표도 센 값은 남긴다(진단용)
        assertThat(measure(ups, ups).nUp()).isEqualTo(3);
    }

    @Test
    void 최대_낙폭은_손_계산과_같고_고점_갱신과_빈_목록을_다룬다() {
        // W: 1.1, 0.88, 0.924 → 최고점 1.1 대비 0.88 → −0.2
        assertThat(PositionMeter.maxDrawdown(list("0.1", "-0.2", "0.05")).toPlainString()).isEqualTo("-0.20000000");
        // 계속 오르면 0 (t=0 의 낙폭 0 포함)
        assertThat(PositionMeter.maxDrawdown(list("0.1", "0.1")).toPlainString()).isEqualTo("0.00000000");
        // 고점 갱신 뒤 다시 빠지면 새 고점 기준: 1.5, 0.75(−50%), 1.5×0.5=0.75 → −0.5
        assertThat(PositionMeter.maxDrawdown(list("0.5", "-0.5")).toPlainString()).isEqualTo("-0.50000000");
        assertThat(PositionMeter.maxDrawdown(list())).isNull();
    }

    @Test
    void 원화_수익률과_시장_수익률은_정확한_곱과_합이다() {
        assertThat(PositionMeter.krwReturn(true, bd("0.05"), null)).isEqualByComparingTo("0.05");
        // (1 + 0.1)(1 − 0.1) − 1 = −0.01 (반올림 없는 정확한 곱)
        assertThat(PositionMeter.krwReturn(false, bd("0.1"), bd("-0.1"))).isEqualByComparingTo("-0.0100");
        assertThat(PositionMeter.marketReturn(bd("0.02"), bd("0.04"))).isEqualByComparingTo("0.03");
    }

    @Test
    void 음성_외화_종목의_환율_없음과_시장_대용_없음은_멈춘다() {
        assertThatThrownBy(() -> PositionMeter.krwReturn(false, bd("0.1"), null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PositionMeter.marketReturn(null, bd("0.1"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PositionMeter.marketReturn(bd("0.1"), null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PositionMeter.measure(list("0.1"), list("0.1", "0.2"), 6, 3, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PositionMeter.measure(list("0.1"), list("0.1"), 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 판정 문턱

    private static Position classify(String up, String down, String mdd, String mktMdd, boolean top, boolean bottom) {
        return PositionMeter.classify(bd(up), bd(down), bd(mdd), bd(mktMdd), top, bottom);
    }

    @Test
    void 골키퍼는_하위_변동성이고_하락_참여율이_영점이_이하다() {
        assertThat(classify("1", "0.2", "-0.1", "-0.1", false, true)).isEqualTo(Position.GOALKEEPER);
        // 0.20000001 이면 골키퍼가 아니고 수비수(< 0.6)
        assertThat(classify("1", "0.20000001", "-0.1", "-0.1", false, true)).isEqualTo(Position.DEFENDER);
        // 하위 변동성이 아니면 같은 숫자여도 수비수
        assertThat(classify("1", "0.2", "-0.1", "-0.1", false, false)).isEqualTo(Position.DEFENDER);
    }

    @Test
    void 수비수는_하락_참여율이_영점육_미만이다() {
        assertThat(classify("1", "0.59999999", "-0.1", "-0.1", false, false)).isEqualTo(Position.DEFENDER);
        // 정확히 0.6 은 수비수가 아니다 — 위쪽 가지로 넘어가 미드필더(상승 1.0 미만)
        assertThat(classify("0.9", "0.6", "-0.1", "-0.1", false, false)).isEqualTo(Position.MIDFIELDER);
    }

    @Test
    void 돌파형은_상위_변동성_상승_1점3_초과_낙폭이_시장의_1점5배_이하다() {
        assertThat(classify("1.30000001", "0.7", "-0.15", "-0.1", true, false)).isEqualTo(Position.FORWARD_BREAKOUT);
        // 상승 정확히 1.3 은 초과가 아니다 → 수비적(상승 ≥ 1.0, 하락 < 0.8)
        assertThat(classify("1.3", "0.7", "-0.15", "-0.1", true, false)).isEqualTo(Position.FORWARD_DEFENSIVE);
        // 낙폭이 시장의 1.5배보다 얕으면(−0.14) 돌파형이 아니다
        assertThat(classify("2", "0.7", "-0.14", "-0.1", true, false)).isEqualTo(Position.FORWARD_DEFENSIVE);
        // 상위 변동성이 아니면 돌파형이 아니다
        assertThat(classify("2", "0.7", "-0.5", "-0.1", false, false)).isEqualTo(Position.FORWARD_DEFENSIVE);
    }

    @Test
    void 수비적은_상승_1_이상_하락_영점팔_미만이다() {
        assertThat(classify("1.0", "0.79999999", "-0.1", "-0.1", false, false)).isEqualTo(Position.FORWARD_DEFENSIVE);
        assertThat(classify("0.99999999", "0.7", "-0.1", "-0.1", false, false)).isEqualTo(Position.MIDFIELDER);
    }

    @Test
    void 타겟형은_상승_1점1_초과_하락_1점2_이하_상위_변동성_아님이다() {
        assertThat(classify("1.10000001", "1.2", "-0.1", "-0.1", false, false)).isEqualTo(Position.FORWARD_TARGET);
        assertThat(classify("1.1", "1.2", "-0.1", "-0.1", false, false)).isEqualTo(Position.MIDFIELDER);
        assertThat(classify("1.5", "1.20000001", "-0.1", "-0.1", false, false)).isEqualTo(Position.MIDFIELDER);
        // 상위 변동성이면 타겟형이 아니다(돌파형 조건도 못 채우면 미드필더)
        assertThat(classify("1.2", "1.0", "-0.01", "-0.1", true, false)).isEqualTo(Position.MIDFIELDER);
    }

    @Test
    void 규칙_순서가_겹치는_숫자를_정한다() {
        // 하위 변동성·하락 0.1·상승 2.0: 골키퍼가 수비수·수비적보다 앞선다
        assertThat(classify("2", "0.1", "-0.5", "-0.1", false, true)).isEqualTo(Position.GOALKEEPER);
        // 수비수(하락 < 0.6)가 돌파형보다 앞선다
        assertThat(classify("2", "0.5", "-0.5", "-0.1", true, false)).isEqualTo(Position.DEFENDER);
        // 돌파형이 수비적보다 앞선다, 수비적이 타겟형보다 앞선다
        assertThat(classify("1.2", "0.7", "-0.5", "-0.1", false, false)).isEqualTo(Position.FORWARD_DEFENSIVE);
    }

    // ---------------------------------------------------------------- 풀 상대 순위

    private static Metrics judged(String vol, String up, String down, String mdd) {
        return new Metrics(10, 4, 4, bd(up), bd(down), bd(vol), bd(mdd), null);
    }

    private static Metrics unjudged() {
        return new Metrics(2, 0, 0, null, null, null, null, Reason.MONTHS);
    }

    /** 변동성이 서로 다른 N 개(판정 불능 3개 섞음) → 하위는 골키퍼로 드러나게(하락 0.1), 나머지는 수비수다. */
    private static Map<String, Position> bottomSet(int n) {
        Map<String, Metrics> pool = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            pool.put("p" + i, judged("0." + String.format("%02d", i), "1.0", "0.1", "-0.1"));
        }
        pool.put("x1", unjudged());
        pool.put("x2", unjudged());
        pool.put("x3", unjudged());
        return PositionMeter.classifyPool(pool, bd("-0.1"));
    }

    @Test
    void 상위_하위_개수는_올림_영점이_N_이고_판정_불능은_결과에서_빠진다() {
        // N=5 → k=ceil(1.0)=1, N=6 → ceil(1.2)=2, N=10 → 2, N=11 → ceil(2.2)=3
        for (int[] c : new int[][] {{5, 1}, {6, 2}, {10, 2}, {11, 3}, {1, 1}, {2, 1}}) {
            Map<String, Position> result = bottomSet(c[0]);
            assertThat(result).as("N=" + c[0]).hasSize(c[0]);
            assertThat(result.values().stream().filter(p -> p == Position.GOALKEEPER).count()).as("하위 수 N=" + c[0]).isEqualTo(c[1]);
            // 가장 낮은 변동성이 하위다
            assertThat(result.get("p1")).isEqualTo(Position.GOALKEEPER);
        }
        assertThat(bottomSet(5)).doesNotContainKeys("x1", "x2", "x3");
    }

    @Test
    void 상위는_높은_변동성_쪽이고_경계_동률은_모두_들어간다() {
        Map<String, Metrics> pool = new LinkedHashMap<>();
        // 상위(돌파형으로 드러나게): 상승 2.0, 하락 0.7(수비수 아님), 낙폭 −0.5(시장 −0.1 의 1.5배보다 깊음)
        // N=10 → k=2. 변동성 0.10(1위 동률 둘) → 둘 다 상위, 다음 0.09 는 3위로 상위가 아니다.
        pool.put("a", judged("0.10", "2.0", "0.7", "-0.5"));
        pool.put("b", judged("0.10", "2.0", "0.7", "-0.5"));
        pool.put("c", judged("0.09", "2.0", "0.7", "-0.5"));
        for (int i = 0; i < 7; i++) {
            pool.put("m" + i, judged("0.0" + (i + 1), "2.0", "0.7", "-0.5"));
        }
        Map<String, Position> result = PositionMeter.classifyPool(pool, bd("-0.1"));
        assertThat(result.get("a")).isEqualTo(Position.FORWARD_BREAKOUT);
        assertThat(result.get("b")).isEqualTo(Position.FORWARD_BREAKOUT);
        assertThat(result.get("c")).isEqualTo(Position.FORWARD_DEFENSIVE);
        // 동률로 k=2 를 넘어도(RANK 동률) 셋이 함께 상위가 되는 경우: 1위 동률 셋 → 모두 상위
        pool.put("c", judged("0.10", "2.0", "0.7", "-0.5"));
        Map<String, Position> ties = PositionMeter.classifyPool(pool, bd("-0.1"));
        assertThat(ties.get("a")).isEqualTo(Position.FORWARD_BREAKOUT);
        assertThat(ties.get("c")).isEqualTo(Position.FORWARD_BREAKOUT);
    }

    @Test
    void 빈_풀은_빈_결과다() {
        assertThat(PositionMeter.classifyPool(Map.<String, Metrics>of(), bd("-0.1"))).isEmpty();
    }
}

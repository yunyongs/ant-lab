package antlab.common.money;

import static antlab.common.money.MoneyMath.ReturnFactors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyMathTest {

    @Test
    void 가격과_수수료_반올림은_마지막에_한번만_한다() {
        assertThat(MoneyMath.initialPrice(bd("10.25"), bd("2"))).isEqualByComparingTo(bd("21"));
        assertThat(MoneyMath.priceAt(bd("10"), List.of(
                new ReturnFactors(bd("0.04"), bd("0")),
                new ReturnFactors(bd("0.04"), bd("0"))))).isEqualByComparingTo(bd("11"));
        assertThat(MoneyMath.priceAt(bd("100"), List.of(new ReturnFactors(bd("0.10"), bd("-0.10")))))
                .isEqualByComparingTo(bd("99"));
        assertThat(MoneyMath.fee(bd("10"), 1, bd("0.05"))).isEqualByComparingTo(bd("1"));
        assertThat(MoneyMath.fee(bd("10"), 1, bd("0.049"))).isEqualByComparingTo(bd("0"));
    }

    @Test
    void 이동평균_요율_수량_가능액을_계산한다() {
        assertThat(MoneyMath.avgPrice(2, bd("100"), 1, bd("101"))).isEqualByComparingTo(bd("100.3333"));
        assertThat(MoneyMath.avgPrice(1, bd("1.0001"), 1, bd("1"))).isEqualByComparingTo(bd("1.0001"));
        assertThat(MoneyMath.avgPrice(0, bd("0"), 2, bd("7"))).isEqualByComparingTo(bd("7.0000"));
        assertThat(MoneyMath.feeRate("KR", bd("0.003"), bd("0.004"), bd("0.001")))
                .isEqualByComparingTo(bd("0.00300000"));
        assertThat(MoneyMath.feeRate("US", bd("0.003"), bd("0.004"), bd("0.001")))
                .isEqualByComparingTo(bd("0.00500000"));
        assertThat(MoneyMath.feeRate("US", bd("0.7"), bd("0.6"), bd("0.6")))
                .isEqualByComparingTo(bd("1.20000000"));
        assertThat(MoneyMath.fee(bd("10"), 1, bd("1.2"))).isEqualByComparingTo(bd("12"));
        assertThat(MoneyMath.teamQty(bd("100"), bd("10"), bd("1.2"))).isEqualTo(4);
        assertThat(MoneyMath.affordable(bd("11"), bd("10"), bd("1"))).isTrue();
        assertThat(MoneyMath.affordable(bd("10"), bd("10"), bd("1"))).isFalse();
        assertThat(MoneyMath.teamQty(bd("11"), bd("5"), bd("0.10"))).isEqualTo(2);
        assertThat(MoneyMath.teamQty(bd("1"), bd("1"), bd("0.10"))).isZero();
    }

    @Test
    void 낙폭과_평가액을_계산한다() {
        assertThat(MoneyMath.drawdown(bd("2"), bd("3"))).isEqualByComparingTo(bd("-0.33333333"));
        assertThat(MoneyMath.drawdown(bd("199999999"), bd("200000000")))
                .isEqualByComparingTo(bd("-0.00000001"));
        assertThat(MoneyMath.drawdown(bd("0"), bd("3"))).isEqualByComparingTo(bd("-1.00000000"));
        assertThat(MoneyMath.drawdown(bd("3"), bd("3"))).isEqualByComparingTo(bd("0.00000000"));
        assertThat(MoneyMath.valuation(bd("7"), 2)).isEqualByComparingTo(bd("14"));
        assertThat(MoneyMath.totalValue(bd("3"), List.of(bd("14"), bd("5"))))
                .isEqualByComparingTo(bd("22"));
    }

    @Test
    void 백이십턴의_정확한_곱을_독립_유리수와_대조한다() {
        List<ReturnFactors> returns = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            returns.add(new ReturnFactors(bd("0.00000001"), bd("0.00000002")));
        }
        BigInteger numerator = BigInteger.valueOf(100_000_000_000_000L)
                .multiply(BigInteger.valueOf(100_000_001).pow(120))
                .multiply(BigInteger.valueOf(100_000_002).pow(120));
        BigInteger denominator = BigInteger.valueOf(100_000_000).pow(240);
        BigInteger expected = numerator.multiply(BigInteger.TWO).add(denominator)
                .divide(denominator.multiply(BigInteger.TWO));
        assertThat(MoneyMath.priceAt(bd("100000000000000"), returns))
                .isEqualByComparingTo(new BigDecimal(expected));

        returns.replaceAll(ignored -> new ReturnFactors(bd("0.25"), bd("0")));
        for (int i = 1; i < returns.size(); i += 2) {
            returns.set(i, new ReturnFactors(bd("-0.20"), bd("0")));
        }
        assertThat(MoneyMath.priceAt(bd("100"), returns)).isEqualByComparingTo(bd("100"));
    }

    @Test
    void 잘못된_입력은_값을_노출하지_않고_거부한다() {
        assertThatThrownBy(() -> MoneyMath.priceAt(bd("10"), List.of(new ReturnFactors(null, bd("0")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.priceAt(bd("10"), List.of(new ReturnFactors(bd("-1.00000001"), bd("0")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.priceAt(bd("10"), List.of(new ReturnFactors(bd("0"), bd("-1")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.feeRate("JP", bd("0.1"), bd("0.1"), bd("0.1")))
                .hasMessageContaining("시장 코드");
        assertThatThrownBy(() -> MoneyMath.avgPrice(1, bd("0"), 1, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.drawdown(bd("1"), bd("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.fee(bd("10.0001"), 1, bd("0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.totalValue(bd("1"), List.of(bd("-1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.initialPrice(bd("999999999999999"), bd("2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.totalValue(bd("999999999999999"), List.of(bd("1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MoneyMath.totalValue(bd("999999999999998"), List.of(bd("1"))))
                .isEqualByComparingTo(bd("999999999999999"));
        // 메시지에는 입력 값이 들어가지 않는다.
        assertThatThrownBy(() -> MoneyMath.priceAt(bd("10"), List.of(new ReturnFactors(bd("-1.00000001"), bd("0")))))
                .hasMessageNotContaining("1.00000001");
        assertThatThrownBy(() -> MoneyMath.fee(bd("10.0001"), 1, bd("0.1")))
                .hasMessageNotContaining("10.0001");
        assertThatThrownBy(() -> MoneyMath.totalValue(bd("1"), List.of(bd("-7654321"))))
                .hasMessageNotContaining("7654321");
    }

    @Test
    void 경계_입력은_계약대로_받는다() {
        BigDecimal zero = MoneyMath.priceAt(bd("10"), List.of(new ReturnFactors(bd("-1"), bd("0"))));
        assertThat(zero).isEqualByComparingTo(bd("0"));
        assertThat(zero.scale()).isZero();
        assertThat(MoneyMath.priceAt(bd("10"), List.of())).isEqualByComparingTo(bd("10"));
        assertThat(MoneyMath.totalValue(bd("5"), List.of())).isEqualByComparingTo(bd("5"));
        assertThat(MoneyMath.feeRate("KR", bd("0.0300000000"), bd("0.1"), bd("0.1")))
                .isEqualByComparingTo(bd("0.03"));
    }

    @Test
    void 범위_밖_입력과_넘침을_거부한다() {
        assertThatThrownBy(() -> MoneyMath.priceAt(bd("10"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.totalValue(bd("1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.feeRate("KR", bd("1"), bd("0.1"), bd("0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.feeRate("US", bd("0.1"), bd("0.1"), bd("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.feeRate("KR", bd("0.000000001"), bd("0.1"), bd("0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.avgPrice(Integer.MAX_VALUE, bd("5"), 1, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.teamQty(bd("999999999999999"), bd("1"), bd("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.avgPrice(0, bd("5"), 1, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 입력_목록은_변경하지_않고_반환_scale을_고정한다() {
        List<ReturnFactors> path = new ArrayList<>(List.of(new ReturnFactors(bd("0"), bd("0"))));
        MoneyMath.priceAt(bd("10"), path);
        assertThat(path).containsExactly(new ReturnFactors(bd("0"), bd("0")));
        assertThat(MoneyMath.fee(bd("10"), 1, bd("0.05")).scale()).isZero();
        assertThat(MoneyMath.avgPrice(0, bd("0"), 2, bd("7")).scale()).isEqualTo(4);
        assertThat(MoneyMath.drawdown(bd("3"), bd("3")).scale()).isEqualTo(8);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 응답용_scale_바꾸기는_값을_바꾸지_않고_자릿수만_맞춘다() {
        assertThat(MoneyMath.exactWon(bd("30000000.0000")).toPlainString()).isEqualTo("30000000");
        assertThat(MoneyMath.exactWon(bd("3E+7")).toPlainString()).isEqualTo("30000000");
        assertThat(MoneyMath.exactRate(bd("-0.2")).toPlainString()).isEqualTo("-0.20000000");
        assertThat(MoneyMath.exactRate(bd("0.00015")).toPlainString()).isEqualTo("0.00015000");
        assertThat(MoneyMath.exactRate(bd("0E-8")).toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 응답용_scale_바꾸기는_값이_바뀌면_반올림하지_않고_거부한다() {
        assertThatThrownBy(() -> MoneyMath.exactWon(bd("100.5"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("100.5");
        assertThatThrownBy(() -> MoneyMath.exactRate(bd("0.123456789"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.exactWon(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.exactRate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 평균단가_응답_scale은_4자리로_맞추고_5자리_유효값은_거부한다() {
        assertThat(MoneyMath.exactAvgPrice(bd("165000")).toPlainString()).isEqualTo("165000.0000");
        assertThat(MoneyMath.exactAvgPrice(bd("150000.5000")).toPlainString()).isEqualTo("150000.5000");
        assertThat(MoneyMath.exactAvgPrice(bd("1.2E+5")).toPlainString()).isEqualTo("120000.0000");
        assertThatThrownBy(() -> MoneyMath.exactAvgPrice(bd("1.00005"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("1.00005");
        assertThatThrownBy(() -> MoneyMath.exactAvgPrice(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 실현_손익은_체결가와_평균단가의_차이에_수량을_곱해_소수_4자리로_낸다() {
        assertThat(MoneyMath.realizedPnl(bd("160000"), bd("150000.0000"), 4).toPlainString()).isEqualTo("40000.0000");
        assertThat(MoneyMath.realizedPnl(bd("30000"), bd("35000"), 40).toPlainString()).isEqualTo("-200000.0000");
        assertThat(MoneyMath.realizedPnl(bd("150000"), bd("150000.0000"), 1).toPlainString()).isEqualTo("0.0000");
        assertThat(MoneyMath.realizedPnl(bd("150001"), bd("150000.6667"), 3).toPlainString()).isEqualTo("0.9999");
        assertThat(MoneyMath.realizedPnl(bd("1"), bd("0.0001"), 2).toPlainString()).isEqualTo("1.9998");
        // 큰 수량 경계: NUMERIC(19,4) 상한 안은 받고, 넘으면 값 없이 거부한다
        assertThat(MoneyMath.realizedPnl(bd("999999999"), bd("1"), 1_000_000).scale()).isEqualTo(4);
        assertThatThrownBy(() -> MoneyMath.realizedPnl(bd("999999999999999"), bd("1"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("999999999999999");
    }

    @Test
    void 실현_손익의_잘못된_입력은_거부한다() {
        assertThatThrownBy(() -> MoneyMath.realizedPnl(bd("100.5"), bd("1"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.realizedPnl(bd("100"), bd("0"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.realizedPnl(bd("100"), bd("1.00001"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.realizedPnl(bd("100"), bd("1"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.realizedPnl(null, bd("1"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.realizedPnl(bd("100"), null, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 실현_손익_응답_scale은_4자리이고_매수의_null은_그대로_null이다() {
        assertThat(MoneyMath.exactPnl(bd("40000")).toPlainString()).isEqualTo("40000.0000");
        assertThat(MoneyMath.exactPnl(bd("-1.5")).toPlainString()).isEqualTo("-1.5000");
        assertThat(MoneyMath.exactPnl(null)).isNull();
        assertThatThrownBy(() -> MoneyMath.exactPnl(bd("1.00005"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("1.00005");
    }
}

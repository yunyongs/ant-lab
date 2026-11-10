package antlab.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 계좌·보유의 상태 변경 불변식(DB 없음). */
class AccountStateTest {

    @Test
    void 새_계좌는_잔액_0이고_원장_금액을_더하고_새_잔액을_돌려준다() {
        CashAccount a = new CashAccount(1L);
        assertThat(a.getBalance()).isEqualByComparingTo("0");
        assertThat(a.getVersion()).as("저장 전 버전은 null").isNull();
        assertThat(a.applyLedgerAmount(new BigDecimal("10000000"))).isEqualByComparingTo("10000000");
        assertThat(a.applyLedgerAmount(new BigDecimal("-2500000"))).isEqualByComparingTo("7500000");
        assertThat(a.getBalance()).isEqualByComparingTo("7500000");
    }

    @Test
    void 잔액이_음수가_되는_금액은_거부하고_잔액은_그대로다() {
        CashAccount a = new CashAccount(1L);
        a.applyLedgerAmount(new BigDecimal("1000"));
        assertThatThrownBy(() -> a.applyLedgerAmount(new BigDecimal("-1001"))).isInstanceOf(IllegalStateException.class);
        assertThat(a.getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void 원_단위가_아닌_금액은_반올림하지_않고_거부한다() {
        CashAccount a = new CashAccount(1L);
        assertThatThrownBy(() -> a.applyLedgerAmount(new BigDecimal("0.5"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> a.applyLedgerAmount(new BigDecimal("1000.0001"))).isInstanceOf(IllegalStateException.class);
        assertThat(a.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void 소수_0으로_끝나는_원_단위_금액은_받는다() {
        CashAccount a = new CashAccount(1L);
        assertThat(a.applyLedgerAmount(new BigDecimal("1000.0000"))).isEqualByComparingTo("1000");
        assertThat(a.applyLedgerAmount(new BigDecimal("1E+3"))).isEqualByComparingTo("2000");
    }

    @Test
    void 매수는_수량을_늘리고_평균단가를_받아_적는다() {
        Holding h = new Holding(1L, 2L);
        assertThat(h.getQuantity()).isZero();
        assertThat(h.getAvgPrice()).isEqualByComparingTo("0");
        h.applyBuy(10, new BigDecimal("1500.0000"));
        h.applyBuy(5, new BigDecimal("1600.0000"));
        assertThat(h.getQuantity()).isEqualTo(15);
        assertThat(h.getAvgPrice()).isEqualByComparingTo("1600");
    }

    @Test
    void 매수_수량이_0_이하이거나_평균단가가_0_이하면_거부한다() {
        Holding h = new Holding(1L, 2L);
        assertThatThrownBy(() -> h.applyBuy(0, new BigDecimal("1"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> h.applyBuy(1, BigDecimal.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThat(h.getQuantity()).isZero();
    }

    @Test
    void 수량이_int_한도를_넘는_매수는_거부하고_수량과_평균단가는_그대로다() {
        Holding h = new Holding(1L, 2L);
        h.applyBuy(Integer.MAX_VALUE, new BigDecimal("1500"));
        assertThatThrownBy(() -> h.applyBuy(1, new BigDecimal("1600")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(h.getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(h.getAvgPrice()).isEqualByComparingTo("1500");
    }

    @Test
    void 평균단가가_소수_4자리를_넘으면_거부하고_끝자리_0은_허용한다() {
        Holding h = new Holding(1L, 2L);
        assertThatThrownBy(() -> h.applyBuy(1, new BigDecimal("1500.00001")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(h.getQuantity()).isZero();
        h.applyBuy(1, new BigDecimal("1500.123400000"));
        assertThat(h.getAvgPrice()).isEqualByComparingTo("1500.1234");
    }

    @Test
    void 보유보다_많이_팔_수_없고_전량_매도하면_평균단가가_0이_된다() {
        Holding h = new Holding(1L, 2L);
        h.applyBuy(10, new BigDecimal("1500"));
        assertThatThrownBy(() -> h.applySell(11)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> h.applySell(0)).isInstanceOf(IllegalStateException.class);
        h.applySell(4);
        assertThat(h.getQuantity()).isEqualTo(6);
        assertThat(h.getAvgPrice()).as("부분 매도는 평균단가 유지").isEqualByComparingTo("1500");
        h.applySell(6);
        assertThat(h.getQuantity()).isZero();
        assertThat(h.getAvgPrice()).isEqualByComparingTo("0");
    }
}

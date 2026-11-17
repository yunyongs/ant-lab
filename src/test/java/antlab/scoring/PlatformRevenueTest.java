package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.scoring.PlatformRevenue.SourceRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformRevenueTest {

    private static BehaviourOrder order(int turn, OrderSource source, long notional, long fee) {
        return new BehaviourOrder(1, turn, OrderSide.BUY, 1, source, BigDecimal.valueOf(notional), BigDecimal.valueOf(fee),
                BigDecimal.TEN, null);
    }

    @Test
    void fiveRowsInFixedOrderAndASourceWithoutOrdersHasZeros() {
        PlatformRevenue.Result r = PlatformRevenue.of(5, List.of(order(0, OrderSource.DIRECT, 1000, 7),
                order(1, OrderSource.RANKING, 2000, 20), order(2, OrderSource.RANKING, 1000, 10),
                order(3, OrderSource.DIRECT, 500, 5)));
        assertThat(r.bySource()).extracting(SourceRow::source).containsExactly(OrderSource.RANKING, OrderSource.FEED,
                OrderSource.ALERT, OrderSource.BENCH, OrderSource.DIRECT);
        SourceRow ranking = r.bySource().get(0);
        assertThat(ranking.orders()).isEqualTo(2);
        assertThat(ranking.tradeNotional().toPlainString()).isEqualTo("3000");
        assertThat(ranking.fee().toPlainString()).isEqualTo("30");
        for (int i : new int[] {1, 2, 3}) {
            assertThat(r.bySource().get(i).orders()).isZero();
            assertThat(r.bySource().get(i).tradeNotional().toPlainString()).isEqualTo("0");
            assertThat(r.bySource().get(i).fee().toPlainString()).isEqualTo("0");
        }
        assertThat(r.bySource().get(4).fee().toPlainString()).as("the team purchase is not DIRECT of turns 1..T-1").isEqualTo("5");
        assertThat(r.teamFee().toPlainString()).isEqualTo("7");
        assertThat(r.totalFee().toPlainString()).isEqualTo("42");
    }

    @Test
    void theNudgedFeeIsRankingPlusAlertAndNeverFeed() {
        PlatformRevenue.Result r = PlatformRevenue.of(4, List.of(order(1, OrderSource.RANKING, 1000, 10),
                order(1, OrderSource.ALERT, 1000, 20), order(2, OrderSource.FEED, 1000, 40), order(2, OrderSource.DIRECT, 1000, 80)));
        assertThat(r.nudgedFee().toPlainString()).isEqualTo("30");
        assertThat(r.nudgedFeeShare().toPlainString()).isEqualTo("0.20000000");
        assertThat(r.totalFee().toPlainString()).isEqualTo("150");
        assertThat(r.teamFee().toPlainString()).isEqualTo("0");
    }

    @Test
    void aZeroTotalFeeHasNoShare() {
        PlatformRevenue.Result r = PlatformRevenue.of(3, List.of());
        assertThat(r.totalFee().toPlainString()).isEqualTo("0");
        assertThat(r.nudgedFee().toPlainString()).isEqualTo("0");
        assertThat(r.nudgedFeeShare()).isNull();
        assertThat(r.bySource()).hasSize(5);
    }

    @Test
    void anOrderAtTheLastTurnOrWithNegativeFeeIsRefused() {
        assertThatThrownBy(() -> PlatformRevenue.of(3, List.of(order(3, OrderSource.DIRECT, 100, 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlatformRevenue.of(3, List.of(order(1, OrderSource.DIRECT, 100, -1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlatformRevenue.of(3, List.of(order(1, OrderSource.DIRECT, 0, 1))))
                .isInstanceOf(IllegalStateException.class);
    }
}

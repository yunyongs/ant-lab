package antlab.scoring;

import antlab.account.OrderSource;
import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fees of a finished season split by where the order came from (MVP-19, PLAN §13 P0-20 Q2): a pure function of the filled orders.
 * Turn 0 orders are the team purchase and make the team fee; orders of turns 1..T-1 are grouped by source in a fixed order, so the list
 * never changes shape. The platform's nudged part is the fee of the sources in {@link ReportRules#PLATFORM_SOURCES} only.
 */
public final class PlatformRevenue {

    /** Fixed row order of the per-source list (every source, even with no order). */
    public static final List<OrderSource> SOURCE_ORDER = List.of(OrderSource.RANKING, OrderSource.FEED, OrderSource.ALERT,
            OrderSource.BENCH, OrderSource.DIRECT);

    private PlatformRevenue() {
    }

    public record SourceRow(OrderSource source, int orders, BigDecimal tradeNotional, BigDecimal fee) {
    }

    /**
     * @param totalFee       team fee plus every source's fee, whole won (the report's total fee)
     * @param teamFee        fees of turn 0 orders
     * @param bySource       five rows in {@link #SOURCE_ORDER}, turns 1..T-1
     * @param nudgedFee      fee of the platform sources
     * @param nudgedFeeShare nudged fee ÷ total fee, null when the total is 0
     */
    public record Result(BigDecimal totalFee, BigDecimal teamFee, List<SourceRow> bySource, BigDecimal nudgedFee,
            BigDecimal nudgedFeeShare) {
    }

    /** @throws IllegalStateException when an order is at turn T or later or has a negative fee or a non-positive amount */
    public static Result of(int reportTurn, List<BehaviourOrder> orders) {
        BigDecimal team = BigDecimal.ZERO;
        int[] counts = new int[SOURCE_ORDER.size()];
        BigDecimal[] notionals = new BigDecimal[SOURCE_ORDER.size()];
        BigDecimal[] fees = new BigDecimal[SOURCE_ORDER.size()];
        Arrays.fill(notionals, BigDecimal.ZERO);
        Arrays.fill(fees, BigDecimal.ZERO);
        for (BehaviourOrder order : orders) {
            if (order.turnNo() < 0 || order.turnNo() >= reportTurn) {
                throw inconsistent("보고 범위 밖의 주문이 있다");
            }
            if (order.fee() == null || order.fee().signum() < 0 || order.notional() == null || order.notional().signum() <= 0) {
                throw inconsistent("주문의 수수료나 대금이 범위를 벗어났다");
            }
            if (order.turnNo() == 0) {
                team = team.add(order.fee());
                continue;
            }
            int i = SOURCE_ORDER.indexOf(order.source());
            counts[i]++;
            notionals[i] = notionals[i].add(order.notional());
            fees[i] = fees[i].add(order.fee());
        }
        List<SourceRow> rows = new ArrayList<>();
        BigDecimal total = team;
        BigDecimal nudged = BigDecimal.ZERO;
        for (int i = 0; i < SOURCE_ORDER.size(); i++) {
            OrderSource source = SOURCE_ORDER.get(i);
            rows.add(new SourceRow(source, counts[i], MoneyMath.exactWon(notionals[i]), MoneyMath.exactWon(fees[i])));
            total = total.add(fees[i]);
            if (ReportRules.PLATFORM_SOURCES.contains(source.name())) {
                nudged = nudged.add(fees[i]);
            }
        }
        BigDecimal totalWon = MoneyMath.exactWon(total);
        BigDecimal nudgedWon = MoneyMath.exactWon(nudged);
        BigDecimal share = totalWon.signum() == 0 ? null : MoneyMath.ratio(nudgedWon, totalWon);
        return new Result(totalWon, MoneyMath.exactWon(team), rows, nudgedWon, share);
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("플랫폼 수익 원천이 모순이다: " + reason);
    }
}

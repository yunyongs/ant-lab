package antlab.scoring;

import antlab.asset.Position;
import antlab.common.money.MoneyMath;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turn contribution calculator — a pure function over stored values. Money is integer arithmetic (prices and
 * valuations are whole won), so nothing is rounded; every ratio is one {@link MoneyMath#ratio} call. The benchmark
 * comes from {@link Phase0MarketBaseline}, the same code as the season report.
 *
 * <p>The holdings rebuilt from the fills must give the stored turn row back (holdings value and the defender,
 * midfielder and attacker sums), and the stored change must equal holdings contribution − trading cost; otherwise the
 * sources contradict each other and it fails with a fixed message (no amounts, numbers or dates).
 */
public final class TurnContributionCalculator {

    private TurnContributionCalculator() {
    }

    /**
     * One asset held through the move into the turn. {@code openSpans} is the number of declared spans open at the
     * turn (exactly one is consistent); prices are null when the turn price is missing.
     */
    public record Line(long assetId, long quantity, BigDecimal previousPrice, BigDecimal price, int openSpans,
            Position declared) {
    }

    /** Stored valuations of one turn row: total, holdings and the four positions. */
    public record Values(BigDecimal total, BigDecimal holdings, BigDecimal goalkeeper, BigDecimal defender,
            BigDecimal midfielder, BigDecimal forward) {
    }

    /** Season facts the benchmark needs. Rates have 8 decimal places. */
    public record SeasonTerms(long seasonId, BigDecimal initialCash, BigDecimal feeRateKr, BigDecimal feeRateUs,
            BigDecimal fxSpreadRate) {
    }

    /**
     * @param previous stored row of turn k − 1, or null at turn 1 (then the start is all cash: initial capital)
     * @param benchmarkPrices turn 0..k prices of the two benchmark proxies
     */
    public static TurnContributionDto calculate(SeasonTerms season, int turnNo, Values previous, Values current,
            List<Line> lines, BigDecimal tradingCost, Map<AssetKey, List<BigDecimal>> benchmarkPrices) {
        BigDecimal initialCash = season.initialCash();
        Values before = previous != null ? previous
                : new Values(initialCash, BigDecimal.ZERO, initialCash, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal previousTotal = before.total();

        BigDecimal[] valueBySlot = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] contributionBySlot = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal holdingsValue = BigDecimal.ZERO;
        List<HoldingContributionDto> holdings = new ArrayList<>();
        for (Line line : lines) {
            if (line.quantity() < 0) {
                throw inconsistent("체결 이력의 누적 수량이 음수다");
            }
            if (line.previousPrice() == null || line.price() == null) {
                throw inconsistent("보유 종목의 턴 가격이 없다");
            }
            if (line.openSpans() != 1 || line.declared() == null) {
                throw inconsistent("보유한 턴에 열린 선언 구간이 하나가 아니다");
            }
            int quantity = Math.toIntExact(line.quantity());
            BigDecimal value = MoneyMath.valuation(line.price(), quantity);
            BigDecimal contribution = value.subtract(MoneyMath.valuation(line.previousPrice(), quantity));
            int slot = slot(line.declared());
            valueBySlot[slot] = valueBySlot[slot].add(value);
            contributionBySlot[slot] = contributionBySlot[slot].add(contribution);
            holdingsValue = holdingsValue.add(value);
            holdings.add(new HoldingContributionDto(line.assetId(), line.declared(), quantity,
                    MoneyMath.exactWon(line.previousPrice()), MoneyMath.exactWon(line.price()), MoneyMath.exactWon(value),
                    MoneyMath.exactWon(contribution),
                    MoneyMath.ratio(line.price().subtract(line.previousPrice()), line.previousPrice()),
                    MoneyMath.ratio(contribution, previousTotal)));
        }
        if (holdingsValue.compareTo(current.holdings()) != 0 || valueBySlot[1].compareTo(current.defender()) != 0
                || valueBySlot[2].compareTo(current.midfielder()) != 0 || valueBySlot[3].compareTo(current.forward()) != 0) {
            throw inconsistent("체결로 다시 만든 보유가 턴 스냅샷과 다르다");
        }
        holdings.sort(Comparator.comparing(HoldingContributionDto::contribution).reversed()
                .thenComparing(HoldingContributionDto::assetId));
        BigDecimal holdingsContribution = contributionBySlot[0].add(contributionBySlot[1]).add(contributionBySlot[2])
                .add(contributionBySlot[3]);

        BigDecimal total = current.total();
        // Holds while fills are at the turn price and orders are the only cash moves; a deposit or adjustment needs its own term.
        if (total.subtract(previousTotal).compareTo(holdingsContribution.subtract(tradingCost)) != 0) {
            throw inconsistent("평가액 변화가 기여 합 − 매매 비용과 다르다");
        }
        PositionContributionsDto positions = new PositionContributionsDto(
                position(current.goalkeeper(), before.goalkeeper(), contributionBySlot[0], previousTotal),
                position(current.defender(), before.defender(), contributionBySlot[1], previousTotal),
                position(current.midfielder(), before.midfielder(), contributionBySlot[2], previousTotal),
                position(current.forward(), before.forward(), contributionBySlot[3], previousTotal));
        return new TurnContributionDto(season.seasonId(), turnNo, MoneyMath.exactWon(previousTotal),
                MoneyMath.exactWon(total), MoneyMath.exactWon(total.subtract(previousTotal)),
                MoneyMath.exactWon(holdingsContribution), MoneyMath.exactWon(tradingCost),
                MoneyMath.ratio(total.subtract(initialCash), initialCash), positions, List.copyOf(holdings),
                benchmark(season, turnNo, total, benchmarkPrices));
    }

    private static BenchmarkToDateDto benchmark(SeasonTerms season, int turnNo, BigDecimal teamTotal,
            Map<AssetKey, List<BigDecimal>> prices) {
        for (AssetKey key : List.of(Phase0MarketBaseline.BENCH_KR, Phase0MarketBaseline.BENCH_US)) {
            List<BigDecimal> series = prices.get(key);
            if (series == null || series.size() != turnNo + 1 || series.stream().anyMatch(p -> p == null)) {
                throw inconsistent("기준선 대용 종목이 시즌 풀 또는 가격에 없다");
            }
        }
        BigDecimal initialCash = season.initialCash();
        BigDecimal value = valueAt(season, prices, turnNo);
        BigDecimal previous = turnNo == 1 ? initialCash : valueAt(season, prices, turnNo - 1);
        BigDecimal change = value.subtract(previous);
        return new BenchmarkToDateDto(MoneyMath.exactWon(value), MoneyMath.exactWon(previous), MoneyMath.exactWon(change),
                MoneyMath.ratio(change, previous), MoneyMath.ratio(value.subtract(initialCash), initialCash),
                MoneyMath.ratio(teamTotal.subtract(value), initialCash));
    }

    private static BigDecimal valueAt(SeasonTerms season, Map<AssetKey, List<BigDecimal>> prices, int turnNo) {
        return Phase0MarketBaseline.valueAt(season.initialCash(), season.feeRateKr(), season.feeRateUs(),
                season.fxSpreadRate(), prices, turnNo);
    }

    private static PositionContributionDto position(BigDecimal value, BigDecimal previousValue, BigDecimal contribution,
            BigDecimal previousTotal) {
        return new PositionContributionDto(MoneyMath.exactWon(value), MoneyMath.exactWon(previousValue),
                MoneyMath.exactWon(value.subtract(previousValue)), MoneyMath.exactWon(contribution),
                MoneyMath.ratio(contribution, previousTotal));
    }

    /** 0 goalkeeper, 1 defender, 2 midfielder, 3 attacker (the three attacker kinds together, as in the turn row). */
    private static int slot(Position position) {
        return switch (position) {
            case GOALKEEPER -> 0;
            case DEFENDER -> 1;
            case MIDFIELDER -> 2;
            case FORWARD_TARGET, FORWARD_BREAKOUT, FORWARD_DEFENSIVE -> 3;
        };
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("턴 기여 원천이 모순이다: " + reason);
    }
}

package antlab.scoring;

import antlab.asset.Position;
import antlab.common.money.MoneyMath;
import antlab.season.SeasonPathReturns;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 배열 입력 포지션 측정기 — SQL 정의 {@code db/12_calc_position_def.sql} 의 {@code pos_metrics}·{@code pos_max_drawdown}·
 * {@code pos_block_mkt}·{@code pos_classify} 와 풀 상대 순위(M13)의 Java 짝이다. 입력은 월 수익률 배열이고 날짜·월 블록 번호를 모른다.
 * 성적표가 시즌 경로(턴 순서)에서 실측 포지션을 잴 때 쓰고, 기준일 창 판정과 같은 식이라 기준일 풀 전 종목에서 저장값과 같음을 골든으로 확인한다.
 * 반올림은 8자리 한 번씩이며 40자리 올림 나눗셈의 자릿수는 {@link MoneyMath#pad40Divide} 가 PostgreSQL 규칙으로 따른다.
 */
public final class PositionMeter {

    /** 판정 불능 사유. 선언 순서가 곧 판정 순서다(SQL 의 CASE 순서). */
    public enum Reason { MONTHS, N_UP, N_DOWN, ZERO_DEN }

    /**
     * 종목 하나의 지표. 판정 불능이면 {@code up}·{@code down}·{@code volatility}·{@code maxDrawdown} 이 모두 null 이다(전부-또는-없음).
     */
    public record Metrics(int monthsUsed, int nUp, int nDown, BigDecimal up, BigDecimal down, BigDecimal volatility,
            BigDecimal maxDrawdown, Reason reason) {

        public boolean judgeable() {
            return reason == null;
        }
    }

    private PositionMeter() {
    }

    /**
     * 시장 수익률 한 달 = 0.5 × 국내 대용 + 0.5 × 미국 대용(원화). 둘 중 하나라도 없으면 0으로 덮지 않고 예외다(SQL {@code pos_block_mkt}).
     */
    public static BigDecimal marketReturn(BigDecimal krReturn, BigDecimal usKrwReturn) {
        if (krReturn == null || usKrwReturn == null) {
            throw new IllegalStateException("시장 대용 수익률이 없다");
        }
        return ReportRules.BENCH_KR_WEIGHT.multiply(krReturn).add(ReportRules.BENCH_US_WEIGHT.multiply(usKrwReturn));
    }

    /**
     * 원화 월 수익률: 원화 종목은 현지 수익률 그대로, 외화 종목은 (1 + 현지)(1 + 환율 변화) − 1 (정확한 곱, 반올림 없음, 12 의 M4).
     * 외화 종목인데 환율 변화가 없으면 예외다.
     */
    public static BigDecimal krwReturn(boolean krwCurrency, BigDecimal localReturn, BigDecimal fxChange) {
        Objects.requireNonNull(localReturn, "현지 수익률");
        return SeasonPathReturns.krwReturn(krwCurrency, localReturn, fxChange);
    }

    /**
     * 최대 낙폭: W0 = 1, Wt = Wt−1 × (1 + rt), 최고점은 W0 를 포함한 누적 최댓값, 낙폭 = (W + 0.0…0) ÷ 최고점 − 1 의 최솟값(0 포함), 8자리 한 번.
     * 빈 목록이면 null 이다.
     */
    public static BigDecimal maxDrawdown(List<BigDecimal> returns) {
        Objects.requireNonNull(returns, "수익률 목록");
        if (returns.isEmpty()) {
            return null;
        }
        BigDecimal wealth = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal min = BigDecimal.ZERO;
        for (BigDecimal r : returns) {
            Objects.requireNonNull(r, "수익률");
            wealth = wealth.multiply(BigDecimal.ONE.add(r));
            if (wealth.compareTo(peak) > 0) {
                peak = wealth;
            }
            BigDecimal drawdown = MoneyMath.pad40Divide(wealth, peak).subtract(BigDecimal.ONE);
            if (drawdown.compareTo(min) < 0) {
                min = drawdown;
            }
        }
        return MoneyMath.round8(min);
    }

    /**
     * 종목 월 수익률 {@code returns} 와 같은 달들의 시장 수익률 {@code market}(같은 길이·같은 순서)로 지표를 잰다.
     * 둘 다 값이 있는 달만 센다(한쪽이 null 이면 그 달은 빠진다). 최소 달 수에 못 미치면 사유와 함께 판정 불능이다.
     *
     * @param minMonths 사용 달 수 최소(기준일 판정 24, 시즌 6)
     */
    public static Metrics measure(List<BigDecimal> returns, List<BigDecimal> market, int minMonths, int minUp, int minDown) {
        Objects.requireNonNull(returns, "종목 수익률");
        Objects.requireNonNull(market, "시장 수익률");
        if (returns.size() != market.size()) {
            throw new IllegalArgumentException("종목과 시장의 수익률 길이가 다르다");
        }
        if (minMonths < 2) {
            throw new IllegalArgumentException("최소 달 수는 2 이상이어야 한다(표본 표준편차)");
        }
        List<BigDecimal> r = new java.util.ArrayList<>();
        List<BigDecimal> m = new java.util.ArrayList<>();
        for (int i = 0; i < returns.size(); i++) {
            if (returns.get(i) != null && market.get(i) != null) {
                r.add(returns.get(i));
                m.add(market.get(i));
            }
        }
        int nUp = 0;
        int nDown = 0;
        BigDecimal sumRUp = null;
        BigDecimal sumMUp = null;
        BigDecimal sumRDown = null;
        BigDecimal sumMDown = null;
        for (int i = 0; i < r.size(); i++) {
            if (m.get(i).compareTo(ReportRules.BAND) >= 0) {
                nUp++;
                sumRUp = sumRUp == null ? r.get(i) : sumRUp.add(r.get(i));
                sumMUp = sumMUp == null ? m.get(i) : sumMUp.add(m.get(i));
            }
            if (m.get(i).compareTo(ReportRules.BAND.negate()) <= 0) {
                nDown++;
                sumRDown = sumRDown == null ? r.get(i) : sumRDown.add(r.get(i));
                sumMDown = sumMDown == null ? m.get(i) : sumMDown.add(m.get(i));
            }
        }
        int months = r.size();
        Reason reason = null;
        if (months < minMonths) {
            reason = Reason.MONTHS;
        } else if (nUp < minUp) {
            reason = Reason.N_UP;
        } else if (nDown < minDown) {
            reason = Reason.N_DOWN;
        } else if (isZeroOrNull(sumMUp) || isZeroOrNull(sumMDown)) {
            reason = Reason.ZERO_DEN;
        }
        if (reason != null) {
            return new Metrics(months, nUp, nDown, null, null, null, null, reason);
        }
        BigDecimal up = MoneyMath.round8(MoneyMath.pad40Divide(sumRUp, sumMUp));
        BigDecimal down = MoneyMath.round8(MoneyMath.pad40Divide(sumRDown, sumMDown));
        BigDecimal volatility = MoneyMath.sampleStddev8(r);
        return new Metrics(months, nUp, nDown, up, down, volatility, maxDrawdown(r), null);
    }

    private static boolean isZeroOrNull(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    /**
     * 풀 상대 순위로 포지션을 판정한다. 판정 가능한 N 개 중 k = CEIL(0.20 × N), 변동성 순위(RANK, 동률은 같은 순위)가 k 이내면
     * 상위/하위다(경계 동률은 모두 들어간다). 비교는 저장되는 8자리 변동성으로 한다. 판정 불능 종목은 결과에 없다.
     *
     * @param marketMaxDrawdown 같은 달들의 시장 수익률로 잰 시장 최대 낙폭
     */
    public static <K> Map<K, Position> classifyPool(Map<K, Metrics> metrics, BigDecimal marketMaxDrawdown) {
        Objects.requireNonNull(metrics, "지표 맵");
        Objects.requireNonNull(marketMaxDrawdown, "시장 최대 낙폭");
        long n = metrics.values().stream().filter(Metrics::judgeable).count();
        int k = MoneyMath.ceilCount(ReportRules.TOP_RATIO, n);
        Map<K, Position> result = new HashMap<>();
        for (Map.Entry<K, Metrics> entry : metrics.entrySet()) {
            Metrics mine = entry.getValue();
            if (!mine.judgeable()) {
                continue;
            }
            int rankDesc = 1;
            int rankAsc = 1;
            for (Metrics other : metrics.values()) {
                if (!other.judgeable()) {
                    continue;
                }
                int cmp = other.volatility().compareTo(mine.volatility());
                if (cmp > 0) {
                    rankDesc++;
                } else if (cmp < 0) {
                    rankAsc++;
                }
            }
            result.put(entry.getKey(), classify(mine.up(), mine.down(), mine.maxDrawdown(), marketMaxDrawdown,
                    rankDesc <= k, rankAsc <= k));
        }
        return result;
    }

    /** 판정 규칙의 유일한 Java 정의(SQL {@code pos_classify}). 인자는 저장되는 8자리 값이다. */
    public static Position classify(BigDecimal up, BigDecimal down, BigDecimal maxDrawdown, BigDecimal marketMaxDrawdown,
            boolean volTop, boolean volBottom) {
        if (volBottom && down.compareTo(ReportRules.GOALKEEPER_DOWN_MAX) <= 0) {
            return Position.GOALKEEPER;
        }
        if (down.compareTo(ReportRules.DEFENDER_DOWN_BELOW) < 0) {
            return Position.DEFENDER;
        }
        if (volTop && up.compareTo(ReportRules.BREAKOUT_UP_ABOVE) > 0
                && maxDrawdown.compareTo(ReportRules.BREAKOUT_MDD_FACTOR.multiply(marketMaxDrawdown)) <= 0) {
            return Position.FORWARD_BREAKOUT;
        }
        if (up.compareTo(ReportRules.DEFENSIVE_UP_MIN) >= 0 && down.compareTo(ReportRules.DEFENSIVE_DOWN_BELOW) < 0) {
            return Position.FORWARD_DEFENSIVE;
        }
        if (up.compareTo(ReportRules.TARGET_UP_ABOVE) > 0 && down.compareTo(ReportRules.TARGET_DOWN_MAX) <= 0 && !volTop) {
            return Position.FORWARD_TARGET;
        }
        return Position.MIDFIELDER;
    }
}

package antlab.common.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/** 금액과 가격의 정확한 계산을 한 곳에 모은 순수 함수 모음이다. */
public final class MoneyMath {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999.9999");

    private MoneyMath() {
    }

    /** 기준가와 환율로 저장할 0턴 원화 가격을 계산한다. */
    public static BigDecimal initialPrice(BigDecimal basePrice, BigDecimal baseFxRate) {
        positiveRate(basePrice, "기준가", 19, 4);
        positiveRate(baseFxRate, "기준 환율", 19, 4);
        return storedMoney(basePrice.multiply(baseFxRate).setScale(0, RoundingMode.HALF_UP), "기준 가격");
    }

    /** 저장된 0턴 가격에서 시작해 모든 턴의 수익률을 누적한 가격을 계산한다. */
    public static BigDecimal priceAt(BigDecimal initialPrice, List<ReturnFactors> returns) {
        positiveInteger(initialPrice, "저장 가격");
        require(returns, "수익률 목록");
        BigDecimal factor = ONE;
        for (ReturnFactors current : returns) {
            require(current, "수익률 원소");
            ratio(current.localReturn(), "현지 수익률", true);
            ratio(current.fxChange(), "환율 변화", false);
            factor = factor.multiply(ONE.add(current.localReturn()))
                    .multiply(ONE.add(current.fxChange()));
        }
        return storedMoney(initialPrice.multiply(factor).setScale(0, RoundingMode.HALF_UP), "계산 가격");
    }

    /** 시장별 매매 요율(매수·매도 같음)을 계산한다. */
    public static BigDecimal feeRate(String market, BigDecimal krRate, BigDecimal usRate, BigDecimal fxRate) {
        require(market, "시장 코드");
        nonNegativeRate(krRate, "국내 요율");
        nonNegativeRate(usRate, "미국 요율");
        nonNegativeRate(fxRate, "환전 요율");
        BigDecimal result = switch (market) {
            case "KR" -> krRate;
            case "US" -> usRate.add(fxRate);
            default -> throw invalid("시장 코드는 KR 또는 US여야 한다");
        };
        nonNegativeNumeric(result, 12, 8, "합산 요율");
        return result.setScale(8, RoundingMode.UNNECESSARY);
    }

    /** 체결가·수량·요율로 원 단위 수수료를 계산한다. */
    public static BigDecimal fee(BigDecimal price, int quantity, BigDecimal rate) {
        positiveInteger(price, "가격");
        positive(quantity, "수량");
        nonNegativeNumeric(rate, 12, 8, "합산 요율");
        return storedMoney(price.multiply(BigDecimal.valueOf(quantity)).multiply(rate)
                .setScale(0, RoundingMode.HALF_UP), "수수료");
    }

    /** 수수료를 제외한 이동 평균 매입단가를 계산한다. */
    public static BigDecimal avgPrice(int oldQuantity, BigDecimal oldAvgPrice, int quantity, BigDecimal price) {
        nonNegative(oldQuantity, "기존 수량");
        positive(quantity, "신규 수량");
        require(oldAvgPrice, "기존 평균단가");
        positiveInteger(price, "체결가");
        if (oldQuantity == 0 && oldAvgPrice.signum() != 0) {
            throw invalid("기존 수량이 0이면 기존 평균단가는 0이어야 한다");
        }
        if (oldQuantity > 0 && oldAvgPrice.signum() <= 0) {
            throw invalid("기존 수량이 있으면 기존 평균단가는 양수여야 한다");
        }
        numeric(oldAvgPrice, 19, 4, "기존 평균단가");
        long total = (long) oldQuantity + quantity;
        if (total > Integer.MAX_VALUE) {
            throw invalid("수량 합이 int 범위를 넘는다");
        }
        return storedMoney(oldAvgPrice.multiply(BigDecimal.valueOf(oldQuantity))
                .add(price.multiply(BigDecimal.valueOf(quantity)))
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP), "평균단가");
    }

    /**
     * 실현 손익 = (체결가 − 체결 직전 평균단가) × 수량, 소수 4자리 HALF_UP(SQL {@code ROUND(…, 4)} 의 짝, erd D9).
     * 평균단가가 소수 4자리 정확하고 수량이 정수라 값은 이미 정확하며, 반올림은 이 한 곳에만 있다. 손실이면 음수다.
     */
    public static BigDecimal realizedPnl(BigDecimal price, BigDecimal avgPrice, int quantity) {
        positiveInteger(price, "체결가");
        require(avgPrice, "체결 직전 평균단가");
        numeric(avgPrice, 19, 4, "체결 직전 평균단가");
        if (avgPrice.signum() <= 0) {
            throw invalid("체결 직전 평균단가: 양수여야 한다");
        }
        positive(quantity, "수량");
        return storedSignedMoney(price.subtract(avgPrice).multiply(BigDecimal.valueOf(quantity))
                .setScale(4, RoundingMode.HALF_UP), "실현 손익");
    }

    /** 대금과 수수료를 함께 지불할 수 있는지 확인한다. */
    public static boolean affordable(BigDecimal available, BigDecimal notional, BigDecimal fee) {
        nonNegativeInteger(available, "가능 금액");
        nonNegativeInteger(notional, "대금");
        nonNegativeInteger(fee, "수수료");
        return notional.add(fee).compareTo(available) <= 0;
    }

    /** 수수료를 포함한 예산으로 살 수 있는 정수 주 수를 계산한다. */
    public static int teamQty(BigDecimal budget, BigDecimal price, BigDecimal rate) {
        nonNegativeInteger(budget, "예산");
        positiveInteger(price, "가격");
        nonNegativeNumeric(rate, 12, 8, "합산 요율");
        BigDecimal divisor = price.multiply(ONE.add(rate));
        try {
            return budget.divideToIntegralValue(divisor).intValueExact();
        } catch (ArithmeticException ex) {
            throw invalid("계산된 수량이 int 범위를 넘는다");
        }
    }

    /** 총액과 고점의 누적 낙폭을 계산한다. */
    public static BigDecimal drawdown(BigDecimal total, BigDecimal peak) {
        nonNegativeInteger(total, "총액");
        positiveInteger(peak, "고점");
        if (total.compareTo(peak) >= 0) {
            return ZERO.setScale(8, RoundingMode.UNNECESSARY);
        }
        return total.subtract(peak).divide(peak, 8, RoundingMode.HALF_UP);
    }

    /**
     * 정수 ÷ 정수 → 소수 8자리, 0에서 먼 쪽 HALF_UP, 중간 반올림 없음(SQL {@code sr_div8} 의 짝, PLAN §5.2 "반올림은 한 번").
     * 입력은 원 단위 정수끼리다(scale 은 무관 — 4자리로 저장된 정수 값도 받는다). 소수부가 있거나 0으로 나누면 예외다.
     */
    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        require(numerator, "분자");
        require(denominator, "분모");
        if (!isInteger(numerator) || !isInteger(denominator)) {
            throw invalid("비율의 분자와 분모는 정수여야 한다");
        }
        if (denominator.signum() == 0) {
            throw invalid("비율의 분모는 0일 수 없다");
        }
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    /** Exact cost of a holding: quantity × average cost (scale 4, no rounding). Display-only; the ledger never reads it. */
    public static BigDecimal holdingCost(int quantity, BigDecimal avgPrice) {
        positive(quantity, "수량");
        require(avgPrice, "평균단가");
        if (avgPrice.signum() <= 0) {
            throw invalid("평균단가는 0보다 커야 한다");
        }
        return avgPrice.multiply(BigDecimal.valueOf(quantity));
    }

    /** Unrealised P&L in whole won: valuation − exact cost, rounded once (HALF_UP). */
    public static BigDecimal unrealizedPnl(BigDecimal valuation, BigDecimal cost) {
        require(valuation, "평가액");
        require(cost, "원가");
        return valuation.subtract(cost).setScale(0, RoundingMode.HALF_UP);
    }

    /** Unrealised return = (valuation − exact cost) ÷ exact cost, 8 decimal places, rounded once (HALF_UP). */
    public static BigDecimal unrealizedReturn(BigDecimal valuation, BigDecimal cost) {
        require(valuation, "평가액");
        require(cost, "원가");
        if (cost.signum() <= 0) {
            throw invalid("원가는 0보다 커야 한다");
        }
        return valuation.subtract(cost).divide(cost, 8, RoundingMode.HALF_UP);
    }

    /** 저장 가격과 보유 수량으로 평가액을 계산한다. */
    public static BigDecimal valuation(BigDecimal price, int quantity) {
        positiveInteger(price, "가격");
        nonNegative(quantity, "수량");
        return storedMoney(price.multiply(BigDecimal.valueOf(quantity)), "평가액");
    }

    /** 현금과 종목별 평가액을 합산한다. */
    public static BigDecimal totalValue(BigDecimal cash, List<BigDecimal> positionValues) {
        nonNegativeInteger(cash, "현금");
        require(positionValues, "평가액 목록");
        BigDecimal result = cash;
        for (BigDecimal positionValue : positionValues) {
            nonNegativeInteger(positionValue, "평가액");
            result = result.add(positionValue);
        }
        return storedMoney(result, "총 평가액");
    }

    /**
     * 응답용 원 단위 금액 — scale 0 으로 정확히 바꾼다. DB 에서 읽은 값(scale 4)과 방금 만든 값(요청 scale)의
     * 문자열을 같게 만드는 용도이며, 소수 자리에 0이 아닌 값이 있으면 반올림하지 않고 예외다.
     */
    public static BigDecimal exactWon(BigDecimal value) {
        require(value, "금액");
        return exactScale(value, 0, "금액");
    }

    /** 응답용 평균단가 — scale 4 로 정확히 바꾼다(저장 형식과 같다). 값이 바뀌면(5자리 이상 유효) 예외다. */
    public static BigDecimal exactAvgPrice(BigDecimal value) {
        require(value, "평균단가");
        return exactScale(value, 4, "평균단가");
    }

    /**
     * A price in its stored form (scale 4, {@code NUMERIC(19,4)}), exactly. The turn advance holds this turn's prices at scale 0 until
     * they are read back, and PostgreSQL's division scale depends on the input scales, so feed inputs are normalised first.
     */
    public static BigDecimal exactStoredPrice(BigDecimal value) {
        require(value, "가격");
        return exactScale(value, 4, "가격");
    }

    /** 응답용 실현 손익 — scale 4 로 정확히 바꾼다(저장 형식과 같다). 값이 없으면(매수) null 이다. */
    public static BigDecimal exactPnl(BigDecimal value) {
        return value == null ? null : exactScale(value, 4, "실현 손익");
    }

    /** 응답용 비율 — scale 8 로 정확히 바꾼다. 값이 바뀌면(9자리 이상 유효) 예외다. */
    public static BigDecimal exactRate(BigDecimal value) {
        require(value, "비율");
        return exactScale(value, 8, "비율");
    }

    /** 응답용 확률 — scale 4 로 정확히 바꾼다. 값이 바뀌면(5자리 이상 유효) 예외다. */
    public static BigDecimal exactProbability(BigDecimal value) {
        require(value, "확률");
        return exactScale(value, 4, "확률");
    }

    private static BigDecimal exactScale(BigDecimal value, int scale, String field) {
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw invalid(field + ": 허용된 소수 자리까지 정확히 표현되어야 한다");
        }
    }

    /**
     * 정확한 분수 평균 = (1/n)·Σ aᵢ/bᵢ 를 공통 분모로 합쳐 마지막에 {@link #ratio} 한 번만 나눈다(SQL {@code sr_frac_mean} 의 짝).
     * 분수마다 먼저 반올림한 뒤 평균하는 것과 값이 다를 수 있다. 목록이 비었거나 길이가 다르거나 분모가 0이면 예외다.
     */
    public static BigDecimal fractionMean(List<BigInteger> numerators, List<BigInteger> denominators) {
        require(numerators, "분자 목록");
        require(denominators, "분모 목록");
        int n = numerators.size();
        if (n == 0 || n != denominators.size()) {
            throw invalid("분수 목록이 비었거나 길이가 다르다");
        }
        BigInteger num = BigInteger.ZERO;
        BigInteger den = BigInteger.ONE;
        for (int i = 0; i < n; i++) {
            BigInteger a = numerators.get(i);
            BigInteger b = denominators.get(i);
            require(a, "분자");
            require(b, "분모");
            num = num.multiply(b).add(a.multiply(den));
            den = den.multiply(b);
        }
        return ratio(new BigDecimal(num), new BigDecimal(den.multiply(BigInteger.valueOf(n))));
    }

    /**
     * SQL {@code (n + 0.000…(소수 40자리)) / d} 와 같은 나눗셈 — 몫의 소수 자릿수까지 PostgreSQL {@code numeric} 규칙을 따른다(단계 그대로 따라 하기, 계획 W2).
     * 자릿수 = max(분자 scale·40, 분모 scale, 16 − 4·(몫의 4자리 묶음 지수 추정)), 그 자리에서 HALF_UP 한 번이다. 0으로 나누면 예외다.
     */
    public static BigDecimal pad40Divide(BigDecimal numerator, BigDecimal denominator) {
        require(numerator, "분자");
        require(denominator, "분모");
        if (denominator.signum() == 0) {
            throw invalid("분모는 0일 수 없다");
        }
        int rscale = divScale(numerator, Math.max(Math.max(numerator.scale(), 0), PAD_SCALE),
                denominator, Math.max(denominator.scale(), 0));
        return numerator.divide(denominator, rscale, RoundingMode.HALF_UP);
    }

    /**
     * 표본 표준편차(n − 1) 소수 8자리 — SQL {@code ROUND(stddev_samp(x), 8)} 의 짝. 값이 둘보다 적으면 null 이다.
     * PostgreSQL {@code numeric} 의 단계를 따른다: Σx 와 Σx² 는 정확, 분산 분자 N·Σx² − (Σx)² 도 정확하며(자릿수 2·s, s = 입력 최대 scale),
     * 분산은 {@code select_div_scale} 자리에서 한 번 반올림하고, 제곱근도 같은 자리에서 한 번 반올림한 뒤 8자리로 반올림한다.
     */
    public static BigDecimal sampleStddev8(List<BigDecimal> values) {
        BigDecimal stddev = sampleStddev(values);
        return stddev == null ? null : round8(stddev);
    }

    /**
     * Sample standard deviation (n - 1) before the 8-digit rounding: the counterpart of SQL {@code stddev_samp(x)}, equal in value and
     * decimal scale. Null when there are fewer than two values. The steps are those of {@link #sampleStddev8} without the final
     * 8-digit rounding. When the variance is 0 (all values equal) the result is 0 and its scale carries no meaning.
     */
    public static BigDecimal sampleStddev(List<BigDecimal> values) {
        BigDecimal variance = sampleVariance(values);
        if (variance == null) {
            return null;
        }
        if (variance.signum() == 0) {
            return ZERO;
        }
        // 제곱근을 분산의 자릿수에서 한 번 반올림한다: round(sqrt(U·10^rscale)) = r 또는 r + 1 (r = 정수 제곱근).
        int rscale = variance.scale();
        BigInteger scaled = variance.unscaledValue().multiply(BigInteger.TEN.pow(rscale));
        BigInteger root = scaled.sqrt();
        if (scaled.subtract(root.multiply(root)).compareTo(root) > 0) {
            root = root.add(BigInteger.ONE);
        }
        return new BigDecimal(root, rscale);
    }

    /**
     * Division: the counterpart of SQL {@code numeric / numeric}. The quotient scale is {@code select_div_scale} (from the scales and
     * magnitudes of both inputs), rounded HALF_UP once at that scale. The input scales feed the result scale, so never pass values
     * reduced with {@code stripTrailingZeros}. Dividing by zero throws.
     */
    public static BigDecimal pgDivide(BigDecimal numerator, BigDecimal denominator) {
        require(numerator, "분자");
        require(denominator, "분모");
        if (denominator.signum() == 0) {
            throw invalid("분모는 0일 수 없다");
        }
        int rscale = divScale(numerator, Math.max(numerator.scale(), 0), denominator, Math.max(denominator.scale(), 0));
        return numerator.divide(denominator, rscale, RoundingMode.HALF_UP);
    }

    /**
     * Mean: the counterpart of SQL {@code avg(numeric)}. The exact sum (scale = largest input scale) is divided by the count with
     * {@link #pgDivide}. Null when there are no values.
     */
    public static BigDecimal pgAvg(List<BigDecimal> values) {
        require(values, "값 목록");
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = ZERO;
        for (BigDecimal x : values) {
            require(x, "값");
            sum = sum.add(x);
        }
        return pgDivide(sum, BigDecimal.valueOf(values.size()));
    }

    /**
     * 표본 분산(n − 1) — PG {@code var_samp} 의 짝. 분자 N·Σx² − (Σx)² 는 정확하고 {@code select_div_scale} 자리에서 한 번 반올림한다.
     * 값이 둘보다 적으면 null, 분자가 0 이하(모두 같은 값)면 0 이다. 분산의 반올림 모드는 8자리 결과로는 거의 드러나지 않아
     * 분산 단계에서 PG 와 직접 대조한다({@code MoneyMathSqlParityTest}).
     */
    static BigDecimal sampleVariance(List<BigDecimal> values) {
        require(values, "값 목록");
        int n = values.size();
        if (n < 2) {
            return null;
        }
        int s = 0;
        BigDecimal sumX = ZERO;
        BigDecimal sumX2 = ZERO;
        for (BigDecimal x : values) {
            require(x, "값");
            s = Math.max(s, x.scale());
            sumX = sumX.add(x);
            sumX2 = sumX2.add(x.multiply(x));
        }
        BigDecimal bigN = BigDecimal.valueOf(n);
        BigDecimal numerator = bigN.multiply(sumX2).subtract(sumX.multiply(sumX));
        if (numerator.signum() <= 0) {
            return ZERO;
        }
        BigDecimal denominator = BigDecimal.valueOf((long) n * (n - 1));
        int rscale = divScale(numerator, 2 * s, denominator, 0);
        // 분산의 반올림 모드는 8자리 결과로는 거의 드러나지 않아 분산 단계에서 PG 와 직접 대조한다
        return numerator.divide(denominator, rscale, RoundingMode.HALF_UP);
    }

    private static final int PAD_SCALE = 40;

    /** 소수 8자리 HALF_UP 한 번 — 성적표·포지션 측정의 비율 반올림 자리. 반올림 구현은 이 파일에만 둔다. */
    public static BigDecimal round8(BigDecimal value) {
        require(value, "값");
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    /** Fractional digits of a portfolio share sent to the engine (the wire allows 12). */
    public static final int SHARE_SCALE = 12;

    /**
     * Share of one holding in the total value, rounded DOWN at {@link #SHARE_SCALE} digits. Rounding down keeps the sum of
     * the shares at or below 1, so the cash share (the exact remainder) is never negative. Both inputs are whole won.
     */
    public static BigDecimal shareDown(BigDecimal valuation, BigDecimal totalValue) {
        nonNegativeInteger(valuation, "평가액");
        positiveInteger(totalValue, "총 평가액");
        if (valuation.compareTo(totalValue) > 0) {
            throw invalid("평가액이 총 평가액보다 클 수 없다");
        }
        return valuation.divide(totalValue, SHARE_SCALE, RoundingMode.DOWN);
    }

    /**
     * Whole won of a value multiple: {@code totalValue × multiple}, HALF_UP once (PLAN §5.2). The engine's eight-digit
     * rounding is a ratio rounding on the wire; this is the one money rounding. A negative multiple is a bug.
     */
    public static BigDecimal scaleByMultiple(BigDecimal totalValue, BigDecimal multiple) {
        positiveInteger(totalValue, "총 평가액");
        require(multiple, "배수");
        if (multiple.signum() < 0) {
            throw invalid("배수는 음수일 수 없다");
        }
        return storedMoney(totalValue.multiply(multiple).setScale(0, RoundingMode.HALF_UP), "예상 평가액");
    }

    /** Working scale of unrounded score arithmetic (season score, rating): 30 decimals, far below any stored scale. */
    public static final int SCORE_WORK_SCALE = 30;
    private static final MathContext SCORE_ROOT = new MathContext(60, RoundingMode.HALF_UP);

    /** Score arithmetic division at {@link #SCORE_WORK_SCALE}, HALF_UP. Parts stay unrounded until their stored scale. */
    public static BigDecimal scoreDivide(BigDecimal numerator, BigDecimal denominator) {
        require(numerator, "분자");
        require(denominator, "분모");
        return numerator.divide(denominator, SCORE_WORK_SCALE, RoundingMode.HALF_UP);
    }

    /** Division to 60 significant digits (for a variance feeding a square root). */
    public static BigDecimal divideSignificant(BigDecimal numerator, BigDecimal denominator) {
        require(numerator, "분자");
        require(denominator, "분모");
        return numerator.divide(denominator, SCORE_ROOT);
    }

    /** Square root to 60 significant digits. */
    public static BigDecimal sqrtSignificant(BigDecimal value) {
        require(value, "값");
        return value.sqrt(SCORE_ROOT);
    }

    /** Twelve decimals HALF_UP once: the scale of an observed value sent to the engine (the wire allows 12). */
    public static BigDecimal round12(BigDecimal value) {
        require(value, "값");
        return value.setScale(SHARE_SCALE, RoundingMode.HALF_UP);
    }

    /** Two decimals HALF_UP once: the stored scale of scores and ratings. */
    public static BigDecimal round2(BigDecimal value) {
        require(value, "값");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Mean of the values at {@code scale} decimal places, HALF_UP, one rounding; null for an empty list. */
    public static BigDecimal mean(List<BigDecimal> values, int scale) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), scale, RoundingMode.HALF_UP);
    }

    /** Whole percentage points of a ratio: {@code round(ratio * 100, 0)}, HALF_UP (away from zero on ties, like SQL {@code round}). */
    public static BigDecimal percentPoints(BigDecimal ratio) {
        require(ratio, "비율");
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
    }

    /** CEIL(ratio × n) — 풀 상대 순위의 상위·하위 개수 k (SQL {@code ceil(top_ratio * count(*))}). */
    public static int ceilCount(BigDecimal ratio, long n) {
        require(ratio, "비율");
        return ratio.multiply(BigDecimal.valueOf(n)).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** PostgreSQL {@code select_div_scale}: 몫이 16자리 유효 숫자를 갖도록 자릿수를 정하되 두 입력의 자릿수보다 작지 않게 한다. */
    private static int divScale(BigDecimal v1, int dscale1, BigDecimal v2, int dscale2) {
        int qweight = groupWeight(v1) - groupWeight(v2);
        if (firstGroup(v1) <= firstGroup(v2)) {
            qweight--;
        }
        int rscale = 16 - qweight * 4;
        rscale = Math.max(rscale, dscale1);
        rscale = Math.max(rscale, dscale2);
        return Math.min(Math.max(rscale, 0), 1000);
    }

    /** 10000 진 묶음 기준 최상위 자리의 지수(0이면 0). */
    private static int groupWeight(BigDecimal value) {
        if (value.signum() == 0) {
            return 0;
        }
        int exponent = value.precision() - value.scale() - 1;
        return Math.floorDiv(exponent, 4);
    }

    /** 최상위 10000 진 묶음의 값(1–9999, 0이면 0). */
    private static int firstGroup(BigDecimal value) {
        if (value.signum() == 0) {
            return 0;
        }
        return value.abs().movePointLeft(4 * groupWeight(value)).setScale(0, RoundingMode.DOWN).intValue();
    }

    public record ReturnFactors(BigDecimal localReturn, BigDecimal fxChange) {
    }

    private static void ratio(BigDecimal value, String field, boolean allowMinusOne) {
        require(value, field);
        numeric(value, 12, 8, field);
        int cmp = value.compareTo(ONE.negate());
        if (cmp < 0 || (cmp == 0 && !allowMinusOne)) {
            throw invalid(field + ": 범위를 벗어났다");
        }
    }

    private static void nonNegativeRate(BigDecimal value, String field) {
        require(value, field);
        numeric(value, 12, 8, field);
        if (value.signum() < 0 || value.compareTo(ONE) >= 0) {
            throw invalid(field + ": 0 이상 1 미만이어야 한다");
        }
    }

    private static void nonNegativeNumeric(BigDecimal value, int precision, int scale, String field) {
        require(value, field);
        numeric(value, precision, scale, field);
        if (value.signum() < 0) {
            throw invalid(field + ": 음수일 수 없다");
        }
    }

    private static void positiveRate(BigDecimal value, String field, int precision, int scale) {
        require(value, field);
        numeric(value, precision, scale, field);
        if (value.signum() <= 0) {
            throw invalid(field + ": 양수여야 한다");
        }
    }

    private static void positiveInteger(BigDecimal value, String field) {
        require(value, field);
        if (value.signum() <= 0 || !isInteger(value) || value.compareTo(MAX_MONEY) > 0) {
            throw invalid(field + ": 양의 원 단위 정수여야 한다");
        }
    }

    private static void nonNegativeInteger(BigDecimal value, String field) {
        require(value, field);
        if (value.signum() < 0 || !isInteger(value) || value.compareTo(MAX_MONEY) > 0) {
            throw invalid(field + ": 0 이상의 원 단위 정수여야 한다");
        }
    }

    private static BigDecimal storedMoney(BigDecimal value, String field) {
        if (value.signum() < 0 || value.compareTo(MAX_MONEY) > 0) {
            throw invalid(field + ": NUMERIC(19,4) 범위를 넘는다");
        }
        return value;
    }

    private static BigDecimal storedSignedMoney(BigDecimal value, String field) {
        if (value.abs().compareTo(MAX_MONEY) > 0) {
            throw invalid(field + ": NUMERIC(19,4) 범위를 넘는다");
        }
        return value;
    }

    private static boolean isInteger(BigDecimal value) {
        try {
            value.setScale(0, RoundingMode.UNNECESSARY);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private static void numeric(BigDecimal value, int precision, int scale, String field) {
        try {
            BigDecimal scaled = value.setScale(scale, RoundingMode.UNNECESSARY);
            if (scaled.precision() > precision) {
                throw invalid(field + ": 자릿수가 범위를 넘는다");
            }
        } catch (ArithmeticException ex) {
            throw invalid(field + ": 허용된 소수 자리까지 정확히 표현되어야 한다");
        }
    }

    private static void require(Object value, String field) {
        if (Objects.isNull(value)) {
            throw invalid(field + ": null일 수 없다");
        }
    }

    private static void positive(int value, String field) {
        if (value <= 0) {
            throw invalid(field + ": 양수여야 한다");
        }
    }

    private static void nonNegative(int value, String field) {
        if (value < 0) {
            throw invalid(field + ": 음수일 수 없다");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

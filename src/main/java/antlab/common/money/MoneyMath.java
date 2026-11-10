package antlab.common.money;

import java.math.BigDecimal;
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

    /** 시장별 매수 요율을 계산한다. */
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

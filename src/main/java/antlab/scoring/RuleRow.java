package antlab.scoring;

import java.math.BigDecimal;

/**
 * 규칙 쿼리 한 행의 투영. 열 별칭은 쿼리 바깥 껍데기가 붙인다. 세 값(기대·실제·건수)과 detail(JSON 문자열)만 있고,
 * 판정·차이·분류는 {@link RuleResult#of} 가 한 곳에서 붙인다.
 */
public interface RuleRow {

    /** 기대 값. 스칼라 규칙이 아니거나 비교 대상이 없으면 null. */
    BigDecimal getExpectedValue();

    /** 실제 값. 스칼라 규칙이 아니거나 비교 대상이 없으면 null. */
    BigDecimal getActualValue();

    /** 불일치 항목 수. 쿼리가 {@code COALESCE} 로 항상 값을 낸다. */
    Integer getMismatchCount();

    /** 불일치 항목 JSON 문자열(없으면 null). */
    String getDetail();
}

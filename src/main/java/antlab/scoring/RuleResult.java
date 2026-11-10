package antlab.scoring;

import java.math.BigDecimal;

/**
 * 규칙 하나의 대사 결과 — {@code recon_rules} 의 9컬럼과 같다. 엔티티가 아닌 순수 값이라 트랜잭션이 롤백돼도 들고 건널 수 있다
 * (SQL 쪽 jsonb 지역 변수와 같은 역할). 금액·수량 합은 {@link BigDecimal}, {@code detail} 은 JSON 문자열 그대로다.
 */
public record RuleResult(int ruleNo, CheckCode checkCode, ReconciliationStatus status, BigDecimal expectedValue,
        BigDecimal actualValue, BigDecimal diffValue, int mismatchCount, ReconciliationClassification classification,
        String detail) {

    /**
     * 쿼리 결과에 판정·차이·분류를 붙인다 — {@code recon_rules} 의 CASE 사본이며 반올림은 없다.
     * 일치(건수 0)면 분류·detail 은 없고, 불일치면 {@code INVESTIGATE}. 차이는 기대·실제가 둘 다 있을 때만 실제 − 기대다.
     * 항목이 서로 상쇄돼 차이가 0이어도 건수가 있으면 불일치다.
     */
    public static RuleResult of(int ruleNo, RuleRow row) {
        RuleCatalog.Rule rule = RuleCatalog.byRuleNo(ruleNo)
                .orElseThrow(() -> new IllegalArgumentException("규칙 번호가 없다: " + ruleNo));
        int count = row.getMismatchCount();
        boolean match = count == 0;
        BigDecimal expected = row.getExpectedValue();
        BigDecimal actual = row.getActualValue();
        BigDecimal diff = expected != null && actual != null ? actual.subtract(expected) : null;
        return new RuleResult(ruleNo, rule.checkCode(), match ? ReconciliationStatus.MATCH : ReconciliationStatus.MISMATCH,
                expected, actual, diff, count, match ? null : ReconciliationClassification.INVESTIGATE,
                match ? null : row.getDetail());
    }
}

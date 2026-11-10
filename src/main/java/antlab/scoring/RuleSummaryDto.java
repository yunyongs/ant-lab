package antlab.scoring;

import java.math.BigDecimal;

/**
 * 수동 검사 응답의 규칙 한 줄 — 코드·상태·건수와 {@code reconciliation_log} 행의 기대·실제·차이·detail 을 그대로 담는다
 * (가공·반올림 없음). 스칼라가 아닌 규칙은 세 값이 null 이고, 일치하면 detail 도 null 이다.
 * 개발 엔드포인트(local 전용) 전용이라 노출 상한 검사에서 제외한다.
 */
public record RuleSummaryDto(int ruleNo, String checkCode, String status, int mismatchCount,
        BigDecimal expectedValue, BigDecimal actualValue, BigDecimal diffValue, String detail) {
}

package antlab.scoring;

/** 수동 검사 응답의 규칙 한 줄 — 코드·상태·건수만 담는다. 기대·실제·차이·detail 값은 내지 않는다. */
public record RuleSummaryDto(int ruleNo, String checkCode, String status, int mismatchCount) {
}

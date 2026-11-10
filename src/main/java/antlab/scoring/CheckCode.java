package antlab.scoring;

/**
 * 대사 검사 코드 17개 — reconciliation_log.check_code. 순서와 글자는 DB CHECK 목록(erd 6.4)과 같다.
 * 각 규칙의 내용은 대사 기능(P0-32)이 붙인다.
 */
public enum CheckCode {
    ACCOUNT_OPENING, CASH_SUM, LEDGER_CHAIN, ORDER_LEDGER, HOLDING_QTY, ORDER_FILL, EXEC_VALUES, RESERVED_ZERO,
    VALUATION, TURN_PRICE, FILL_PRICE, FEE_RULE, FORMATION, FORMATION_BIND, LINEUP_HOLDING, LINEUP_OVERLAP,
    POSITION_VALUE
}

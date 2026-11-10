package antlab.account;

/** 원장 유형 — cash_ledger.type. 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다). */
public enum LedgerType {
    DEPOSIT, BUY, SELL, FEE, TAX, ADJUSTMENT
}

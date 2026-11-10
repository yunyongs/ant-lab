package antlab.account;

/** 주문 유형 — orders.type (v2 는 MARKET 만 쓰고 LIMIT 은 CHECK 와 같게 둔다). 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다). */
public enum OrderType {
    MARKET, LIMIT
}

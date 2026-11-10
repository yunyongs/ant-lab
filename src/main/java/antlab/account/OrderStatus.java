package antlab.account;

/** 주문 상태 — orders.status. 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다). */
public enum OrderStatus {
    PENDING, FILLED, CANCELED, REJECTED
}

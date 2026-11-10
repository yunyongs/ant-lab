package antlab.account;

/** 주문 출발점 — orders.source. 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다). */
public enum OrderSource {
    RANKING, FEED, ALERT, BENCH, DIRECT
}

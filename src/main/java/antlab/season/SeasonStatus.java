package antlab.season;

/** 시즌 상태 — season.status. 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다). */
public enum SeasonStatus {
    IN_PROGRESS, ABANDONING, FINISHED
}

package antlab.season;

/**
 * 시즌 상태 — season.status. 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다).
 * HALTED 는 한 턴의 대사 실패가 거듭되어 멈춘 상태다(MVP-15). 재개하면 IN_PROGRESS(또는 ABANDONING)로 돌아간다.
 */
public enum SeasonStatus {
    IN_PROGRESS, ABANDONING, FINISHED, HALTED
}

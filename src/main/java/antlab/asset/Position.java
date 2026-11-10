package antlab.asset;

/** 포지션 — asset_position.system_position, lineup.declared_position(P0-28)이 함께 쓴다. 상수는 DB CHECK 목록과 글자 단위로 같다(EnumCheckContractTest 가 대조한다). */
public enum Position {
    GOALKEEPER, DEFENDER, MIDFIELDER, FORWARD_TARGET, FORWARD_BREAKOUT, FORWARD_DEFENSIVE
}

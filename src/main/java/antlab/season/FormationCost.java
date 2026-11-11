package antlab.season;

import java.math.BigDecimal;

/**
 * 전술 변경 한 건의 비용 집계(내부 값). 그 변경에 연결된 체결의 매수·매도 대금과 수수료·세금 합이다. 주문 없는 변경은 주문 0건·합 0 이다.
 * 공개 성적표 DTO 가 아니다 — 종료 시즌 조건과 표현 형식은 성적표 기능이 정한다.
 */
public record FormationCost(long formationChangeId, int turnNo, long orderCount, BigDecimal buyNotional,
        BigDecimal sellNotional, BigDecimal feeSum, BigDecimal taxSum) {
}

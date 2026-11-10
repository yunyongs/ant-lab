package antlab.account;

import antlab.asset.Position;
import java.math.BigDecimal;

/**
 * 보유 조회의 내부 행 — 보유 × 그 턴 가격 × 열린 라인업을 질의 한 번(생성자 표현식)으로 읽는다. 응답이 아니다.
 * 가격이나 선언 포지션이 null 이면 주문 서비스가 막는 상태(데이터 손상)라 서비스가 500 으로 멈춘다.
 */
public record HoldingViewRow(Long assetId, int quantity, BigDecimal avgPrice, BigDecimal price,
        Position declaredPosition) {
}

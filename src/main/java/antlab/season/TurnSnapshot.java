package antlab.season;

import java.math.BigDecimal;

/** 턴 스냅샷 계산 결과 값(저장 전). */
public record TurnSnapshot(BigDecimal cashBalance, BigDecimal holdingsValue, BigDecimal totalValue,
        BigDecimal goalkeeperValue, BigDecimal defenderValue, BigDecimal midfielderValue, BigDecimal forwardValue,
        BigDecimal drawdown) {
}

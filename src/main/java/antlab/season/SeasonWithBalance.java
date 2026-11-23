package antlab.season;

import java.math.BigDecimal;

/**
 * 목록 질의 한 번으로 시즌, 현금 잔액, 참가한 리그(없으면 null)를 함께 읽는 투영(N+1 방지). {@code latestTotalValue} is
 * the total of the turn row at the current turn, null at turn 0.
 */
public record SeasonWithBalance(Season season, BigDecimal balance, Long leagueId, BigDecimal latestTotalValue) {
}

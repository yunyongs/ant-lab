package antlab.season;

import java.math.BigDecimal;

/** 목록 질의 한 번으로 시즌과 현금 잔액을 함께 읽는 투영(N+1 방지). */
public record SeasonWithBalance(Season season, BigDecimal balance) {
}

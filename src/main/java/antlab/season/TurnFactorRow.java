package antlab.season;

import java.math.BigDecimal;

/**
 * 턴 가격 계산 입력 한 칸(종목 × 턴). 수익률·환율 변화는 역사 표에 행이 없으면 null 이다.
 * 월 블록 번호는 일부러 담지 않는다 — 시즌이 끝나기 전에는 어디로도 나가면 안 되는 값이다.
 */
public interface TurnFactorRow {

    Long getAssetId();

    BigDecimal getInitialPrice();

    String getCurrency();

    Integer getTurnNo();

    BigDecimal getLocalReturn();

    BigDecimal getFxChange();
}

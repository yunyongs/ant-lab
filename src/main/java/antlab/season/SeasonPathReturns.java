package antlab.season;

import antlab.asset.Currency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 경로 수익률 투영({@link SeasonPathRepository#findTurnFactors})을 종목마다 <b>원화 월 수익률 목록</b>으로 바꾼다.
 * 월 블록 번호는 투영에 없고 여기서도 만들지 않는다 — 경로의 달 순서는 {@code season} 패키지 안에서만 읽는다(SPRING_JPA §4.6).
 * 원화 수익률은 12 의 {@code pos_ret} 와 같다: 원화 종목은 현지 수익률 그대로, 외화 종목은 (1 + 현지)(1 + 환율 변화) − 1(정확값).
 * 현지 수익률이 없는 달은 null 이고(표본 부족), 외화 종목에 환율 변화가 없으면 예외다(12 의 pos_fail).
 */
public final class SeasonPathReturns {

    private SeasonPathReturns() {
    }

    /**
     * 종목 번호 → 길이 {@code turns} 의 원화 수익률 목록(1..turns 턴 순). 입력은 종목 번호, 턴 번호 순이며
     * 종목마다 정확히 1..turns 턴이 있어야 한다(아니면 예외 — 성적표 원천의 모순이다).
     */
    public static Map<Long, List<BigDecimal>> byAsset(List<TurnFactorRow> rows, int turns) {
        Map<Long, List<BigDecimal>> out = new LinkedHashMap<>();
        for (TurnFactorRow row : rows) {
            List<BigDecimal> list = out.computeIfAbsent(row.getAssetId(), k -> new ArrayList<>());
            if (row.getTurnNo() != list.size() + 1) {
                throw new IllegalStateException("경로 수익률이 처음부터 보고 턴까지 이어지지 않는다");
            }
            list.add(row.getLocalReturn() == null ? null : krwReturn(
                    Currency.KRW.name().equals(row.getCurrency()), row.getLocalReturn(), row.getFxChange()));
        }
        for (List<BigDecimal> list : out.values()) {
            if (list.size() != turns) {
                throw new IllegalStateException("경로 수익률이 처음부터 보고 턴까지 이어지지 않는다");
            }
        }
        return out;
    }

    /**
     * 원화 월 수익률: 원화 종목은 현지 수익률 그대로, 외화 종목은 (1 + 현지)(1 + 환율 변화) − 1 (정확한 곱, 반올림 없음, 12 의 M4).
     * 외화 종목인데 환율 변화가 없으면 예외다.
     */
    public static BigDecimal krwReturn(boolean krwCurrency, BigDecimal localReturn, BigDecimal fxChange) {
        if (krwCurrency) {
            return localReturn;
        }
        if (fxChange == null) {
            throw new IllegalStateException("외화 종목에 환율 변화가 없다");
        }
        return BigDecimal.ONE.add(localReturn).multiply(BigDecimal.ONE.add(fxChange)).subtract(BigDecimal.ONE);
    }
}

package antlab.season;

import antlab.asset.AssetPosition;
import antlab.asset.AssetPositionRepository;
import antlab.common.money.MoneyMath;
import antlab.common.money.MoneyMath.ReturnFactors;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 다음 턴 풀 전체의 원화 가격 — 저장된 0턴 가격에서 1..턴의 수익률·환율 변화를 누적해 곱하고 마지막에 한 번만 반올림한다
 * ({@link MoneyMath#priceAt}, PLAN §5.2·erd D16·D25). 전 턴의 반올림 가격을 시작값으로 쓰지 않는다.
 * {@code db/23} 의 {@code ta_prices}(재귀 CTE)와 다른 식으로 같은 값을 내야 하며, 규칙 12 가 같은 트랜잭션에서 다시 대조한다.
 *
 * <p>입력 조회는 {@code turn_no <= 요청 턴} 으로 제한해 다음 턴 뒤의 달을 읽지 않는다(사후 정보 배제, PLAN §4.5).
 * 완전성은 23 과 같은 뜻이다: 수익률(외화는 환율도)이 한 칸이라도 빠진 종목은 결과에 넣지 않고, 풀 종목이 결과에 없으면 거절한다.
 */
@Component
public class TurnPriceCalculator {

    private static final String KRW = "KRW";

    private final SeasonPathRepository seasonPaths;
    private final AssetPositionRepository positions;

    public TurnPriceCalculator(SeasonPathRepository seasonPaths, AssetPositionRepository positions) {
        this.seasonPaths = seasonPaths;
        this.positions = positions;
    }

    /** 종목 한 개의 계산된 원화 가격. */
    public record AssetPrice(Long assetId, BigDecimal price) {
    }

    /**
     * @return 종목 번호 오름차순의 가격 목록(풀 전체)
     * @throws IllegalStateException 수익률·환율이 빠진 풀 종목이 있거나 계산 가격이 0 이하(500, 내용은 로그에만)
     */
    public List<AssetPrice> compute(Season season, int turnNo) {
        Map<Long, List<TurnFactorRow>> byAsset = new LinkedHashMap<>();
        for (TurnFactorRow row : seasonPaths.findTurnFactors(season.getId(), turnNo)) {
            byAsset.computeIfAbsent(row.getAssetId(), k -> new ArrayList<>()).add(row);
        }
        List<AssetPrice> result = new ArrayList<>();
        for (Map.Entry<Long, List<TurnFactorRow>> entry : byAsset.entrySet()) {
            List<ReturnFactors> factors = completeFactors(entry.getValue(), turnNo);
            if (factors == null) {
                continue;
            }
            BigDecimal price = MoneyMath.priceAt(entry.getValue().get(0).getInitialPrice(), factors);
            if (price.signum() <= 0) {
                throw new IllegalStateException("계산된 턴 가격이 0 이하다");
            }
            result.add(new AssetPrice(entry.getKey(), price));
        }
        Set<Long> computed = result.stream().map(AssetPrice::assetId).collect(Collectors.toSet());
        for (AssetPosition pool : positions.findByBaseDate(season.getBaseDate())) {
            if (!computed.contains(pool.getAssetId())) {
                throw new IllegalStateException("다음 턴 가격을 만들 수 없는 풀 종목이 있다");
            }
        }
        return List.copyOf(result);
    }

    /** 턴 1..turnNo 의 계수가 모두 있으면 턴 순서 목록, 아니면 null. 원화 종목의 환율 변화는 0 으로 둔다. */
    private static List<ReturnFactors> completeFactors(List<TurnFactorRow> rows, int turnNo) {
        if (rows.size() != turnNo) {
            return null;
        }
        List<ReturnFactors> factors = new ArrayList<>(turnNo);
        for (TurnFactorRow row : rows) {
            boolean krw = KRW.equals(row.getCurrency());
            if (row.getLocalReturn() == null || (!krw && row.getFxChange() == null)) {
                return null;
            }
            factors.add(new ReturnFactors(row.getLocalReturn(), krw ? BigDecimal.ZERO : row.getFxChange()));
        }
        return factors;
    }
}

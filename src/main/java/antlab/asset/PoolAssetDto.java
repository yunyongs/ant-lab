package antlab.asset;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * 종목 풀의 한 종목. 구성 요소는 모두 {@code [노출]} 열(또는 허용 목록의 식별자)에서만 온다.
 * 비율은 지수 없는 문자열(scale 8)로 나간다. 날짜·창·환율·시가총액 순위·통화는 싣지 않는다.
 */
public record PoolAssetDto(
        @ExposedFrom("asset.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (used in order requests). "
                + "Not shown to the player / 종목 식별자(주문 요청에 쓴다). 표시하지 않는다")
        Long assetId,
        @ExposedFrom("asset.market")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Market market,
        @ExposedFrom("asset.ticker")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String ticker,
        @ExposedFrom("asset.name")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String name,
        @ExposedFrom("asset.asset_type")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        AssetType assetType,
        @ExposedFrom("asset.return_basis")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ReturnBasis returnBasis,
        @ExposedFrom("asset.proxy_index_name")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Generic name of the index that fills the months without an actual return, for any asset "
                        + "that has proxy months (ETF or stock) / 실제 수익률이 없는 달을 채우는 대체 지수의 일반 이름"
                        + "(대체 달이 있는 ETF·개별 주식)")
        String proxyIndexName,
        @ExposedFrom("asset.asset_group_id")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Long groupId,
        @ExposedFrom("asset_group.name")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String groupName,
        @ExposedFrom("asset_group.kind")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        AssetGroupKind groupKind,
        @ExposedFrom("asset_position.system_position")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Position systemPosition,
        @ExposedFrom("asset_position.up_participation")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Up-market participation rate (months the "
                + "market rose 1% or more, in KRW) / 상승장 참여율(시장이 1% 이상 오른 달, 원화)")
        BigDecimal upParticipation,
        @ExposedFrom("asset_position.down_participation")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Down-market participation rate (months the "
                + "market fell 1% or more, in KRW) / 하락장 참여율(시장이 1% 이상 빠진 달, 원화)")
        BigDecimal downParticipation,
        @ExposedFrom("asset_position.volatility")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Sample standard deviation of monthly "
                + "returns (not annualized) / 월 수익률 표본 표준편차(연율화 안 함)")
        BigDecimal volatility,
        @ExposedFrom("asset_position.max_drawdown")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Maximum drawdown, 0 or less (−0.35 = −35%) "
                + "/ 최대 낙폭, 0 이하(−0.35 = −35%)")
        BigDecimal maxDrawdown) {

    /** 판정 5개 값이 하나라도 비었는가(적재 전 상태). DB 는 판정 8열이 모두 있거나 모두 없게 막는다. */
    boolean unjudged() {
        return systemPosition == null || upParticipation == null || downParticipation == null
                || volatility == null || maxDrawdown == null;
    }
}

package antlab.ranking;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

@Schema(description = "One row of a gainers or losers list / 상승·하락 목록의 한 줄")
public record RankedAssetDto(
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Row position in the list, from 1 / 목록 안의 줄 번호, 1부터")
        Integer rank,
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in the pool response) / "
                + "종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Price change of the turn = price ÷ previous turn's price − 1, "
                + "8 decimal places / 그 턴의 가격 변화 = 가격 ÷ 직전 턴 가격 − 1, 소수 8자리")
        BigDecimal changeRate) {

    static RankedAssetDto from(RankingBoard.Asset row) {
        return new RankedAssetDto(row.rank(), row.assetId(), row.changeRate());
    }
}

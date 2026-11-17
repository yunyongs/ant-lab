package antlab.ranking;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

@Schema(description = "One row of a group gainers or losers list / 그룹 상승·하락 목록의 한 줄")
public record RankedGroupDto(
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Row position in the list, from 1 / 목록 안의 줄 번호, 1부터")
        Integer rank,
        @ExposedFrom("asset.asset_group_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset group identifier (the same as groupId in the pool "
                + "response) / 종목 그룹 식별자(풀 응답의 groupId 와 같다)")
        Long groupId,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Mean price change of the group's assets, rounded once to 8 "
                + "decimal places / 그룹 종목들의 가격 변화 평균, 소수 8자리로 한 번 반올림")
        BigDecimal changeRate) {

    static RankedGroupDto from(RankingBoard.Group row) {
        return new RankedGroupDto(row.rank(), row.groupId(), row.changeRate());
    }
}

package antlab.ranking;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "One row of the attention list; rank and grade only, the index value is not shown / 관심 목록의 한 줄. 순위와 등급만 "
        + "보이고 지수 값은 보이지 않는다")
public record AttentionRankDto(
        @ExposedFrom("asset_month_attention.attention_index")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Row position in the list, from 1 / 목록 안의 줄 번호, 1부터")
        Integer rank,
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in the pool response) / "
                + "종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("asset_month_attention.attention_index")
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "3", description = "Attention grade: 3 = top 10 %, "
                + "2 = top half, 1 = the rest / 관심 등급: 3 = 상위 10%, 2 = 상위 절반, 1 = 나머지")
        Integer grade) {

    static AttentionRankDto from(RankingBoard.Attention row) {
        return new AttentionRankDto(row.rank(), row.assetId(), row.grade());
    }
}

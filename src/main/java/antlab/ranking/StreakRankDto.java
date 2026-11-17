package antlab.ranking;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

@Schema(description = "One row of a rising or falling streak list / 연속 상승·하락 목록의 한 줄")
public record StreakRankDto(
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Row position in the list, from 1 / 목록 안의 줄 번호, 1부터")
        Integer rank,
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in the pool response) / "
                + "종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "2", description = "Turns in a row with the same direction of "
                + "price change, at least 2 / 가격이 같은 방향으로 이어진 턴 수, 2 이상")
        Integer streak,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Price change of the turn, 8 decimal places / 그 턴의 가격 변화, "
                + "소수 8자리")
        BigDecimal changeRate) {

    static StreakRankDto from(RankingBoard.Streak row) {
        return new StreakRankDto(row.rank(), row.assetId(), row.streak(), row.changeRate());
    }
}

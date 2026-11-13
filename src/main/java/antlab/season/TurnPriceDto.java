package antlab.season;

import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** 한 종목의 그 턴 가격(원화 정본, 원 단위 정수 문자열). 이름은 풀 응답의 같은 종목 번호로 잇는다. */
public record TurnPriceDto(
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in "
                + "the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Price, whole KRW / 가격, 원 단위 정수")
        BigDecimal price) {

    static TurnPriceDto from(TurnPrice p) {
        return new TurnPriceDto(p.getAssetId(), MoneyMath.exactWon(p.getPrice()));
    }
}

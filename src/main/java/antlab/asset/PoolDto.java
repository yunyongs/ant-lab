package antlab.asset;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * 기준일 종목 풀 응답. 배열이 아니라 객체로 감싸 이후 필드를 더해도 깨지지 않게 한다.
 * 구성 요소 {@code assets} 는 DB 열에서 오는 값이 아니라 {@link PoolAssetDto} 목록이라 {@code @ExposedFrom} 없이
 * 그 항목 record 를 노출 상한 시험이 따로 검사한다.
 */
public record PoolDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<PoolAssetDto> assets) {
}

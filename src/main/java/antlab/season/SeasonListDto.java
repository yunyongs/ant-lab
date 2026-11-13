package antlab.season;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** 현재 회원의 시즌 전부(시즌 번호 내림차순). 쪽 나누기는 하지 않는다. */
@Schema(description = "All seasons of the current member (newest season number first). No paging / 현재 회원의 시즌 "
        + "전부(시즌 번호 내림차순). 쪽 나누기 없음")
public record SeasonListDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<SeasonDto> items) {
}

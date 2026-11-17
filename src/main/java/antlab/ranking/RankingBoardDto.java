package antlab.ranking;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

@Schema(description = "Ranking board of one played turn: seven lists of at most 5 rows each, computed from that turn's data only / "
        + "한 턴의 순위판: 그 턴의 자료만으로 계산한, 줄 5개 이하의 목록 일곱 개")
public record RankingBoardDto(
        @ExposedFrom("turn_price.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Integer turnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Biggest price rises, change above 0, largest first / 가격이 가장 "
                + "많이 오른 종목, 변화 0 초과, 큰 순")
        List<RankedAssetDto> gainers,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Biggest price falls, change below 0, largest fall first / 가격이 "
                + "가장 많이 내린 종목, 변화 0 미만, 많이 내린 순")
        List<RankedAssetDto> losers,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Most attention, by rank and grade; assets without an attention "
                + "figure are left out / 관심이 가장 큰 종목, 순위와 등급. 관심 값이 없는 종목은 뺀다")
        List<AttentionRankDto> attention,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Longest runs of rising turns, at least 2 / 가장 오래 오른 종목, "
                + "2턴 이상")
        List<StreakRankDto> risingStreaks,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Longest runs of falling turns, at least 2 / 가장 오래 내린 종목, "
                + "2턴 이상")
        List<StreakRankDto> fallingStreaks,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Groups with the biggest mean rise / 평균 상승이 가장 큰 그룹")
        List<RankedGroupDto> groupGainers,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Groups with the biggest mean fall / 평균 하락이 가장 큰 그룹")
        List<RankedGroupDto> groupLosers) {

    static RankingBoardDto from(RankingBoard board) {
        return new RankingBoardDto(board.turnNo(), board.gainers().stream().map(RankedAssetDto::from).toList(),
                board.losers().stream().map(RankedAssetDto::from).toList(),
                board.attention().stream().map(AttentionRankDto::from).toList(),
                board.risingStreaks().stream().map(StreakRankDto::from).toList(),
                board.fallingStreaks().stream().map(StreakRankDto::from).toList(),
                board.groupGainers().stream().map(RankedGroupDto::from).toList(),
                board.groupLosers().stream().map(RankedGroupDto::from).toList());
    }
}

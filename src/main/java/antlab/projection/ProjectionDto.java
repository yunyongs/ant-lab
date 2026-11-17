package antlab.projection;

import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import antlab.engine.client.EngineProjectionResult.Band;
import antlab.engine.client.EngineProjectionResult.EndQuantiles;
import antlab.engine.client.EngineProjectionResult.MddQuantiles;
import antlab.engine.client.EngineProjectionResult.PortfolioResult;
import antlab.engine.client.EngineProjectionResult.Summary;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * The spread of a team's possible value over the coming turns. Won values are the stored total times an engine multiple,
 * rounded once; drawdowns and probabilities stay ratios with eight decimals. No seed, month, version, digest or request
 * is part of it.
 */
@Schema(description = "Spread of a team's value over the coming turns. Seed, months, versions and the request are not "
        + "included\n"
        + "\n"
        + "팀 가치가 앞으로 어떻게 퍼질 수 있는지. 시드·달·버전·요청은 싣지 않는다")
public record ProjectionDto(
        @ExposedFrom("projection.phase")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "CURRENT is computed on request; START and END are "
                + "stored once / CURRENT 는 요청 때 계산하고 START·END 는 한 번 저장한다")
        ProjectionMoment phase,
        @ExposedFrom("projection.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turn the team is valued at (turn counts below are "
                + "turns ahead of it) / 팀을 평가한 턴(아래 turn 은 이 턴으로부터 앞으로 센 턴)")
        Integer turnNo,
        @ExposedFrom("projection.total_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total value at turnNo, whole KRW / turnNo 의 총 평가액, 원 단위 정수")
        BigDecimal totalValue,
        @ExposedFrom("projection.horizon_turns")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of turns looked ahead / 내다본 턴 수")
        Integer horizonTurns,
        @ExposedFrom("projection.path_count")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of simulated paths / 모의 경로 수")
        Integer pathCount,
        @ExposedFrom("season.risk_limit")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk limit, 8 decimal places / 위험 선언, 소수 8자리")
        BigDecimal riskLimit,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<ProjectionBand> bands,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<ProjectionSummary> summaries) {

    /** Value percentiles at one turn ahead, whole KRW. */
    public record ProjectionBand(
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turns ahead / 앞으로 센 턴")
            Integer turn,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "5th percentile value, whole KRW / 하위 5% 가치, 원 단위 정수")
            BigDecimal p05,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "25th percentile value, whole KRW / 하위 25% 가치, 원 단위 정수")
            BigDecimal p25,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Median value, whole KRW / 중앙값, 원 단위 정수")
            BigDecimal p50,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "75th percentile value, whole KRW / 상위 25% 가치, 원 단위 정수")
            BigDecimal p75,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "95th percentile value, whole KRW / 상위 5% 가치, 원 단위 정수")
            BigDecimal p95) {
    }

    /** What happens by one turn ahead: the end value, the worst drawdown on the way and the chance of breaching the risk limit. */
    public record ProjectionSummary(
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turns ahead / 앞으로 센 턴")
            Integer turn,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            ProjectionEnd end,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            ProjectionMdd mdd,
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Chance the risk limit is breached on the way, "
                    + "8 decimal places / 도중에 위험 선언을 넘을 확률, 소수 8자리")
            BigDecimal breachProbability) {
    }

    /** End value percentiles, whole KRW. */
    public record ProjectionEnd(
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "5th percentile, whole KRW / 하위 5%, 원 단위 정수")
            BigDecimal p05,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "25th percentile, whole KRW / 하위 25%, 원 단위 정수")
            BigDecimal p25,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Median, whole KRW / 중앙값, 원 단위 정수")
            BigDecimal p50,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "75th percentile, whole KRW / 상위 25%, 원 단위 정수")
            BigDecimal p75,
            @ExposedFrom({"projection.result", "projection.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "95th percentile, whole KRW / 상위 5%, 원 단위 정수")
            BigDecimal p95) {
    }

    /** Maximum drawdown percentiles as ratios from -1 to 0 with eight decimals. */
    public record ProjectionMdd(
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "5th percentile, 8 decimal places / 하위 5%, 소수 8자리")
            BigDecimal p05,
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "10th percentile, 8 decimal places / 하위 10%, 소수 8자리")
            BigDecimal p10,
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "25th percentile, 8 decimal places / 하위 25%, 소수 8자리")
            BigDecimal p25,
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Median, 8 decimal places / 중앙값, 소수 8자리")
            BigDecimal p50,
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "75th percentile, 8 decimal places / 상위 25%, 소수 8자리")
            BigDecimal p75,
            @ExposedFrom("projection.result")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "95th percentile, 8 decimal places / 상위 5%, 소수 8자리")
            BigDecimal p95) {
    }

    /** Builds the response of one portfolio: multiples become won once, ratios stay ratios. */
    static ProjectionDto of(ProjectionMoment phase, int turnNo, BigDecimal totalValue, int horizonTurns, int pathCount,
            BigDecimal riskLimit, PortfolioResult portfolio) {
        BigDecimal total = MoneyMath.exactWon(totalValue);
        List<ProjectionBand> bands = portfolio.bands().stream().map(b -> band(total, b)).toList();
        List<ProjectionSummary> summaries = portfolio.summaries().stream().map(s -> summary(total, s)).toList();
        return new ProjectionDto(phase, turnNo, total, horizonTurns, pathCount, MoneyMath.exactRate(riskLimit), bands,
                summaries);
    }

    private static ProjectionBand band(BigDecimal total, Band b) {
        return new ProjectionBand(b.turn(), won(total, b.p05()), won(total, b.p25()), won(total, b.p50()), won(total, b.p75()),
                won(total, b.p95()));
    }

    private static ProjectionSummary summary(BigDecimal total, Summary s) {
        EndQuantiles e = s.end();
        MddQuantiles m = s.mdd();
        return new ProjectionSummary(s.turn(),
                new ProjectionEnd(won(total, e.p05()), won(total, e.p25()), won(total, e.p50()), won(total, e.p75()),
                        won(total, e.p95())),
                new ProjectionMdd(m.p05(), m.p10(), m.p25(), m.p50(), m.p75(), m.p95()), s.breachProbability());
    }

    private static BigDecimal won(BigDecimal total, BigDecimal multiple) {
        return MoneyMath.scaleByMultiple(total, multiple);
    }
}

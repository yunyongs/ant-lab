package antlab.leaderboard;

import antlab.agent.detection.DetectionMetric;
import antlab.agent.detection.DetectionStatus;
import antlab.common.exposure.ExposedFrom;
import antlab.member.MemberKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The manager leaderboard (AG-06). Members are ranked by L only; the multiple, the detection result and the failed turns
 * are shown and never rank. No member, agent, season, owner or league identifier and no date but the detection run time.
 */
@Schema(description = "The manager leaderboard: ranked rows, the members below the minimum, and the viewer's own progress / "
        + "감독 리더보드: 순위 행, 최소 시즌 미만 회원 수, 보는 사람 자신의 진행")
public record ManagerLeaderboardDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Formula formula,
        @ExposedFrom("season_manager_parts.reference_version")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Reference set version the parts were ranked against / "
                + "부품을 매긴 기준 집합 판")
        String referenceVersion,
        @ExposedFrom("season_manager_parts.process_score_version")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Process score versions of the seasons used by the listed "
                + "rows, ascending / 나열된 행이 쓴 시즌의 과정 점수 판, 오름차순")
        List<String> processScoreVersions,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The requested filter / 요청한 걸러 보기")
        LeaderboardKind kind,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Finished seasons of all members still waiting for their "
                + "parts / 모든 회원의 끝난 시즌 중 부품을 기다리는 수")
        Integer pendingSeasons,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Ranked rows in display order: L desc, seasonsUsed desc, "
                + "displayName / 순위 행, 표시 순서: L 내림차순, seasonsUsed 내림차순, displayName")
        List<Row> rows,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Unranked unranked,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"}, description = "The viewer's own progress, "
                + "whatever kind is requested. null when the viewer has no season / 보는 사람 자신의 진행(kind 와 무관). "
                + "시즌이 없으면 null")
        Me me) {

    @Schema(name = "ManagerScoreFormulaDto", description = "The current score formula; provisional until its status is FINAL / "
            + "현재 점수 공식. 상태가 FINAL 이 될 때까지 잠정")
    public record Formula(
            @ExposedFrom("manager_score_formula.version")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formula version / 공식 판")
            String version,
            @ExposedFrom("manager_score_formula.status")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            ManagerScoreFormulaStatus status,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            Weights weights) {
    }

    @Schema(name = "ManagerScoreWeightsDto", description = "Weights of the four parts, 2 decimal places, summing to 1 / "
            + "네 부품의 가중치, 소수 2자리, 합 1")
    public record Weights(
            @JsonProperty("return")
            @ExposedFrom("manager_score_formula.w_return")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Return part weight / 수익 부품 가중치")
            BigDecimal ret,
            @ExposedFrom("manager_score_formula.w_risk_adjusted")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk-adjusted part weight / 위험 조정 부품 가중치")
            BigDecimal riskAdjusted,
            @ExposedFrom("manager_score_formula.w_risk")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk part weight / 위험 부품 가중치")
            BigDecimal risk,
            @ExposedFrom("manager_score_formula.w_process")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Process part weight / 과정 부품 가중치")
            BigDecimal process) {
    }

    @Schema(name = "ManagerLeaderboardRowDto", description = "One ranked member. Rows carry no identifier / "
            + "순위에 든 회원 하나. 행에 식별자는 없다")
    public record Row(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "1 + the number of rows with a higher L (ties share the "
                    + "better rank), inside the requested kind / 1 + L 이 더 높은 행 수(동률은 좋은 순위를 함께), 요청한 kind 안에서")
            Integer rank,
            @ExposedFrom("member.kind")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            MemberKind kind,
            @ExposedFrom({"member.name", "persona_version.display_name", "agent_profile.model_id"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "A human's display name; for an agent the persona name "
                    + "and the model id joined by \" · \" / 사람은 표시 이름, 에이전트는 페르소나 이름과 모델 id 를 \" · \" 로 이은 것")
            String displayName,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True for the viewer's own row / 보는 사람 자신의 행이면 true")
            Boolean me,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True for an agent the viewer owns. Always false for a "
                    + "human row / 보는 사람이 소유한 에이전트면 true. 사람 행은 항상 false")
            Boolean ownedByMe,
            @ExposedFrom({"season_manager_parts.part_return", "season_manager_parts.part_risk_adjusted",
                    "season_manager_parts.part_risk", "season_manager_parts.part_process", "manager_score_formula.w_return",
                    "manager_score_formula.w_risk_adjusted", "manager_score_formula.w_risk", "manager_score_formula.w_process"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Career score L = trimmed mean − 0.5 × sample sd, 2 decimal "
                    + "places; may be negative / 경력 점수 L = 절사 평균 − 0.5 × 표본 표준편차, 소수 2자리. 음수일 수 있다")
            BigDecimal l,
            @ExposedFrom({"season_manager_parts.part_return", "season_manager_parts.part_risk_adjusted",
                    "season_manager_parts.part_risk", "season_manager_parts.part_process", "manager_score_formula.w_return",
                    "manager_score_formula.w_risk_adjusted", "manager_score_formula.w_risk", "manager_score_formula.w_process"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Mean S after dropping floor(m / 10) lowest and highest, "
                    + "2 decimal places; l is computed from the unrounded values, so trimmedMean − 0.5 × sampleSd may differ "
                    + "from l by 0.01 / floor(m / 10)개씩 가장 낮은·높은 S 를 뺀 평균, 소수 2자리. l 은 반올림 전 값으로 계산하므로 "
                    + "trimmedMean − 0.5 × sampleSd 가 l 과 0.01 다를 수 있다")
            BigDecimal trimmedMean,
            @ExposedFrom({"season_manager_parts.part_return", "season_manager_parts.part_risk_adjusted",
                    "season_manager_parts.part_risk", "season_manager_parts.part_process", "manager_score_formula.w_return",
                    "manager_score_formula.w_risk_adjusted", "manager_score_formula.w_risk", "manager_score_formula.w_process"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Sample standard deviation (n − 1) of the S used, 2 decimal "
                    + "places / 쓴 S 의 표본 표준편차(n − 1), 소수 2자리")
            BigDecimal sampleSd,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons L uses: the newest counted, at most 10 / "
                    + "L 이 쓴 시즌 수: 최근 집계 시즌, 최대 10")
            Integer seasonsUsed,
            @ExposedFrom("season_manager_parts.final_multiple")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Mean valuation multiple of the seasons used, 8 decimal "
                    + "places. Display only, never ranks / 쓴 시즌의 평균 평가 배수, 소수 8자리. 표시 전용, 순위에 쓰지 않는다")
            BigDecimal meanMultiple,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            Counts counts,
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                    description = "Agent details. null for a human row / 에이전트 정보. 사람 행은 null")
            Agent agent) {
    }

    @Schema(name = "ManagerSeasonCountsDto", description = "Every season of the member in exactly one bucket, tested in this "
            + "order: voided, unfinished, awaitingLeagueClose, pending, notStarted, mismatch, noReference, counted / "
            + "회원의 모든 시즌이 이 순서로 정확히 한 칸에 든다")
    public record Counts(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons in L's pool / L 에 들 수 있는 시즌")
            Integer counted,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "VOID seasons / 무효 시즌")
            Integer voided,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons not finished yet (running, paused, halted, "
                    + "abandoning) / 아직 끝나지 않은 시즌(진행·정지·멈춤·포기 중)")
            Integer unfinished,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Finished seats of a league still open / "
                    + "아직 열린 리그의 끝난 자리")
            Integer awaitingLeagueClose,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Finished seasons waiting for their parts / "
                    + "부품을 기다리는 끝난 시즌")
            Integer pending,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Human seasons abandoned before turn 3 / "
                    + "3턴 전에 포기한 사람 시즌")
            Integer notStarted,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons the reference refused as REFERENCE_MISMATCH / "
                    + "기준 집합이 REFERENCE_MISMATCH 로 거절한 시즌")
            Integer mismatch,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons without a reference (NO_REFERENCE) / "
                    + "기준 집합이 없는 시즌(NO_REFERENCE)")
            Integer noReference) {
    }

    @Schema(name = "ManagerLeaderboardAgentDto", description = "What an agent row shows of the agent; never its owner / "
            + "에이전트 행이 보이는 에이전트 정보. 소유자는 보이지 않는다")
    public record Agent(
            @ExposedFrom("persona_version.display_name")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Persona name of the newest season used / "
                    + "쓴 시즌 중 가장 최근 시즌의 페르소나 이름")
            String personaName,
            @ExposedFrom("season_agent_config.persona_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Persona version of the newest season used / "
                    + "쓴 시즌 중 가장 최근 시즌의 페르소나 버전")
            Integer personaVersion,
            @ExposedFrom("season_agent_config.persona_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Distinct persona versions of the seasons used, ascending / "
                    + "쓴 시즌의 페르소나 버전(중복 없이 오름차순)")
            List<Integer> personaVersionsUsed,
            @ExposedFrom("season_agent_config.memory_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "Memory version of the newest "
                    + "season used; null without memory / 쓴 시즌 중 가장 최근 시즌의 메모리 버전. 메모리가 없으면 null")
            Integer memoryVersion,
            @ExposedFrom("agent_profile.model_id")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Model identifier / 모델 식별자")
            String modelId,
            @ExposedFrom("agent_call.outcome")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turns over all the agent's seasons (not only the ones L "
                    + "uses) whose final model call ended NO_RESPONSE or INVALID / L 에 쓴 시즌만이 아니라 에이전트의 모든 시즌에서 최종 "
                    + "모델 호출이 NO_RESPONSE 또는 INVALID 로 끝난 턴 수")
            Integer failedTurns,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            Detection detection,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"}, description = "Outlook record; always null "
                    + "until AG-07 / 전망 기록. AG-07 전까지 항상 null")
            Object outlook) {
    }

    @Schema(name = "ManagerLeaderboardDetectionDto", description = "The model's detection test result. Report only: it never "
            + "filters or reorders rows / 모델의 탐지 시험 결과. 보고 전용이며 행을 거르거나 순서를 바꾸지 않는다")
    public record Detection(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED)
            DetectionStatus status,
            @ExposedFrom("detection_test.test_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "Test version; null when "
                    + "NOT_TESTED / 시험 판. NOT_TESTED 면 null")
            String testVersion,
            @ExposedFrom("detection_test.sample_layer")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "Sample layer; null when "
                    + "NOT_TESTED / 표본 층. NOT_TESTED 면 null")
            String sampleLayer,
            @ExposedFrom("detection_test.run_at")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time",
                    description = "Newest run time of the windows; null when NOT_TESTED / 창들 중 가장 최근 실행 시각. "
                            + "NOT_TESTED 면 null")
            Instant runAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"array", "null"}, description = "Windows in ascending months; "
                    + "null when NOT_TESTED / 창, 달 수 오름차순. NOT_TESTED 면 null")
            List<Window> windows) {
    }

    @Schema(name = "ManagerLeaderboardDetectionWindowDto", description = "One detection window / 탐지 시험 창 하나")
    public record Window(
            @ExposedFrom("detection_test.window_months")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Months shown to the model (1, 3 or 6) / "
                    + "모델에 보여 준 달 수(1·3·6)")
            Integer months,
            @ExposedFrom("detection_test.units")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Test units / 시험 단위 수")
            Integer units,
            @ExposedFrom("detection_test.run_at")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Run time of this window / 이 창의 실행 시각")
            Instant runAt,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            List<Metric> metrics) {
    }

    @Schema(name = "ManagerLeaderboardDetectionMetricDto", description = "One detection metric / 탐지 지표 하나")
    public record Metric(
            @ExposedFrom("detection_metric.metric")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            DetectionMetric metric,
            @ExposedFrom({"detection_metric.hits", "detection_metric.units"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Hits over units, 8 decimal places / "
                    + "맞힌 수 ÷ 채점 단위 수, 소수 8자리")
            BigDecimal accuracy,
            @ExposedFrom("detection_metric.base_rate")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Chance base rate, 8 decimal places / "
                    + "우연 기준 적중률, 소수 8자리")
            BigDecimal baseRate) {
    }

    @Schema(name = "ManagerLeaderboardUnrankedDto", description = "Members with at least one counted season but fewer than "
            + "the minimum (3 humans, 5 agents), whatever kind is requested / 집계 시즌이 1개 이상이지만 최소(사람 3, "
            + "에이전트 5)보다 적은 회원 수(kind 와 무관)")
    public record Unranked(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Humans below the minimum / 최소 미만 사람 수")
            Integer humans,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Agents below the minimum / 최소 미만 에이전트 수")
            Integer agents) {
    }

    @Schema(name = "ManagerLeaderboardMeDto", description = "The viewer's own progress and seasons. No other member's seasons "
            + "are ever returned / 보는 사람 자신의 진행과 시즌. 다른 회원의 시즌은 주지 않는다")
    public record Me(
            @ExposedFrom("member.kind")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            MemberKind kind,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Counted seasons / 집계 시즌 수")
            Integer counted,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Counted seasons needed to be ranked (3 humans, 5 agents) / "
                    + "순위에 드는 데 필요한 집계 시즌 수(사람 3, 에이전트 5)")
            Integer minimum,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True when counted reaches the minimum / "
                    + "집계 시즌이 최소에 닿으면 true")
            Boolean ranked,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "Rank in the requested table; "
                    + "null when not ranked or when the requested kind leaves the viewer out / 요청한 표의 순위. 순위에 들지 "
                    + "않았거나 요청한 kind 에 보는 사람이 없으면 null")
            Integer rank,
            @ExposedFrom({"season_manager_parts.part_return", "season_manager_parts.part_risk_adjusted",
                    "season_manager_parts.part_risk", "season_manager_parts.part_process", "manager_score_formula.w_return",
                    "manager_score_formula.w_risk_adjusted", "manager_score_formula.w_risk", "manager_score_formula.w_process"})
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "decimal",
                    description = "L, 2 decimal places; null when not ranked / L, 소수 2자리. 순위에 들지 않았으면 null")
            BigDecimal l,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            Counts counts,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The viewer's finished seasons, newest first / "
                    + "보는 사람의 끝난 시즌, 최근 순")
            List<MySeason> seasons) {
    }

    @Schema(name = "ManagerLeaderboardMySeasonDto", description = "One finished season of the viewer. s, parts, "
            + "processScoreVersion and multiple are set only when excluded is null / 보는 사람의 끝난 시즌 하나. s·parts·"
            + "processScoreVersion·multiple 은 excluded 가 null 일 때만 채운다")
    public record MySeason(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The viewer's own ordinal of the season in finish order "
                    + "(1 = first finished) / 끝난 순서로 매긴 보는 사람 자신의 시즌 순번(1 = 처음 끝난 시즌)")
            Integer seasonNo,
            @ExposedFrom({"season_manager_parts.exclusion", "league.status"})
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "Why the season is not in L; "
                    + "null when it counts / L 에 들지 않는 이유. 집계되면 null")
            LeaderboardExclusion excluded,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True when the season is among the newest 10 counted, so L "
                    + "uses it / 최근 집계 시즌 10개 안이라 L 이 쓰면 true")
            Boolean used,
            @ExposedFrom({"season_manager_parts.part_return", "season_manager_parts.part_risk_adjusted",
                    "season_manager_parts.part_risk", "season_manager_parts.part_process", "manager_score_formula.w_return",
                    "manager_score_formula.w_risk_adjusted", "manager_score_formula.w_risk", "manager_score_formula.w_process"})
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "decimal",
                    description = "Season score S under the current formula, 2 decimal places / 현재 공식의 시즌 점수 S, 소수 2자리")
            BigDecimal s,
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"})
            Parts parts,
            @ExposedFrom("season_manager_parts.process_score_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "Process score version / "
                    + "과정 점수 판")
            String processScoreVersion,
            @ExposedFrom("season_manager_parts.final_multiple")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "decimal",
                    description = "Valuation multiple, 8 decimal places. Display only / 평가 배수, 소수 8자리. 표시 전용")
            BigDecimal multiple) {
    }

    @Schema(name = "ManagerSeasonPartsDto", description = "The four parts of S, 0 to 100: return, risk-adjusted and risk at 8 "
            + "decimal places, process at 2 / S 의 네 부품(0~100): 수익·위험 조정·위험은 소수 8자리, 과정은 2자리")
    public record Parts(
            @JsonProperty("return")
            @ExposedFrom("season_manager_parts.part_return")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Return part / 수익 부품")
            BigDecimal ret,
            @ExposedFrom("season_manager_parts.part_risk_adjusted")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk-adjusted part / 위험 조정 부품")
            BigDecimal riskAdjusted,
            @ExposedFrom("season_manager_parts.part_risk")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk part / 위험 부품")
            BigDecimal risk,
            @ExposedFrom("season_manager_parts.part_process")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Process part (the season score's process score) / "
                    + "과정 부품(시즌 점수의 과정 점수)")
            BigDecimal process) {
    }
}

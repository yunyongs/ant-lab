package antlab.api.report;

import antlab.api.error.ApiProblem;
import antlab.common.exposure.FinishedSeasonOnly;
import antlab.scoring.SeasonReportDto;
import antlab.scoring.SeasonReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "season")
public class SeasonReportController {

    private static final String PROBLEM = "application/problem+json";

    private final SeasonReportService reports;

    public SeasonReportController(SeasonReportService reports) {
        this.reports = reports;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    @FinishedSeasonOnly
    @Operation(operationId = "getSeasonReport", summary = "시즌 성적표 조회",
            description = "끝난 시즌의 성적표(결과·기준선·과정·비용·대사 상태 37항목)를 저장 없이 조회로 계산해 준다. 진행 중이거나 포기 중인 시즌은 409 SEASON_NOT_FINISHED 다. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열(−0.2 = −20%)이고 값을 만들 수 없는 칸은 null 이다(키는 생략하지 않는다). 기준선은 대용 ETF(069500·SPY 50/50) 매수 후 보유이며 가격 수익률과 수정 종가가 섞인 편향이 있다. 날짜·월 블록·시드·종목·변경 번호는 싣지 않는다.\n" +
                    "\n" +
                    "제외(Phase 0 범위 밖):\n" +
                    "- X1 연평균 성장률(CAGR) 분수 거듭제곱(ln·exp)이 필요한데 Java BigDecimal 에 정확한 짝이 없어 골든 8자리 일치를 보장할 수 없다. 짧은 시즌에서는 값이 부풀려진다. → MVP(M3 점수와 함께)\n" +
                    "- X2 Sharpe 10턴 표본으로는 의미가 없고, 무위험 수익률(현금 무이자) 정의도 함께 정해야 한다. → MVP\n" +
                    "- X3 경로 백분위·일관성 예상 분포(수천 경로)가 필요하다. → M2\n" +
                    "- X4 60/40·내 포메이션(따라감)·시작 포메이션(고정) 기준선, 선수 선택·전술 변경·구조 효과 3분해, 변경 뒤 6턴 효과 밴드 리밸런싱 시뮬레이션과 포지션별 대표 ETF 표가 필요하다 (PLAN §6.1 \"MVP 구현 때 정한다\"). → MVP(Java)\n" +
                    "- X5 매매 규율(패닉·추격)·하락 직후 전술 변경 정의가 비어 있다: \"상승 직후\" 문턱이 없고 −8% 의 기준이 종목·팀·시장 중 무엇인지 문서에 없다. 기능 표 §3.4 의 \"경로에 있으면 포함\"과 다르다. → MVP\n" +
                    "- X6 분산 중 자산군 수·평균 상관 기능 표 P0-20 행에 없고 상관은 10턴으로는 불안정하다. → MVP\n" +
                    "- X7 소음 반응(방향) 피드 방향을 판정하는 규칙이 필요하다. → MVP\n" +
                    "- X8 관심 추종·수비수 득점·벤치 득점·신호 판별 복기 항목이라 점수에 넣지 않는다 (PLAN §6.2). 신호 판별은 EXT. → MVP/EXT\n" +
                    "- X9 세금·청산 시 세후·이연 세금 세금은 EXT 다 (PLAN §4.6). execution.tax 가 항상 0 인지는 검증이 단언한다. → EXT\n" +
                    "- X10 수수료 중 환전 몫 표시 몫을 나누면 원 단위 반올림 지점이 새로 생긴다. PLAN §4.6 이 \"표시만\"이라 한 항목이다. → MVP\n" +
                    "- X11 시즌 점수 0–100·레이팅 PLAN §6.3 은 초안이다. → M3\n" +
                    "- X12 성적표 저장(season_score) MVP 스케치다 (PLAN §5.1, SPRING_JPA §12-1). → MVP")
    @ApiResponse(responseCode = "200", description = "시즌 성적표")
    @ApiResponse(responseCode = "400", description = "시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "끝나지 않은 시즌이다 — SEASON_NOT_FINISHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonReportDto get(@PathVariable Long seasonId) {
        return reports.get(seasonId);
    }
}

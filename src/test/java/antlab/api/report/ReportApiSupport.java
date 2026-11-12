package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;

import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 성적표 API 시험 공용 도구. 합성 고정본(실제 시세 아님)의 12턴 시즌을 팀 구성부터 끝까지 진행한다. */
public final class ReportApiSupport {

    static final String[] SCRIPTS = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql", "/fixtures/turn_returns.sql"};

    /** 금액 열: 소수점 없는 정수 문자열. */
    public static final Set<String> MONEY = Set.of("initialCash", "finalValue", "baselineFinalValue", "tradeNotional",
            "totalFee", "teamFee", "formationChangeFee", "platformNudgeFee");
    /** 비율 열: 소수 8자리 문자열(값이 없으면 null). */
    public static final Set<String> RATIO = Set.of("totalReturn", "monthlyVolatility", "maxDrawdown", "baselineReturn",
            "excessReturn", "riskLimit", "riskBreachDepth", "maxSingleWeight", "turnoverAnnual", "formationDeviation",
            "roleFit", "nudgeShare", "nudgeSameTurnShare");
    public static final String MONEY_FORMAT = "-?\\d+";
    public static final String RATIO_FORMAT = "-?\\d+\\.\\d{8}";

    private ReportApiSupport() {
    }

    static MockHttpServletResponse getReport(MockMvcTester mvc, String seasonId) {
        return mvc.get().uri("/api/v1/seasons/" + seasonId + "/report").exchange().getResponse();
    }

    static MockHttpServletResponse getReport(MockMvcTester mvc, long seasonId) {
        return getReport(mvc, Long.toString(seasonId));
    }

    /** 시즌 시작 → 0턴 팀 → {@code advances} 번 전환. 12번이면 FINISHED. */
    static long season(MockMvcTester mvc, JdbcTemplate jdbc, int advances) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < advances; t++) {
            if (advance(mvc, season, t).getStatus() != 201) {
                throw new IllegalStateException("턴 전환 실패");
            }
        }
        return season;
    }

    static long finishedSeason(MockMvcTester mvc, JdbcTemplate jdbc) throws Exception {
        return season(mvc, jdbc, 12);
    }
}

package antlab.api.turn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 턴 조회·보유 조회 시험 공용 도구. 기대 비율은 시험 쪽에서 따로 계산한다(운영 코드의 함수를 쓰지 않는다). */
public final class TurnViewSupport {

    private TurnViewSupport() {
    }

    public static MockHttpServletResponse getTurn(MockMvcTester mvc, long seasonId, String turnNo) {
        return mvc.get().uri("/api/v1/seasons/" + seasonId + "/turns/" + turnNo).exchange().getResponse();
    }

    public static MockHttpServletResponse getTurn(MockMvcTester mvc, long seasonId, int turnNo) {
        return getTurn(mvc, seasonId, Integer.toString(turnNo));
    }

    public static MockHttpServletResponse getHoldings(MockMvcTester mvc, long seasonId) {
        return mvc.get().uri("/api/v1/seasons/" + seasonId + "/holdings").exchange().getResponse();
    }

    /** 정수 ÷ 정수 → 소수 8자리, 0에서 먼 쪽 HALF_UP (시험 쪽 독립 계산). */
    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    public static BigDecimal won(Object dbValue) {
        return ((BigDecimal) dbValue).setScale(0, RoundingMode.UNNECESSARY);
    }

    /**
     * 전술 변경을 시즌 현재 포메이션과 변경 기록에 함께 넣는다(대사 규칙 15 가 일관을 요구한다). API 로 변경을 만들 수 있게 되기 전까지의 고정 도구다.
     */
    public static void changeFormation(JdbcTemplate jdbc, long seasonId, int turnNo, int gk, int df, int mf, int fw) {
        Map<String, Object> cur = jdbc.queryForMap("SELECT current_goalkeeper, current_defender, current_midfielder,"
                + " current_forward FROM season WHERE id = ?", seasonId);
        jdbc.update("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender,"
                + " before_midfielder, before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", seasonId, turnNo, cur.get("current_goalkeeper"),
                cur.get("current_defender"), cur.get("current_midfielder"), cur.get("current_forward"), gk, df, mf, fw);
        jdbc.update("UPDATE season SET current_goalkeeper = ?, current_defender = ?, current_midfielder = ?,"
                + " current_forward = ?, version = version + 1 WHERE id = ?", gk, df, mf, fw, seasonId);
    }
}

package antlab.scoring;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 성적표와 시즌 실측 포지션의 상수 한 곳 — SQL 정의 {@code db/26_season_report_def.sql}({@code sr_k})과
 * {@code db/12_calc_position_def.sql}({@code pos_k}, {@code pos_classify})의 Java 짝이다.
 * 정의 파일을 바꾸면 이 파일도 같은 PR 에서 바꾼다(ReportRulesSqlParityTest 가 값을 대조한다).
 */
public final class ReportRules {

    /** 기준일 판정 창 달 수(12 {@code win_months}) */
    public static final int WINDOW_MONTHS = 36;
    /** 기준일 판정 창 끝 지연 달 수(12 {@code end_lag}) */
    public static final int WINDOW_END_LAG = 1;
    /** 기준일 판정의 최소 사용 달·오른 달·빠진 달(12 {@code min_months}·{@code min_up}·{@code min_down}) */
    public static final int BASE_MIN_MONTHS = 24;
    public static final int BASE_MIN_UP = 5;
    public static final int BASE_MIN_DOWN = 5;
    /** 시즌 실측의 최소 사용 달·오른 달·빠진 달(26 {@code sr_k}) — 10턴 시즌이 24·5·5 를 넘을 수 없어서 따로 둔다 */
    public static final int SEASON_MIN_MONTHS = 6;
    public static final int SEASON_MIN_UP = 3;
    public static final int SEASON_MIN_DOWN = 3;
    /** 월 수익률 → 연 환산 횟수(26 {@code annual}) — 회전율에만 쓴다. 변동성은 연율화하지 않는다 */
    public static final int ANNUAL_TURNS = 12;

    /** 오른·빠진 달의 시장 수익률 경계, 포함(12 {@code band}) */
    public static final BigDecimal BAND = new BigDecimal("0.01");
    /** 풀 상대 순위의 상위·하위 비율(12 {@code top_ratio}) */
    public static final BigDecimal TOP_RATIO = new BigDecimal("0.20");

    /** 시장 대용·기준선 대용 두 종목과 비중(12 {@code kr_*}·{@code us_*}·{@code w_*}, 26 기준선) */
    public static final String BENCH_KR_MARKET = "KR";
    public static final String BENCH_KR_TICKER = "069500";
    public static final String BENCH_US_MARKET = "US";
    public static final String BENCH_US_TICKER = "SPY";
    public static final BigDecimal BENCH_KR_WEIGHT = new BigDecimal("0.5");
    public static final BigDecimal BENCH_US_WEIGHT = new BigDecimal("0.5");

    /** 독려 반응으로 치는 주문 출처(26 {@code nudge_sources}) */
    public static final Set<String> NUDGE_SOURCES = Set.of("RANKING", "FEED", "ALERT");
    /** 플랫폼 수익 중 독려 몫으로 치는 주문 출처(26 {@code platform_sources}, 소유자 결정 P0-20 Q2) */
    public static final Set<String> PLATFORM_SOURCES = Set.of("RANKING", "ALERT");

    // 포지션 판정 문턱(12 pos_classify 한 곳). 판정 순서: 골키퍼 → 수비수 → 돌파형 → 수비적 → 타겟형 → 미드필더(나머지).
    /** 골키퍼: 하위 변동성이고 하락 참여율 ≤ 이 값 */
    public static final BigDecimal GOALKEEPER_DOWN_MAX = new BigDecimal("0.2");
    /** 수비수: 하락 참여율 &lt; 이 값 */
    public static final BigDecimal DEFENDER_DOWN_BELOW = new BigDecimal("0.6");
    /** 돌파형: 상위 변동성이고 상승 참여율 &gt; 이 값 */
    public static final BigDecimal BREAKOUT_UP_ABOVE = new BigDecimal("1.3");
    /** 돌파형: 최대 낙폭 ≤ 이 배수 × 시장 최대 낙폭(둘 다 0 이하) */
    public static final BigDecimal BREAKOUT_MDD_FACTOR = new BigDecimal("1.5");
    /** 수비적: 상승 참여율 ≥ 이 값 */
    public static final BigDecimal DEFENSIVE_UP_MIN = new BigDecimal("1.0");
    /** 수비적: 하락 참여율 &lt; 이 값 */
    public static final BigDecimal DEFENSIVE_DOWN_BELOW = new BigDecimal("0.8");
    /** 타겟형: 상승 참여율 &gt; 이 값 */
    public static final BigDecimal TARGET_UP_ABOVE = new BigDecimal("1.1");
    /** 타겟형: 하락 참여율 ≤ 이 값이고 상위 변동성이 아니다 */
    public static final BigDecimal TARGET_DOWN_MAX = new BigDecimal("1.2");

    private ReportRules() {
    }
}

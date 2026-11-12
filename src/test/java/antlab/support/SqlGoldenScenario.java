package antlab.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 골든 시험의 실행 계획. 파일명 정렬이나 glob 으로 자동 실행하지 않고 순서와 반복 횟수를 여기에 명시한다.
 * {@code _def} 는 다른 파일이 {@code \ir} 로 읽는 포함 파일이라 단계로 세지 않는다.
 */
public final class SqlGoldenScenario {

    public enum Kind {
        /** 종료 코드만 본다 */
        RUN,
        /** 종료 코드와 마지막 판정 행을 본다 */
        CHECK
    }

    /** 단계 하나. 경로는 작업 루트 기준 상대 경로다. {@code variables} 는 psql {@code -v} 변수(기본 없음). */
    public record ScriptStep(String path, Kind kind, String verdictColumn, Duration timeout, Map<String, String> variables) {
        public ScriptStep(String path, Kind kind, String verdictColumn, Duration timeout) {
            this(path, kind, verdictColumn, timeout, Map.of());
        }

        public ScriptStep {
            variables = Map.copyOf(variables);
            variables.forEach(PsqlScriptRunner::requireSafeVariable);
            PsqlScriptRunner.requireSafePath(path);
            if (kind == Kind.CHECK && (verdictColumn == null || verdictColumn.isBlank())) {
                throw new IllegalArgumentException("검증 단계에는 판정 열 이름이 필요하다: " + path);
            }
        }
    }

    /** 이름이 붙은 단계 목록 */
    public record ScenarioPlan(String name, List<ScriptStep> steps) {
    }

    static final Duration STEP_TIMEOUT = Duration.ofMinutes(5);
    /** 연습 시즌의 경로 길이(턴 수) */
    public static final int PATH_LENGTH = 10;

    private SqlGoldenScenario() {
    }

    private static ScriptStep run(String file) {
        return new ScriptStep("db/" + file + ".sql", Kind.RUN, null, STEP_TIMEOUT);
    }

    private static ScriptStep check(String file, String column) {
        return new ScriptStep("db/" + file + "_check.sql", Kind.CHECK, column, STEP_TIMEOUT);
    }

    /**
     * db/README 「전체 순서」 표 S01–S37 과 같은 순서다(단계 번호는 주석). 선택인 검증도 여기서는 필수다.
     * 22 수동 대사가 세 번 끼어 로그가 쌓인다(S21·S29·S34). 순서를 바꾸면 README 표도 같은 PR 에서 바꾼다.
     */
    public static ScenarioPlan readmeRegression() {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(run("01_schema_history"));                                   // S01
        steps.add(check("01_schema_history", "p0_05_check"));                  // S02
        steps.add(run("02_schema_season"));                                    // S03
        steps.add(check("02_schema_season", "p0_06_check"));                   // S04
        steps.add(run("03_schema_account"));                                   // S05
        steps.add(check("03_schema_account", "p0_07_check"));                  // S06
        // 01–03 검증은 표본 적재 전에 끝낸다 (빈 테이블 전제)
        steps.add(run("10_load_sample"));                                      // S07
        steps.add(check("10_load_sample", "p0_08_check"));                     // S08
        steps.add(run("11_load_events"));                                      // S09
        steps.add(check("11_load_events", "p0_09_check"));                     // S10
        steps.add(run("12_calc_position"));                                    // S11
        steps.add(check("12_calc_position", "p0_10_check"));                   // S12
        steps.add(run("13_calc_attention"));                                   // S13
        steps.add(check("13_calc_attention", "p0_11_check"));                  // S14
        steps.add(run("14_stats_calendar"));                                   // S15
        steps.add(check("14_stats_calendar", "p0_12_check"));                  // S16
        steps.add(run("20_season_start"));                                     // S17
        steps.add(check("20_season_start", "p0_14_check"));                    // S18
        steps.add(run("21_team_buy"));                                         // S19
        steps.add(check("21_team_buy", "p0_15_check"));                        // S20
        steps.add(run("22_reconcile_season"));                                 // S21
        steps.add(check("22_reconcile_season", "p0_16_check"));                // S22
        for (int i = 0; i < 5; i++) {
            steps.add(run("23_turn_advance"));                                 // S23 × 5
        }
        steps.add(check("23_turn_advance", "p0_17_check"));                    // S24
        steps.add(run("24_turn_feed"));                                        // S25
        steps.add(check("24_turn_feed", "p0_18_check"));                       // S26
        steps.add(run("25_trade_formation"));                                  // S27 (현재 턴 = 5)
        steps.add(check("25_trade_formation", "p0_19_check"));                 // S28
        // S29 턴 중간 점검 (23 검증은 범위 밖이라 넣지 않는다)
        steps.add(check("20_season_start", "p0_14_check"));
        steps.add(check("21_team_buy", "p0_15_check"));
        steps.add(check("22_reconcile_season", "p0_16_check"));
        steps.add(check("24_turn_feed", "p0_18_check"));
        steps.add(run("22_reconcile_season"));
        steps.add(check("26_season_report", "p0_20_check"));                   // S30 (잠정)
        for (int i = 0; i < PATH_LENGTH - 5; i++) {
            steps.add(run("23_turn_advance"));                                 // S31 × 5 (FINISHED)
        }
        steps.add(check("23_turn_advance", "p0_17_check"));                    // S32
        steps.add(check("24_turn_feed", "p0_18_check"));
        steps.add(check("25_trade_formation", "p0_19_check"));
        steps.add(run("20_season_start"));                                     // S33 끝난 뒤 재실행(새 행 없음, "이미 끝났다" 종료 0)
        steps.add(run("21_team_buy"));
        steps.add(run("23_turn_advance"));
        steps.add(check("20_season_start", "p0_14_check"));                    // S34
        steps.add(check("21_team_buy", "p0_15_check"));
        steps.add(run("22_reconcile_season"));
        steps.add(check("22_reconcile_season", "p0_16_check"));
        steps.add(run("26_season_report"));                                    // S35
        steps.add(check("26_season_report", "p0_20_check"));                   // S36
        steps.add(check("90_full_run", "p0_21_check"));                        // S37 최종 판정
        return new ScenarioPlan("README_REGRESSION", List.copyOf(steps));
    }

    /** 기준 시즌. 22 는 실행하지 않는다(시도 번호를 보존한다, P0-17 리뷰 13절). 검증 없이 재현 가능한 행만 만든다. */
    public static ScenarioPlan turnGolden() {
        return turnGoldenUntil(PATH_LENGTH, "TURN_GOLDEN");
    }

    /**
     * 매도 골든(P0-36)의 SQL 쪽 기준: 턴 골든의 5턴 지점 + 피드(db/24) + 5턴 매매(db/25)와 그 검증(p0_19_check).
     * 25 는 현재 턴이 5 이고 현재 턴 피드가 있어야 한다. 현재 턴은 5 에서 멈춘다(시즌은 진행 중).
     */
    public static ScenarioPlan tradeGolden() {
        List<ScriptStep> steps = new ArrayList<>(turnGoldenUntil(5, "TRADE_GOLDEN").steps());
        steps.add(run("24_turn_feed"));
        steps.add(run("25_trade_formation"));
        steps.add(check("25_trade_formation", "p0_19_check"));
        return new ScenarioPlan("TRADE_GOLDEN", List.copyOf(steps));
    }

    /**
     * 같은 턴 여러 변경 드릴 골든(P0-38 G4)의 SQL 쪽 기준: 턴 골든의 5턴 지점 + 매매·전술 변경 드릴 {@code rebuy} 사례 + 나머지 5턴.
     * 드릴(-v drill=1)은 커밋된 극단 상태를 남기므로 하네스의 버리는 DB 에서만 돌린다 — 하네스 DB 이름은 {@code golden_*} 이라
     * 상시 DB 이름 가드에 걸리지 않는다. 판정 열 {@code p0_19_drill_rebuy} 는 한 열이라 일반 검증 단계처럼 읽는다.
     * 드릴 파일은 {@link GoldenFixtureSource#withDrill()} 로 놓은 묶음에만 있다.
     */
    public static ScenarioPlan formationDrill() {
        List<ScriptStep> steps = new ArrayList<>(turnGoldenUntil(5, "FORMATION_DRILL").steps());
        steps.add(new ScriptStep("db/25_trade_formation_drill.sql", Kind.CHECK, "p0_19_drill_rebuy", STEP_TIMEOUT,
                Map.of("drill", "1", "drill_case", "rebuy")));
        for (int i = 0; i < PATH_LENGTH - 5; i++) {
            steps.add(run("23_turn_advance"));
        }
        return new ScenarioPlan("FORMATION_DRILL", List.copyOf(steps));
    }

    /** 이미 만들어진 SQL 시즌에서 23 을 {@code turns} 번 더 돌리는 토막(매도·전술 변경 골든이 5턴 뒤 10턴까지 잇는 데 쓴다). */
    public static ScenarioPlan advanceTurns(int turns) {
        if (turns < 1 || turns > PATH_LENGTH) {
            throw new IllegalArgumentException("턴 수가 범위를 벗어났다: " + turns);
        }
        List<ScriptStep> steps = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            steps.add(run("23_turn_advance"));
        }
        return new ScenarioPlan("ADVANCE_" + turns, List.copyOf(steps));
    }

    /** 변경 비용 조회 정의 파일(db/25) — 정의 질의로 formation_costs 를 부를 때 같은 세션에서 읽는다. */
    public static final String TRADE_DEFINITIONS = "db/25_trade_formation_def.sql";

    /**
     * 롤백 드릴 골든의 앞 토막: 턴 골든과 같은 시작에서 23 을 {@code turns} 번만 돌리고 멈춘다.
     * 끊은 자리에서 질의하고 드릴({@link #rollbackDrill()})을 끼운 뒤 {@link #turnGoldenFrom(int)} 으로 이어 간다.
     */
    public static ScenarioPlan turnGoldenUntil(int turns) {
        return turnGoldenUntil(turns, "TURN_GOLDEN_UNTIL_" + turns);
    }

    private static ScenarioPlan turnGoldenUntil(int turns, String name) {
        if (turns < 0 || turns > PATH_LENGTH) {
            throw new IllegalArgumentException("턴 수가 범위를 벗어났다: " + turns);
        }
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(run("01_schema_history"));
        steps.add(run("02_schema_season"));
        steps.add(run("03_schema_account"));
        steps.add(run("10_load_sample"));
        steps.add(run("11_load_events"));
        steps.add(run("12_calc_position"));
        steps.add(run("13_calc_attention"));
        steps.add(run("20_season_start"));
        steps.add(run("21_team_buy"));
        for (int i = 0; i < turns; i++) {
            steps.add(run("23_turn_advance"));
        }
        return new ScenarioPlan(name, List.copyOf(steps));
    }

    /**
     * 드릴이 한 번 성공 전환한 뒤(드릴 = 실패 1회 + 성공 1회) 시즌 끝까지 이어 가는 뒤 토막.
     * 드릴이 이미 한 턴을 진행했으므로 {@code PATH_LENGTH - drilledAt - 1} 번 더 돌린다.
     */
    public static ScenarioPlan turnGoldenFrom(int drilledAt) {
        if (drilledAt < 0 || drilledAt >= PATH_LENGTH) {
            throw new IllegalArgumentException("드릴 턴이 범위를 벗어났다: " + drilledAt);
        }
        List<ScriptStep> steps = new ArrayList<>();
        for (int i = 0; i < PATH_LENGTH - drilledAt - 1; i++) {
            steps.add(run("23_turn_advance"));
        }
        return new ScenarioPlan("TURN_GOLDEN_FROM_" + drilledAt, List.copyOf(steps));
    }

    /**
     * 롤백 너머 기록 드릴(P0-17) — 버리는 DB 에서만. {@code -v drill=1} 이 가드이고 판정 열은 {@code p0_17_drill} 이다.
     * 하네스 DB 이름은 {@code golden_*} 이라 상시 DB 이름 가드에 걸리지 않는다.
     * 판정이 건수 열(4열)이라 `SqlGoldenHarness.execute`·`assertCheckPassed` 에 넣지 않는다 — `run` 뒤 `CheckVerdictParser.parseWithCounts` 로만 읽는다(이 단계의 `ScriptResult.reason` 은 한 열 판독기의 값이라 뜻이 없다).
     */
    public static ScriptStep rollbackDrill() {
        return new ScriptStep("db/23_turn_advance_drill.sql", Kind.CHECK, "p0_17_drill", STEP_TIMEOUT, Map.of("drill", "1"));
    }

    /** 턴 골든을 끝낸 뒤 20·21 재실행(새 행 없음)과 23 추가 실행("이미 끝났다")의 멱등성 확인용 단계 */
    public static ScenarioPlan afterFinishIdempotence() {
        return new ScenarioPlan("AFTER_FINISH_IDEMPOTENCE", List.of(
                run("20_season_start"), run("21_team_buy"), run("23_turn_advance")));
    }

    /**
     * 시즌 시작 골든(P0-30)의 SQL 쪽 기준: 스키마 → 표본 적재 → 판정 계산 → 시즌 시작 → 시즌 시작 검증.
     * 11(사건)·13(관심 지수)은 db/20 이 필요로 하지 않아 넣지 않는다(db/20 머리 주석).
     */
    public static ScenarioPlan seasonStartGolden() {
        return new ScenarioPlan("SEASON_START_GOLDEN", List.of(
                run("01_schema_history"), run("02_schema_season"), run("03_schema_account"),
                run("10_load_sample"), run("12_calc_position"),
                run("20_season_start"), check("20_season_start", "p0_14_check")));
    }

    /**
     * 팀 구성 골든(P0-31)의 SQL 쪽 기준: 시즌 시작 골든 + 0턴 팀 매수(db/21) + 그 검증(p0_15_check).
     * 11·13 은 db/21 이 필요로 하지 않아 넣지 않는다.
     */
    public static ScenarioPlan teamBuyGolden() {
        List<ScriptStep> steps = new ArrayList<>(seasonStartGolden().steps());
        steps.add(run("21_team_buy"));
        steps.add(check("21_team_buy", "p0_15_check"));
        return new ScenarioPlan("TEAM_BUY_GOLDEN", List.copyOf(steps));
    }

    /** 팀 매수 검증 파일 하나만 — API 가 주문을 넣은 DB 에서 SQL 검증을 그대로 돌린다. */
    public static ScriptStep teamBuyCheck() {
        return check("21_team_buy", "p0_15_check");
    }

    /** 대사 정의 파일(db/22) — 정의 질의로 recon_rules 를 부를 때 같은 세션에서 읽는다. */
    public static final String RECONCILE_DEFINITIONS = "db/22_reconcile_season_def.sql";

    /**
     * API DB(Flyway 로 V1–V3 이 이미 돈 DB)의 표본 적재 순서 — 스키마 단계는 건너뛰고 10 → 12 만 돌린다
     * (db/README 「API DB 표본 적재」와 같은 순서).
     */
    public static ScenarioPlan apiDbLoad() {
        return new ScenarioPlan("API_DB_LOAD", List.of(run("10_load_sample"), run("12_calc_position")));
    }

    /** 기준일 판정 대조용 — 스키마(01–03)와 표본 적재(10), 기준일 판정 계산(12)까지만. 시즌은 만들지 않는다(P0-38 PositionMeter 대조). */
    public static ScenarioPlan positionBaseDate() {
        return new ScenarioPlan("POSITION_BASE_DATE", List.of(run("01_schema_history"), run("02_schema_season"),
                run("03_schema_account"), run("10_load_sample"), run("12_calc_position")));
    }

    /** 시즌 시작 검증 파일 하나만 — API 가 시즌을 만든 DB 에서 SQL 검증 20개를 그대로 돌린다. */
    public static ScriptStep seasonStartCheck() {
        return check("20_season_start", "p0_14_check");
    }
}

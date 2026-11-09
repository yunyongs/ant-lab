package antlab.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    /** 단계 하나. 경로는 작업 루트 기준 상대 경로다. */
    public record ScriptStep(String path, Kind kind, String verdictColumn, Duration timeout) {
        public ScriptStep {
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

    /** README 전체 순서. 선택인 검증도 여기서는 필수다. 22 수동 대사를 끼운다(로그가 쌓인다). */
    public static ScenarioPlan readmeRegression() {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(run("01_schema_history"));
        steps.add(check("01_schema_history", "p0_05_check"));
        steps.add(run("02_schema_season"));
        steps.add(check("02_schema_season", "p0_06_check"));
        steps.add(run("03_schema_account"));
        steps.add(check("03_schema_account", "p0_07_check"));
        // 01–03 검증은 표본 적재 전에 끝낸다 (빈 테이블 전제)
        steps.add(run("10_load_sample"));
        steps.add(check("10_load_sample", "p0_08_check"));
        steps.add(run("11_load_events"));
        steps.add(check("11_load_events", "p0_09_check"));
        steps.add(run("12_calc_position"));
        steps.add(check("12_calc_position", "p0_10_check"));
        steps.add(run("13_calc_attention"));
        steps.add(check("13_calc_attention", "p0_11_check"));
        steps.add(run("14_stats_calendar"));
        steps.add(check("14_stats_calendar", "p0_12_check"));
        steps.add(run("20_season_start"));
        steps.add(check("20_season_start", "p0_14_check"));
        steps.add(run("21_team_buy"));
        steps.add(check("21_team_buy", "p0_15_check"));
        steps.add(run("22_reconcile_season"));
        steps.add(check("22_reconcile_season", "p0_16_check"));
        for (int i = 0; i < PATH_LENGTH; i++) {
            steps.add(run("23_turn_advance"));
        }
        steps.add(check("23_turn_advance", "p0_17_check"));
        return new ScenarioPlan("README_REGRESSION", List.copyOf(steps));
    }

    /** 기준 시즌. 22 는 실행하지 않는다(시도 번호를 보존한다, P0-17 리뷰 13절). 검증 없이 재현 가능한 행만 만든다. */
    public static ScenarioPlan turnGolden() {
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
        for (int i = 0; i < PATH_LENGTH; i++) {
            steps.add(run("23_turn_advance"));
        }
        return new ScenarioPlan("TURN_GOLDEN", List.copyOf(steps));
    }

    /** 턴 골든을 끝낸 뒤 20·21 재실행(새 행 없음)과 23 추가 실행("이미 끝났다")의 멱등성 확인용 단계 */
    public static ScenarioPlan afterFinishIdempotence() {
        return new ScenarioPlan("AFTER_FINISH_IDEMPOTENCE", List.of(
                run("20_season_start"), run("21_team_buy"), run("23_turn_advance")));
    }
}

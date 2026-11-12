package antlab.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 하류 재생(Java 가 같은 시즌을 다시 만들기)이 읽는 입력. 시험 내부 전용이며 운영 DTO 가 아니다.
 *
 * <p>값 전체는 메모리에만 두고 {@code toString} 은 건수만 낸다. 같은 시드라는 이유로 경로가 같다고 보지 않고,
 * 추출한 경로(월 블록 날짜)와 설정을 명시적으로 공급한다.
 *
 * @param settings   시즌 설정(한 행)
 * @param path       턴 번호 순으로 정렬된 경로
 * @param actions    실행 순서가 있는 행동 목록: 턴별로 교차한 주문·전술 변경·라인업 변경과 턴 전환(턴 k 의 행동 → 전환 k+1)
 * @param orderKeys  원본 주문 ID → 재생 행동 키 (서로 다른 DB 의 ID 를 맞출 때 쓴다)
 */
public record SeasonReplayInput(RowSet settings, RowSet path, List<ReplayAction> actions, Map<Long, String> orderKeys) {

    public enum ActionKind { PLACE_ORDER, FORMATION_CHANGE, LINEUP_CHANGE, ADVANCE_TURN }

    /**
     * 행동 하나. 행동마다 필요한 입력이 달라 한 자료형에 의미 없는 칸을 끼워 넣지 않고 행동별 record 로 나눈다.
     * {@code toString} 은 종류만 낸다(수량·비중·사유를 로그에 남기지 않는다).
     */
    public sealed interface ReplayAction {
        ActionKind kind();
    }

    /** 주문. {@code key} 는 행동 키(턴/시장/종목/방향/순번). */
    public record PlaceOrder(String key, long quantity) implements ReplayAction {
        @Override
        public ActionKind kind() {
            return ActionKind.PLACE_ORDER;
        }

        @Override
        public String toString() {
            return "ReplayAction[PLACE_ORDER]";
        }
    }

    /** 새 포메이션 네 칸. */
    public record Shape(int goalkeeper, int defender, int midfielder, int forward) {
        @Override
        public String toString() {
            return "Shape[]";
        }
    }

    /** 전술 변경. {@code ordinal} 은 같은 턴 안의 변경 순번(번호 순, 1부터)이고 비교의 논리 키다. */
    public record FormationChange(long turn, long ordinal, Shape after, String reason) implements ReplayAction {
        @Override
        public ActionKind kind() {
            return ActionKind.FORMATION_CHANGE;
        }

        @Override
        public String toString() {
            return "ReplayAction[FORMATION_CHANGE]";
        }
    }

    /** 선언 포지션 단독 변경. {@code asset} 은 시장/종목, {@code position} 은 새 선언 포지션. */
    public record LineupChange(long turn, String asset, String position) implements ReplayAction {
        @Override
        public ActionKind kind() {
            return ActionKind.LINEUP_CHANGE;
        }

        @Override
        public String toString() {
            return "ReplayAction[LINEUP_CHANGE]";
        }
    }

    /** 턴 전환. {@code toTurn} 은 새 턴 번호. */
    public record AdvanceTurn(long toTurn) implements ReplayAction {
        @Override
        public ActionKind kind() {
            return ActionKind.ADVANCE_TURN;
        }

        @Override
        public String toString() {
            return "ReplayAction[ADVANCE_TURN]";
        }
    }

    /** 한 턴의 시각 있는 행동(주문·전술 변경). {@code at} 은 같은 DB 시계의 기록 시각이다. */
    public record Timed(long turn, Instant at, ReplayAction action) {
        @Override
        public String toString() {
            return "Timed[turn=" + turn + ", " + action.kind() + "]";
        }
    }

    /**
     * 캡처한 행동을 실행 순서로 병합하는 순수 함수(DB 를 읽지 않는다): 턴 0 의 행동 → 전환 1 → 턴 1 의 행동 → … → 현재 턴의 행동.
     *
     * <p>같은 턴 안에서 주문과 전술 변경은 기록 시각 순으로 섞는다(서로 다른 표의 번호는 전역 순번이 아니라 비교하지 않는다).
     * 같은 턴에 시각이 같은 행동이 둘이면 순서를 정할 수 없어 실패한다 — 입력 순서에 기대지 않는다. 다른 턴의 같은 시각은 문제없다.
     * 라인업 변경은 시각이 없어 그 턴의 시각 있는 행동 <b>뒤</b>에, 입력 순서대로 둔다. 현재 턴보다 늦은 턴의 행동이 있으면 실패한다.
     *
     * @param currentTurn   시즌의 현재 턴(0 이상). 전환은 1..currentTurn
     * @param timed         시각 있는 행동(순서 무관)
     * @param lineupChanges 라인업 변경(턴 안에서는 입력 순서를 지킨다)
     */
    public static List<ReplayAction> merge(long currentTurn, List<Timed> timed, List<LineupChange> lineupChanges) {
        List<Timed> sorted = new ArrayList<>(timed);
        sorted.sort(Comparator.comparingLong(Timed::turn).thenComparing(Timed::at));
        for (int i = 1; i < sorted.size(); i++) {
            Timed prev = sorted.get(i - 1);
            Timed cur = sorted.get(i);
            if (prev.turn() == cur.turn() && prev.at().equals(cur.at())) {
                throw new IllegalStateException("같은 턴에 기록 시각이 같은 행동이 있어 순서를 정할 수 없다: 턴 " + cur.turn());
            }
        }
        for (Timed t : sorted) {
            if (t.turn() > currentTurn) {
                throw new IllegalStateException("현재 턴보다 늦은 턴의 행동이 있다");
            }
        }
        for (LineupChange change : lineupChanges) {
            if (change.turn() > currentTurn) {
                throw new IllegalStateException("현재 턴보다 늦은 턴의 행동이 있다");
            }
        }
        List<ReplayAction> actions = new ArrayList<>();
        for (long turn = 0; turn <= currentTurn; turn++) {
            if (turn > 0) {
                actions.add(new AdvanceTurn(turn));
            }
            for (Timed t : sorted) {
                if (t.turn() == turn) {
                    actions.add(t.action());
                }
            }
            for (LineupChange change : lineupChanges) {
                if (change.turn() == turn) {
                    actions.add(change);
                }
            }
        }
        return List.copyOf(actions);
    }

    @Override
    public String toString() {
        return "SeasonReplayInput[path=" + path.size() + "턴, actions=" + actions.size() + "]";
    }
}

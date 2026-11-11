package antlab.support;

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
 * @param actions    실행 순서가 있는 행동 목록: 턴별로 교차한 주문과 턴 전환(턴 k 의 주문 → 전환 k+1)
 * @param orderKeys  원본 주문 ID → 재생 행동 키 (서로 다른 DB 의 ID 를 맞출 때 쓴다)
 */
public record SeasonReplayInput(RowSet settings, RowSet path, List<ReplayAction> actions, Map<Long, String> orderKeys) {

    public enum ActionKind { PLACE_ORDER, ADVANCE_TURN }

    /** 행동 하나. {@code key} 는 PLACE_ORDER 의 행동 키(턴/시장/종목/방향/순번), ADVANCE_TURN 은 새 턴 번호다. */
    public record ReplayAction(ActionKind kind, String key, long quantity) {
        @Override
        public String toString() {
            return "ReplayAction[" + kind + "]";
        }
    }

    @Override
    public String toString() {
        return "SeasonReplayInput[path=" + path.size() + "턴, actions=" + actions.size() + "]";
    }
}

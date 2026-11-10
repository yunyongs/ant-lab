package antlab.history;

import java.time.LocalDate;

/**
 * 경로 엔진이 읽는 (월 블록 번호, 달) 쌍. 엔진 내부 전용이다 — monthStart 는 [복기 전용] 값이라 응답·로그에 싣지 않는다.
 */
public record ReplayMonth(Long monthBlockId, LocalDate monthStart) {

    @Override
    public String toString() {
        return "ReplayMonth[...]";
    }
}

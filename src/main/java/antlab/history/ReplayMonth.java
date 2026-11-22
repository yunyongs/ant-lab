package antlab.history;

import java.time.LocalDate;

/**
 * 경로 엔진이 읽는 (월 블록 번호, 달) 쌍과 국면 라벨(없으면 null). 엔진 내부 전용이다 — monthStart·regimeLabel 은 [복기 전용] 값이라 응답·로그에 싣지 않는다.
 */
public record ReplayMonth(Long monthBlockId, LocalDate monthStart, RegimePool regimeLabel) {

    public ReplayMonth(Long monthBlockId, LocalDate monthStart) {
        this(monthBlockId, monthStart, null);
    }

    @Override
    public String toString() {
        return "ReplayMonth[...]";
    }
}

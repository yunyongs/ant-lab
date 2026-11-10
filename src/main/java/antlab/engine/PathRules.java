package antlab.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 경로 규칙 검사기(순수). 위반한 규칙의 이름만 돌려주고 달·번호는 싣지 않는다([복기 전용] 값).
 * 운영 사후 검사, 골든 고정 경로, 시험이 함께 쓴다.
 * <ul>
 *   <li>S1_COUNT 행 수 = 길이 · S1_RANGE 모든 달이 2007-01-01 이상 기준일 해 1월 1일 미만</li>
 *   <li>S4 턴 k 의 달 = ((k−1) % 12) + 1월 · REUSE 같은 달을 두 번 쓰지 않음</li>
 *   <li>BLOCK_GAP 블록 안의 연속된 턴이 연속된 달이 아님(끊긴 곳을 넘음) · BLOCK_JOIN 블록 경계가 역사 그대로 이어짐 —
 *       블록 길이를 아는 검사에서만 본다</li>
 * </ul>
 */
public final class PathRules {

    private PathRules() {
    }

    /** 블록 길이를 모르는 검사(골든 고정 경로 등 — 블록 길이를 강요하지 않는다). */
    public static List<String> violations(List<LocalDate> months, LocalDate baseDate, int length) {
        return violations(months, baseDate, length, 0);
    }

    /** blockLength &gt; 0 이면 BLOCK_GAP·BLOCK_JOIN 도 본다. */
    public static List<String> violations(List<LocalDate> months, LocalDate baseDate, int length, int blockLength) {
        Objects.requireNonNull(months, "months");
        Objects.requireNonNull(baseDate, "baseDate");
        List<String> result = new ArrayList<>();
        if (months.size() != length) {
            result.add("S1_COUNT");
        }
        LocalDate upper = SimpleBootstrap.replayUpperBound(baseDate);
        boolean outOfRange = false;
        boolean calendar = false;
        boolean reuse = false;
        Set<LocalDate> seen = new HashSet<>();
        for (int i = 0; i < months.size(); i++) {
            LocalDate m = months.get(i);
            outOfRange |= m.isBefore(SimpleBootstrap.REPLAY_START) || !m.isBefore(upper);
            calendar |= m.getMonthValue() != (i % 12) + 1;
            reuse |= !seen.add(m);
        }
        if (outOfRange) {
            result.add("S1_RANGE");
        }
        if (calendar) {
            result.add("S4");
        }
        if (reuse) {
            result.add("REUSE");
        }
        if (blockLength > 0) {
            boolean gap = false;
            boolean join = false;
            for (int i = 1; i < months.size(); i++) {
                boolean consecutive = months.get(i - 1).plusMonths(1).equals(months.get(i));
                if (i % blockLength != 0) {
                    gap |= !consecutive;
                } else {
                    join |= consecutive;
                }
            }
            if (gap) {
                result.add("BLOCK_GAP");
            }
            if (join) {
                result.add("BLOCK_JOIN");
            }
        }
        return result;
    }
}

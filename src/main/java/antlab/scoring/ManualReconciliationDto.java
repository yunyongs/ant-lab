package antlab.scoring;

import java.util.List;

/**
 * 수동 검사 응답. {@code result} 는 15개가 모두 일치하면 {@code MATCH}, 하나라도 아니면 {@code MISMATCH}.
 * 불일치여도 기록은 커밋되고 응답은 200 이다.
 */
public record ManualReconciliationDto(long seasonId, int turnNo, int attemptNo, String result, int mismatchRules,
        List<RuleSummaryDto> rules) {
}

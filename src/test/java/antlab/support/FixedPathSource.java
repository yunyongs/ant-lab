package antlab.support;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.engine.GeneratedPath;
import antlab.engine.PathRequest;
import antlab.engine.PathRules;
import antlab.engine.PathSource;
import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험 전용 경로 출처 — SQL 시즌의 (턴 → 달) 목록을 같은 DB 의 월 블록 번호로 바꾼다(골든 시험용).
 * 운영 코드에는 두지 않는다(난수를 우회하는 경로 출처를 운영에 남기지 않는다).
 */
public final class FixedPathSource implements PathSource {

    private final String engineVersion;
    private final List<LocalDate> monthsByTurn;
    private final MonthBlockRepository monthBlocks;

    public FixedPathSource(String engineVersion, List<LocalDate> monthsByTurn, MonthBlockRepository monthBlocks) {
        this.engineVersion = engineVersion;
        this.monthsByTurn = List.copyOf(monthsByTurn);
        this.monthBlocks = monthBlocks;
    }

    @Override
    public GeneratedPath generate(PathRequest request) {
        if (request.length() != monthsByTurn.size()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "고정 경로의 길이와 요청 길이가 다르다");
        }
        List<String> violations = PathRules.violations(monthsByTurn, request.baseDate(), request.length());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("고정 경로 규칙 위반: " + violations);
        }
        Map<LocalDate, Long> idByMonth = new HashMap<>();
        for (ReplayMonth m : monthBlocks.findReplayMonths(LocalDate.of(1900, 1, 1), LocalDate.of(2200, 1, 1))) {
            idByMonth.put(m.monthStart(), m.monthBlockId());
        }
        List<Long> ids = monthsByTurn.stream().map(m -> {
            Long id = idByMonth.get(m);
            if (id == null) {
                throw new IllegalStateException("고정 경로의 달이 월 블록에 없다");
            }
            return id;
        }).toList();
        return new GeneratedPath(ids, engineVersion);
    }
}

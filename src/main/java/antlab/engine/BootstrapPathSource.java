package antlab.engine;

import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 운영 경로 출처 — 단순 부트스트랩. 재생 범위의 달을 읽어 {@link SimpleBootstrap} 로 경로를 만들고 규칙을 한 번 더 검사한다.
 * 트랜잭션은 호출하는 서비스가 연다(읽기만 한다).
 */
@Component
public class BootstrapPathSource implements PathSource {

    private final MonthBlockRepository monthBlocks;

    public BootstrapPathSource(MonthBlockRepository monthBlocks) {
        this.monthBlocks = monthBlocks;
    }

    @Override
    public GeneratedPath generate(PathRequest request) {
        List<ReplayMonth> months = monthBlocks.findReplayMonths(
                SimpleBootstrap.REPLAY_START, SimpleBootstrap.replayUpperBound(request.baseDate()));
        GeneratedPath path = SimpleBootstrap.generate(months, request);

        // 엔진 버그 방어선: 번호 → 달로 되돌려 규칙을 다시 본다. 정상이면 도달하지 않는다.
        Map<Long, LocalDate> monthById = new HashMap<>();
        for (ReplayMonth m : months) {
            monthById.put(m.monthBlockId(), m.monthStart());
        }
        List<LocalDate> pathMonths = new ArrayList<>();
        for (Long id : path.monthBlockIds()) {
            pathMonths.add(monthById.get(id));
        }
        List<String> violations = PathRules.violations(
                pathMonths, request.baseDate(), request.length(), SimpleBootstrap.BLOCK_LENGTH);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("경로 규칙 위반: " + violations);
        }
        return path;
    }
}

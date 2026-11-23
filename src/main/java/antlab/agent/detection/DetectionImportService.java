package antlab.agent.detection;

import antlab.agent.decision.SchemaViolation;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Imports one {@code detection-summary/1} document. The document must pass its checks (400 with pointers and keywords in
 * {@code errors}, never a value). Under a lock on the source hash: a summary already stored under the hash and equal to
 * this one is a replay that writes nothing; a different one is {@code DETECTION_CONFLICT}; otherwise the rows are
 * inserted. Detection is report only: nothing here touches a score, a rank or a season.
 */
@Service
public class DetectionImportService {

    private final DetectionWriter writer;
    private final Clock clock;

    public DetectionImportService(DetectionWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    public record Outcome(boolean created, int windows) {
    }

    @Transactional
    public Outcome importSummary(JsonNode document) {
        DetectionSummaryDocument.Result read = DetectionSummaryDocument.read(document, clock.instant());
        if (!read.violations().isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "탐지 요약이 detection-summary/1 규칙에 맞지 않다",
                    Map.of("errors", errors(read.violations())));
        }
        DetectionSummary summary = read.summary();
        writer.lockSource(summary.sourceSha256());
        Optional<DetectionSummary> stored = writer.findBySource(summary.sourceSha256());
        if (stored.isPresent()) {
            if (!stored.get().equals(summary)) {
                throw new DomainException(ErrorCode.DETECTION_CONFLICT, "같은 원본 해시로 다른 탐지 요약이 이미 기록되어 있다");
            }
            return new Outcome(false, summary.windows().size());
        }
        writer.insert(summary);
        return new Outcome(true, summary.windows().size());
    }

    private static List<Map<String, String>> errors(List<SchemaViolation> violations) {
        return violations.stream().map(v -> Map.of("field", v.pointer(), "reason", v.keyword())).toList();
    }
}

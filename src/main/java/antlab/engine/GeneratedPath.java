package antlab.engine;

import java.util.List;
import java.util.Objects;

/**
 * monthBlockIds.get(k − 1) = 턴 k 의 month_block.id. 크기 = 경로 길이. engineVersion 은 season.engine_version 에 저장한다.
 * 블록 번호는 [복기 전용] 값이므로 toString 에 싣지 않는다(SPRING_JPA §4.6).
 */
public record GeneratedPath(List<Long> monthBlockIds, String engineVersion) {

    private static final int MAX_VERSION_LENGTH = 20; // season.engine_version VARCHAR(20)

    public GeneratedPath {
        monthBlockIds = List.copyOf(Objects.requireNonNull(monthBlockIds, "monthBlockIds"));
        Objects.requireNonNull(engineVersion, "engineVersion");
        if (engineVersion.isEmpty() || engineVersion.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("engineVersion 은 1–20자다");
        }
    }

    @Override
    public String toString() {
        return "GeneratedPath[" + monthBlockIds.size() + "턴, " + engineVersion + "]";
    }
}

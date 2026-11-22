package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The engine's tracked m2-sbc6-v1 paths must pass the Java path rules that apply to the version (count, range, reuse;
 * the calendar rule S4 does not apply). Java never regenerates these paths, it only checks them.
 */
class EngineConditionalVectorsTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);
    private static final Map<String, LocalDate> REPLAY_START = Map.of(
            "persist", LocalDate.of(1997, 1, 1), "patchy", LocalDate.of(1997, 1, 1),
            "sample", LocalDate.of(2007, 1, 1), "tight24", LocalDate.of(2007, 1, 1));

    @Test
    void 조건부_벡터의_모든_경로가_자바_경로_규칙을_지킨다() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("engine/tests/vectors/m2-sbc6-v1_vectors.csv"));
        assertThat(lines.get(0)).startsWith("case,labels,seed,length,months,");
        assertThat(lines.size()).isGreaterThan(50);
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            LocalDate replayStart = REPLAY_START.get(cols[1]);
            assertThat(replayStart).as(cols[1]).isNotNull();
            List<LocalDate> months = Arrays.stream(cols[4].split(" ")).map(m -> YearMonth.parse(m).atDay(1)).toList();

            List<String> violations = PathRules.violations(months, BASE, Integer.parseInt(cols[3]), 0, replayStart).stream()
                    .filter(rule -> !"S4".equals(rule)).toList();

            assertThat(violations).as(cols[0]).isEmpty();
        }
    }
}

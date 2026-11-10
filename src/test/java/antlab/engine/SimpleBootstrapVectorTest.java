package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 고정 벡터 — 상수나 알고리즘이 바뀌면 깨진다(바꿀 때는 엔진 판을 올리고 벡터를 다시 만든다).
 * 벡터를 다시 만들 때는 Java 코드를 보지 않고 규칙(erd §9 Phase 0 API ⑦, 이 클래스 상수)만으로 쓴
 * 독립 구현과 글자 단위로 대조한다 — M2 Python 병행 대사의 기준이다.
 */
class SimpleBootstrapVectorTest {

    @Test
    void 상수가_고정되어_있다() {
        assertThat(SimpleBootstrap.BLOCK_LENGTH).isEqualTo(6);
        assertThat(SimpleBootstrap.ALLOWED_LENGTHS).containsExactly(12, 24, 60);
        assertThat(SimpleBootstrap.MAX_LENGTH).isEqualTo(60);
        assertThat(SimpleBootstrap.REPLAY_START).isEqualTo(LocalDate.of(2007, 1, 1));
        assertThat(SimpleBootstrap.ENGINE_VERSION).isEqualTo("p0-boot6-v1");
    }

    @Test
    void 고정_벡터와_글자_단위로_같다() throws IOException {
        List<String> lines;
        try (InputStream in = getClass().getResourceAsStream("/engine/p0-boot6-v1_vectors.csv")) {
            assertThat(in).isNotNull();
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertThat(lines.get(0)).isEqualTo("seed,length,months");
        assertThat(lines.size()).isGreaterThan(10);
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", 3);
            long seed = Long.parseLong(cols[0]);
            int length = Integer.parseInt(cols[1]);
            List<LocalDate> path = SimpleBootstrap.generateMonths(SyntheticMonths.sample(),
                    new PathRequest(SyntheticMonths.BASE_DATE, length, seed));
            String actual = String.join(" ", path.stream().map(d -> YearMonth.from(d).toString()).toList());
            assertThat(actual).as("seed=" + seed + " length=" + length).isEqualTo(cols[2]);
        }
    }
}

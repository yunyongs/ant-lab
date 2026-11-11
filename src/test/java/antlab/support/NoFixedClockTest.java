package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 시험의 시계는 흐르는 시계다. 고정 시계를 쓰면 같은 시각의 행이 생겨 {@code created_at} 순서(대사 규칙 16)를 판단할 수 없고,
 * 시도 묶음의 앞뒤 순서 시험도 무너진다. 구조 시험: 시험 소스에 고정·이동 시계 생성이 없고, 시험 문맥의 시계가 앱 설정의 시계다.
 */
@IntegrationTest
class NoFixedClockTest {

    // 이 파일 자신에 걸리지 않게 문자열을 나눠 쓴다.
    private static final List<String> FORBIDDEN = List.of("Clock" + ".fixed", "Clock" + ".offset");

    @Autowired
    Clock clock;

    @Test
    void 시험_소스에_고정_시계_생성이_없다() throws IOException {
        Path root = Path.of("src/test/java");
        assertThat(root).isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("NoFixedClockTest.java"))
                    .filter(p -> {
                        try {
                            String text = Files.readString(p);
                            return FORBIDDEN.stream().anyMatch(text::contains);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).map(p -> root.relativize(p).toString()).toList();
            assertThat(offenders).as("고정·이동 시계를 쓰는 시험 파일").isEmpty();
        }
    }

    @Test
    void 시험_문맥의_시계는_앱_설정의_흐르는_시계다() throws Exception {
        assertThat(clock.getZone()).isEqualTo(Clock.systemUTC().getZone()).isEqualTo(ZoneOffset.UTC);
        Instant first = clock.instant();
        Thread.sleep(5);
        Instant second = clock.instant();
        assertThat(second).isAfter(first);
    }
}

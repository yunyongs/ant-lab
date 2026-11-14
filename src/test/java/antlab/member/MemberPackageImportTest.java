package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The member package must not know the security framework: the token's member lives in {@code antlab.auth}. */
class MemberPackageImportTest {

    private static final Path MEMBER_SOURCES = Path.of("src/main/java/antlab/member");

    @Test
    void member_패키지는_스프링_시큐리티_core를_가져오지_않는다() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(MEMBER_SOURCES)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("the scan found the member sources").isNotEmpty();
        for (Path source : sources) {
            assertThat(Files.readAllLines(source)).as(source.toString())
                    .noneMatch(line -> line.startsWith("import org.springframework.security.core"));
        }
    }
}

package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The image build gives no error when the feed files are missing (the failure would only show at boot), so pin the inputs here. */
class FeedImageInputsTest {

    @Test
    void the_dockerfile_copies_feed_before_the_jar_is_built_and_dockerignore_lets_it_in() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("Dockerfile")).stream().map(String::strip)
                .filter(l -> !l.startsWith("#")).toList();
        int copy = lines.indexOf("COPY feed feed");
        int bootJar = lines.indexOf("RUN ./gradlew --no-daemon --offline bootJar");
        assertThat(copy).isPositive();
        assertThat(bootJar).isGreaterThan(copy);
        assertThat(Files.readAllLines(Path.of(".dockerignore")).stream().map(String::strip)).contains("!feed/");
    }

    @Test
    void the_build_copies_the_five_csv_files_onto_the_classpath() throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build).contains("tasks.named('processResources')").contains("from('feed')").contains("include '*.csv'")
                .contains("into 'feed'");
    }
}

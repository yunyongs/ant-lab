package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code scripts/image_feed_check.sh} against a fake {@code docker} that serves a directory as the image: pass, a missing file,
 * an extra file, a changed byte, a failing listing, usage. No Docker, no Spring context. The workflow wiring is pinned here too.
 */
class ImageFeedCheckScriptTest {

    private static final Path SCRIPT = Path.of("scripts/image_feed_check.sh");
    private static final Path WORKFLOW = Path.of(".github/workflows/api.yml");
    private static final String IMAGE_DIR = "/app/BOOT-INF/classes/feed";

    private record Run(int exit, String output) {}

    @TempDir
    Path temp;
    private Path root;

    @BeforeEach
    void fakeImage() throws IOException {
        root = Files.createDirectories(temp.resolve("image").resolve(IMAGE_DIR.substring(1)));
        try (Stream<Path> csv = Files.list(Path.of("feed"))) {
            for (Path file : csv.filter(f -> f.getFileName().toString().endsWith(".csv")).toList()) {
                Files.copy(file, root.resolve(file.getFileName()));
            }
        }
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, """
                #!/bin/sh
                # docker run --rm --entrypoint CMD IMAGE ARGS...
                [ "$1" = run ] || exit 9
                cmd=$4
                shift 5
                case "$cmd" in
                  ls) [ -z "$FAKE_LS_FAIL" ] || exit 1; exec ls -1 "$FAKE_IMAGE$2" ;;
                  cat) exec cat "$FAKE_IMAGE$1" ;;
                esac
                exit 9
                """);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run run(boolean listingFails, String... args) throws Exception {
        String[] command = new String[args.length + 2];
        command[0] = "bash";
        command[1] = SCRIPT.toString();
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("PATH", temp.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE_IMAGE", temp.resolve("image").toString());
        if (listingFails) {
            pb.environment().put("FAKE_LS_FAIL", "1");
        }
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    @Test
    void the_script_is_executable_and_usage_is_checked() throws Exception {
        assertThat(Files.isExecutable(SCRIPT)).isTrue();
        assertThat(Files.readAllLines(SCRIPT).get(0)).isEqualTo("#!/usr/bin/env bash");
        assertThat(run(false).exit()).isEqualTo(2);
        assertThat(run(false, "a", "b").exit()).isEqualTo(2);
        Run help = run(false, "--help");
        assertThat(help.exit()).isZero();
        assertThat(help.output()).contains("Usage: scripts/image_feed_check.sh IMAGE");
    }

    @Test
    void an_image_with_the_repository_files_passes() throws Exception {
        Run result = run(false, "antlab-api:fake");
        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains("5 files identical");
    }

    @Test
    void a_missing_file_fails() throws Exception {
        Files.delete(root.resolve("notices.csv"));
        Run result = run(false, "antlab-api:fake");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("files differ");
    }

    @Test
    void an_extra_file_fails() throws Exception {
        Files.writeString(root.resolve("stale.csv"), "x\n");
        Run result = run(false, "antlab-api:fake");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("files differ");
    }

    @Test
    void a_changed_byte_fails() throws Exception {
        Path templates = root.resolve("templates.csv");
        Files.writeString(templates, Files.readString(templates) + " ");
        Run result = run(false, "antlab-api:fake");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("templates.csv differs");
    }

    @Test
    void an_image_without_the_directory_fails() throws Exception {
        Run result = run(true, "antlab-api:fake");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("cannot list");
    }

    @Test
    void the_image_job_runs_the_check_on_the_image_it_built() throws IOException {
        List<String> lines = Files.readAllLines(WORKFLOW).stream().map(String::strip).toList();
        int tag = lines.indexOf("tags: antlab-api:ci");
        int check = lines.indexOf("run: scripts/image_feed_check.sh antlab-api:ci");
        int save = lines.indexOf("run: docker save --output api-image.tar antlab-api:ci");
        assertThat(tag).isPositive();
        assertThat(check).as("check step").isGreaterThan(tag);
        assertThat(save).as("saved only after the check").isGreaterThan(check);
    }
}

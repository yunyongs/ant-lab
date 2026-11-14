package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * No signing key, token, refresh cookie value or private key sits in a tracked text file, and no private path is tracked
 * (SEC-06). The scope is {@code git ls-files --cached --others --exclude-standard}: tracked files plus untracked files that
 * are not ignored, so a new file is guarded before {@code git add} and an ignored local {@code .env} never trips the scan.
 * Build output, the api image (smoke script) and the test-run log ({@code AuthLifecycleE2ETest}) are guarded elsewhere.
 * No Spring context, no Docker.
 */
class SecretHygieneTest {

    /** The one allowed key: the visibly fake, jar-excluded test key. */
    private static final String TEST_KEY_FILE = "src/test/resources/application-test.yml";
    private static final String TEST_KEY_MARK = "# Test-only signing key";

    private static final Pattern COMPACT_JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern KEY_ASSIGNMENT = Pattern.compile(
            "(?i)(ANTLAB_(AUTH_)?JWT_SECRET|jwt[-_]?secret)['\"]?\\s*[:=]\\s*['\"]?[A-Za-z0-9+/_-]{16,}");
    private static final Pattern REFRESH_VALUE = Pattern.compile("antlab_refresh=[A-Za-z0-9_-]{16,}");

    private static final List<Pattern> PRIVATE_PATHS = List.of(
            Pattern.compile("(^|/)\\.env(\\.[^/]+)?$"),
            Pattern.compile("(^|/)\\.envrc$"),
            Pattern.compile("(^|/)secrets/"),
            Pattern.compile("(^|/)application-(local|secret)\\.[^/]+$"),
            Pattern.compile("\\.(pem|key|p12|pfx|jks|keystore|truststore|secret)$"));

    private static final int BINARY_PROBE_BYTES = 8192;

    /** The paths that must never be tracked (mirrors the Secrets and Spring configuration blocks of .gitignore). */
    static List<String> privatePaths(List<String> paths) {
        return paths.stream().filter(p -> !p.endsWith(".example"))
                .filter(p -> PRIVATE_PATHS.stream().anyMatch(rule -> rule.matcher(p).find())).toList();
    }

    /** The rule names that match in one file's text; the allowed test key line is the only exemption. */
    static List<String> findings(String relativePath, String text) {
        List<String> found = new ArrayList<>();
        if (COMPACT_JWT.matcher(text).find()) {
            found.add("compact JWT");
        }
        if (PRIVATE_KEY.matcher(text).find()) {
            found.add("private key block");
        }
        if (REFRESH_VALUE.matcher(text).find()) {
            found.add("refresh cookie value");
        }
        var assignment = KEY_ASSIGNMENT.matcher(text);
        while (assignment.find()) {
            boolean allowed = relativePath.equals(TEST_KEY_FILE) && text.contains(TEST_KEY_MARK);
            if (!allowed) {
                found.add("signing key value");
                break;
            }
        }
        return found;
    }

    /** Tracked plus untracked-not-ignored paths. Fails when git is missing: a skipped scan would guard nothing. */
    private static List<String> listed() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] out = git.getInputStream().readAllBytes();
        assertThat(git.waitFor()).as("git ls-files is required for the secret scan").isZero();
        List<String> paths = new ArrayList<>();
        for (String path : new String(out, StandardCharsets.UTF_8).split("\0")) {
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Text only: a NUL byte in the first 8 KiB marks a binary file. Read as ISO-8859-1, which never throws. */
    private static String textOf(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        for (int i = 0; i < Math.min(bytes.length, BINARY_PROBE_BYTES); i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    void 추적_대상_글_파일에는_토큰_키_쿠키_값이_없다() throws Exception {
        List<String> paths = listed();
        assertThat(paths).as("the scan must see the whole tree").hasSizeGreaterThan(100)
                .contains(TEST_KEY_FILE, "docs/DEPLOY.md", ".github/PULL_REQUEST_TEMPLATE.md")
                .anyMatch(p -> p.startsWith("monitoring/"));
        List<String> problems = new ArrayList<>();
        for (String path : paths) {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file)) {
                continue; // deleted in the work tree but still indexed
            }
            String text = textOf(file);
            if (text == null) {
                continue;
            }
            for (String rule : findings(path, text)) {
                problems.add(path + ": " + rule);
            }
        }
        assertThat(problems).as("a secret-shaped value sits in a tracked file (values are never printed)").isEmpty();
    }

    @Test
    void 개인_설정과_env와_키_파일은_추적하지_않는다() throws Exception {
        assertThat(privatePaths(listed())).as("private files are tracked or about to be").isEmpty();
    }

    @Test
    void 비공개_경로_규칙이_견본을_잡는다() {
        List<String> sample = List.of("secrets/antlab.auth.jwt-secret", ".env", "deploy/.env.prod",
                "src/main/resources/application-local.yml", "a.pem", ".env.example", ".env.prod.example", "docs/DEPLOY.md");
        assertThat(privatePaths(sample)).containsExactly("secrets/antlab.auth.jwt-secret", ".env", "deploy/.env.prod",
                "src/main/resources/application-local.yml", "a.pem");
    }

    @Test
    void 시험용_키는_표시가_붙은_한_곳에만_있다() throws IOException {
        String text = Files.readString(Path.of(TEST_KEY_FILE));
        assertThat(text).contains(TEST_KEY_MARK);
        assertThat(KEY_ASSIGNMENT.matcher(text).results().count()).isEqualTo(1);
        // The key is excluded from the jar: the main resources never carry one.
        assertThat(Files.readString(Path.of("src/main/resources/application.yml"))).contains("jwt-secret: ${ANTLAB_JWT_SECRET:}");
    }

    @Test
    void 점검_규칙_자체가_심어_둔_견본을_잡는다() {
        // Built in pieces so that this file itself never contains a match.
        String jwt = "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0" + "." + "c2lnbmF0dXJlLXNhbXBsZQ";
        String key = "ANTLAB_JWT_SECRET" + "=" + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MTIz";
        String yamlKey = "jwt-secret" + ": " + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MTIz";
        String jsonKey = "\"ANTLAB_JWT_SECRET\"" + ": " + "\"QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MTIz\"";
        String relaxedEnv = "ANTLAB_AUTH_JWT_SECRET" + "=" + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MTIz";
        String relaxedProp = "antlab.auth.jwtSecret" + "=" + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MTIz";
        String urlSafe = "jwt-secret" + ": " + "QUJD-EVGR0hJ_ktMTU5PUFFSU1RVVldYWVo0MTIz";
        String cookie = "antlab_refresh" + "=" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-aaaa";
        String block = "-----BEGIN " + "RSA PRIVATE KEY-----";
        assertThat(findings("docs/x.md", "Authorization: Bearer " + jwt)).containsExactly("compact JWT");
        assertThat(findings("scripts/x.sh", key)).containsExactly("signing key value");
        assertThat(findings("src/main/resources/application.yml", yamlKey)).containsExactly("signing key value");
        assertThat(findings("docs/x.md", jsonKey)).containsExactly("signing key value");
        assertThat(findings("scripts/x.sh", relaxedEnv)).containsExactly("signing key value");
        assertThat(findings("src/x.properties", relaxedProp)).containsExactly("signing key value");
        assertThat(findings("monitoring/x.yml", urlSafe)).containsExactly("signing key value");
        assertThat(findings("docs/x.md", "Cookie: " + cookie)).containsExactly("refresh cookie value");
        assertThat(findings("docs/x.md", block)).containsExactly("private key block");
        // The allowed place stays allowed only with its mark, and only in its own file.
        assertThat(findings(TEST_KEY_FILE, TEST_KEY_MARK + "\n" + yamlKey)).isEmpty();
        assertThat(findings(TEST_KEY_FILE, yamlKey)).containsExactly("signing key value");
        assertThat(findings("docs/x.md", TEST_KEY_MARK + "\n" + yamlKey)).containsExactly("signing key value");
        // Placeholders and empty values are fine.
        assertThat(findings("compose.yaml", "ANTLAB_JWT_SECRET: ${ANTLAB_JWT_SECRET:-}")).isEmpty();
        assertThat(findings(".env.example", "ANTLAB_JWT_SECRET=\n")).isEmpty();
    }

    @Test
    void env_예시는_비밀값을_비워_둔다() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(".env.example"));
        for (String name : List.of("ANTLAB_JWT_SECRET", "GF_SECURITY_ADMIN_PASSWORD")) {
            assertThat(lines).as(name).contains(name + "=");
            assertThat(lines).filteredOn(l -> l.startsWith(name + "=")).containsOnly(name + "=");
        }
    }

    @Test
    void dockerignore는_비밀_경로를_계속_뺀다() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(".dockerignore")).stream().map(String::strip).toList();
        assertThat(lines).contains("**/.env", "**/secrets/", "**/application-local.*");
    }
}

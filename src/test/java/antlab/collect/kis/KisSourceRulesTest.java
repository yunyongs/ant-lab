package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Structure checks over the collector sources: the request surface stays three KIS entries, one FRED entry and one ECOS host, and no order/account text exists. */
class KisSourceRulesTest {

    private static final Path COLLECT = Path.of("src/main/java/antlab/collect");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern FORBIDDEN_WORDS = Pattern.compile("(?i)order|hashkey|balance|trading|TTTC|VTTC|JTTT|revoke|account");
    private static final Pattern PATH_LIKE = Pattern.compile("/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]*)*");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(COLLECT)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> literals(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING_LITERAL.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void 수집_소스의_문자열에는_주문_계좌_해시키_거래_ID가_없다() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            for (String literal : literals(Files.readString(file))) {
                if (FORBIDDEN_WORDS.matcher(literal).find()) {
                    offenders.add(file.getFileName() + ": " + literal);
                }
            }
        }
        assertThat(javaFiles()).isNotEmpty();
        assertThat(offenders).isEmpty();
    }

    @Test
    void 경로_문자열은_KisEndpoint의_세_개와_FredEndpoint의_하나와_EcosEndpoint의_호스트뿐이다() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : javaFiles()) {
            for (String literal : literals(Files.readString(file))) {
                if (PATH_LIKE.matcher(literal).matches() || literal.contains("://") || literal.contains("/uapi") || literal.contains("/oauth2")) {
                    found.add(file.getFileName() + ": " + literal);
                }
            }
        }
        assertThat(found).containsExactlyInAnyOrder(
                "KisEndpoint.java: /oauth2/tokenP",
                "KisEndpoint.java: /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
                "KisEndpoint.java: /uapi/overseas-price/v1/quotations/dailyprice",
                "KisEndpoint.java: https://openapi.koreainvestment.com:9443",
                "FredEndpoint.java: https://api.stlouisfed.org",
                "FredEndpoint.java: /fred/series/observations",
                "EcosEndpoint.java: https://ecos.bok.or.kr");
    }

    @Test
    void 수집_소스는_RestClient를_허용_목록_뒤에서만_만든다() throws IOException {
        for (Path file : javaFiles()) {
            String text = Files.readString(file);
            if (text.contains("RestClient.builder()") || text.contains("RestTemplate") || text.contains("WebClient")
                    || text.contains("HttpURLConnection") || text.contains("new URL(")) {
                assertThat(file.getFileName().toString()).as("only the transport builder may create a client")
                        .isEqualTo("KisClientConfig.java");
            }
        }
    }

    @Test
    void 프레임워크_HTTP_로그_수준을_올리지_않는다() throws IOException {
        List<Path> yamls = new ArrayList<>();
        for (String root : List.of("src/main/resources", "src/test/resources")) {
            try (Stream<Path> walk = Files.walk(Path.of(root))) {
                walk.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".properties")).forEach(yamls::add);
            }
        }
        assertThat(yamls).isNotEmpty();
        for (Path yaml : yamls) {
            assertThat(Files.readString(yaml)).as(yaml.toString())
                    .doesNotContain("org.springframework.web.client").doesNotContain("jdk.internal.httpclient")
                    .doesNotContain("jdk.httpclient").doesNotContain("org.springframework.http.client");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 설정_파일은_꺼진_상태와_환경_변수_자리표시자만_가진다() throws IOException {
        Map<String, Object> root = (Map<String, Object>) new Yaml()
                .loadAll(Files.readString(Path.of("src/main/resources/application.yml"))).iterator().next();
        Map<String, Object> kis = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) root.get("antlab")).get("collect")).get("kis");

        assertThat(kis).containsEntry("enabled", false).containsEntry("app-key", "${KIS_APP_KEY:}")
                .containsEntry("app-secret", "${KIS_APP_SECRET:}").containsEntry("token-cache-dir", "${KIS_TOKEN_CACHE_DIR:}")
                .containsEntry("rps", 2).containsEntry("max-requests-per-run", 60);
    }

    @Test
    void 픽스처는_가짜_표시가_있고_실제_토큰이나_키_모양이_없다() throws IOException {
        Pattern realLooking = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}|PS[A-Za-z0-9]{30,}|[A-Za-z0-9+/]{40,}");
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/fixtures/kis"))) {
            List<Path> fixtures = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(fixtures).hasSizeGreaterThanOrEqualTo(10);
            for (Path file : fixtures) {
                String text = Files.readString(file);
                assertThat(text).as(file.toString()).contains("\"_fixture\"").contains("FAKE");
                assertThat(realLooking.matcher(text).find()).as(file + " has no real-looking secret").isFalse();
                Matcher token = Pattern.compile("\"access_token\":\\s*\"([^\"]*)\"").matcher(text);
                while (token.find()) {
                    assertThat(token.group(1)).startsWith("FAKE-");
                }
            }
        }
    }

    @Test
    void 픽스처_이름은_gitignore_규칙에_걸리지_않아_새_체크아웃에도_있다() throws IOException {
        List<PathMatcher> ignored = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(".gitignore"))) {
            String rule = line.strip();
            if (rule.isEmpty() || rule.startsWith("#") || rule.startsWith("!") || rule.contains("/")) {
                continue;
            }
            ignored.add(FileSystems.getDefault().getPathMatcher("glob:" + rule));
        }
        assertThat(ignored).isNotEmpty();
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/fixtures/kis"))) {
            List<String> hidden = files.map(Path::getFileName)
                    .filter(name -> ignored.stream().anyMatch(rule -> rule.matches(name)))
                    .map(Path::toString).toList();
            assertThat(hidden).as("fixtures matched by a .gitignore name rule would be missing from a clean checkout").isEmpty();
        }
    }

    @Test
    void 시험이_읽는_픽스처_이름은_모두_파일로_있다() throws IOException {
        Pattern call = Pattern.compile("(?:fixture|respondDomestic|respondOverseas)\\(\"([a-z0-9_]+)\"");
        Path testDir = Path.of("src/test/java/antlab/collect/kis");
        try (Stream<Path> walk = Files.walk(testDir)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = call.matcher(Files.readString(file));
                while (m.find()) {
                    assertThat(Path.of("src/test/resources/fixtures/kis", m.group(1) + ".json")).as(file.getFileName() + " uses " + m.group(1)).exists();
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 웹과_HTTP_클라이언트_로거는_어느_프로필에서도_디버그가_아니다() throws IOException {
        Pattern wire = Pattern.compile("(?i)^(web|org\\.springframework\\.web.*|jdk\\.internal\\.net\\.http.*|jdk\\.internal\\.httpclient.*|org\\.springframework\\.http.*)$");
        for (String resource : new String[] {"src/main/resources/application.yml", "src/test/resources/application-test.yml"}) {
            for (Object doc : new Yaml().loadAll(Files.readString(Path.of(resource)))) {
                Object logging = ((Map<String, Object>) doc).get("logging");
                if (logging == null) {
                    continue;
                }
                Object levels = ((Map<String, Object>) logging).get("level");
                if (levels == null) {
                    continue;
                }
                for (Map.Entry<String, Object> level : ((Map<String, Object>) levels).entrySet()) {
                    if (wire.matcher(level.getKey()).matches()) {
                        assertThat(String.valueOf(level.getValue()).toLowerCase()).as(resource + " " + level.getKey()).isNotIn("debug", "trace");
                    }
                }
            }
        }
    }
}

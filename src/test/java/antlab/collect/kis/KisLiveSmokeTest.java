package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.PriceBasis;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-off live smoke of the KIS quote client: at most 3 requests, each endpoint once, no retry of any kind. The live test
 * runs only with ANTLAB_KIS_LIVE_SMOKE=1 and keys in the environment; it writes no database rows.
 */
class KisLiveSmokeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Outermost interceptor: each method+path once, 3 requests in total, nothing after a non-200 answer. Records structure only. */
    static final class Budget implements ClientHttpRequestInterceptor {
        final List<String> log = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private boolean tripped;

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            String key = request.getMethod() + " " + request.getURI().getPath();
            if (tripped || seen.size() >= 3 || !seen.add(key)) {
                throw new IllegalStateException("smoke budget: refusing a further request to " + key);
            }
            ClientHttpResponse response = execution.execute(request, body);
            byte[] bytes = response.getBody().readAllBytes();
            int status = response.getStatusCode().value();
            if (status != 200) {
                tripped = true;
            }
            log.add(key + " -> HTTP " + status + " " + shape(bytes, key.endsWith("/oauth2/tokenP")));
            return new Buffered(response, bytes);
        }

        int count() {
            return seen.size();
        }
    }

    /** Keys, codes and row counts only; the token response contributes key names, never values. */
    static String shape(byte[] bytes, boolean token) {
        try {
            JsonNode root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("keys=");
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = root.propertyNames().iterator(); it.hasNext();) {
                names.add(it.next());
            }
            out.append(names);
            if (token) {
                return out.toString();
            }
            out.append(" rt_cd=").append(root.path("rt_cd").asString("")).append(" msg_cd=").append(root.path("msg_cd").asString(""))
                    .append(" msg1=").append(root.path("msg1").asString(""));
            for (String name : names) {
                JsonNode node = root.path(name);
                if (node.isArray()) {
                    List<String> rowKeys = new ArrayList<>();
                    if (node.size() > 0) {
                        for (Iterator<String> it = node.get(0).propertyNames().iterator(); it.hasNext();) {
                            rowKeys.add(it.next());
                        }
                    }
                    out.append(' ').append(name).append("[rows=").append(node.size()).append(", rowKeys=").append(rowKeys).append(']');
                } else if (node.isObject()) {
                    List<String> inner = new ArrayList<>();
                    for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext();) {
                        inner.add(it.next());
                    }
                    out.append(' ').append(name).append("{keys=").append(inner).append('}');
                }
            }
            return out.toString();
        } catch (RuntimeException e) {
            return "unparsable body (" + bytes.length + " bytes)";
        }
    }

    private record Buffered(ClientHttpResponse delegate, byte[] bytes) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }

    @Test
    void 가짜_서버에서_요청은_정확히_3번이고_4번째는_나가지_않는다() {
        var budget = new Budget();
        var builder = RestClient.builder().requestInterceptor(budget);
        var h = new KisHarness(builder);
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p1");
        h.respondOverseas("us_daily_p1");
        KisQuoteClient client = h.factory.newClient();
        LocalDate from = LocalDate.of(2026, 1, 7);
        LocalDate to = LocalDate.of(2026, 1, 9);

        client.domesticDaily("900001", from, to, PriceBasis.ADJUSTED);
        client.overseasDaily("FAKEA", KisExchange.NAS, from, to);

        assertThat(client.requestsMade()).isEqualTo(3);
        assertThat(budget.count()).isEqualTo(3);
        assertThatThrownBy(() -> client.overseasDaily("FAKEA", KisExchange.NAS, from, to)).isInstanceOf(RuntimeException.class);
        assertThat(budget.count()).isEqualTo(3);
        h.server.verify();
    }

    @Test
    void 가짜_서버에서_토큰_캐시가_있으면_요청은_2번이다(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        long expiresAt = KisHarness.START.getEpochSecond() + 86_400;
        Files.writeString(dir.resolve(FileKisTokenStore.FILE_NAME),
                "{\"access_token\":\"" + KisHarness.FAKE_TOKEN + "\",\"expires_at\":" + expiresAt + "}");
        var budget = new Budget();
        var h = new KisHarness(KisHarness.properties(dir.toString(), 2, 3), RestClient.builder().requestInterceptor(budget));
        h.respondDomestic("kr_daily_p1");
        h.respondOverseas("us_daily_p1");
        KisQuoteClient client = h.factory.newClient();
        LocalDate from = LocalDate.of(2026, 1, 7);
        LocalDate to = LocalDate.of(2026, 1, 9);

        client.domesticDaily("900001", from, to, PriceBasis.ADJUSTED);
        client.overseasDaily("FAKEA", KisExchange.NAS, from, to);

        assertThat(budget.count()).isEqualTo(2);
        h.server.verify();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTLAB_KIS_LIVE_SMOKE", matches = "1")
    void 실제_KIS_스모크() throws IOException {
        String key = System.getenv("KIS_APP_KEY");
        String secret = System.getenv("KIS_APP_SECRET");
        String cacheDir = System.getenv("KIS_TOKEN_CACHE_DIR") == null ? "" : System.getenv("KIS_TOKEN_CACHE_DIR");
        var budget = new Budget();
        var props = new KisProperties(true, key, secret, cacheDir, 1, 3);
        var factory = new KisQuoteClientFactory(KisClientConfig.transportBuilder().requestInterceptor(budget),
                KisEndpoint.BASE_URI, props, Clock.systemUTC(), Sleeper.SYSTEM);
        KisQuoteClient client = factory.newClient();
        LocalDate to = LocalDate.now(Clock.systemUTC());
        LocalDate from = to.minusDays(10);
        List<String> out = new ArrayList<>();
        try {
            List<DailyClose> kr = client.domesticDaily("069500", from, to, PriceBasis.ADJUSTED);
            out.add("069500 rows=" + kr.size() + (kr.isEmpty() ? "" : " latest=" + kr.getLast()));
            List<DailyClose> us = client.overseasDaily("SPY", KisExchange.AMS, from, to);
            out.add("SPY rows=" + us.size() + (us.isEmpty() ? "" : " latest=" + us.getLast()));
        } catch (RuntimeException e) {
            out.add("FAILED " + e.getClass().getSimpleName() + ": " + new SecretMasker().mask(String.valueOf(e.getMessage())));
        }
        out.add("requestsMade=" + client.requestsMade() + " budgetCount=" + budget.count());
        out.addAll(budget.log);
        Path file = Path.of("build/kis-smoke/result.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, out);
    }
}

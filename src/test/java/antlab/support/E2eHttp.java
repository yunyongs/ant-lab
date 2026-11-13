package antlab.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-HTTP client for end-to-end tests: a {@link RestClient} on the random port with connect (5 s) and read (60 s) timeouts. It never throws on 4xx/5xx and keeps
 * every answer, so a test can scan all bodies of a run afterwards (for example for a leaked seed).
 */
public final class E2eHttp {

    /** One answer. {@code body} is the raw text (may be empty), {@code contentType} the raw header (may be null). */
    public record Reply(String method, String path, int status, String contentType, String body) {

        public JsonNode json() {
            return JsonMapper.shared().readTree(body);
        }

        public boolean isProblem() {
            return contentType != null && contentType.startsWith("application/problem+json");
        }

        public String code() {
            return json().get("code").asString();
        }

        @Override
        public String toString() {
            return method + " " + path + " -> " + status;
        }
    }

    private final RestClient client;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<Reply> captured = Collections.synchronizedList(new ArrayList<>());

    public E2eHttp(int port) {
        this(port, Map.of());
    }

    /** Headers sent with every call; a header given for one call replaces the default of the same name. */
    public E2eHttp(int port, Map<String, String> defaultHeaders) {
        this.defaultHeaders.putAll(defaultHeaders);
        this.baseUrl = "http://127.0.0.1:" + port;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Reply get(String path) {
        return send(HttpMethod.GET, path, null, null, MediaType.APPLICATION_JSON_VALUE);
    }

    public Reply post(String path, String jsonBody) {
        return send(HttpMethod.POST, path, jsonBody, MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE);
    }

    /** POST with extra request headers (for example the dev gate header). */
    public Reply post(String path, String jsonBody, Map<String, String> headers) {
        return send(HttpMethod.POST, path, jsonBody, MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE, headers);
    }

    /** Full control for error probes: any method, content type and Accept header (null leaves the header off). */
    public Reply send(HttpMethod method, String path, String body, String contentType, String accept) {
        return send(method, path, body, contentType, accept, Map.of());
    }

    public Reply send(HttpMethod method, String path, String body, String contentType, String accept,
            Map<String, String> headers) {
        RestClient.RequestBodySpec spec = client.method(method).uri(URI.create(baseUrl + path));
        Map<String, String> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        all.putAll(defaultHeaders);
        all.putAll(headers);
        all.forEach(spec::header);
        if (accept != null) {
            spec.header("Accept", accept);
        }
        if (contentType != null) {
            spec.header("Content-Type", contentType);
        }
        if (body != null) {
            spec.body(body);
        }
        Reply reply = spec.exchange((request, response) -> {
            String text = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            MediaType type = response.getHeaders().getContentType();
            return new Reply(method.name(), path, response.getStatusCode().value(), type == null ? null : type.toString(), text);
        });
        captured.add(reply);
        return reply;
    }

    /** Every answer seen so far, in order. */
    public List<Reply> captured() {
        synchronized (captured) {
            return List.copyOf(captured);
        }
    }
}

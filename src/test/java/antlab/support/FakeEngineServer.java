package antlab.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** A loopback stand-in for the Python engine: serves canned bodies, records what it was sent, can delay or redirect. */
public final class FakeEngineServer implements AutoCloseable {

    public static final Path CONTRACT_DIR = Path.of("src/test/resources/engine/contract");

    public record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

        /** Header values by name, ignoring case (the JDK server normalises names). */
        public List<String> header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).toList();
        }
    }

    private record Canned(int status, String contentType, String body, String location) {
    }

    private final HttpServer server;
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Canned>> queued = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Duration delay = Duration.ZERO;
    private volatile Duration bodyStall = Duration.ZERO;

    private FakeEngineServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::handle);
    }

    public static FakeEngineServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeEngineServer fake = new FakeEngineServer(http);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String fixture(String name) {
        try {
            return Files.readString(CONTRACT_DIR.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeEngineServer respond(String path, int status, String contentType, String body) {
        routes.put(path, new Canned(status, contentType, body, null));
        return this;
    }

    /** 200 answers are application/json, everything else application/problem+json. */
    public FakeEngineServer respondFixture(String path, int status, String fixtureName) {
        return respond(path, status, status == 200 ? "application/json" : "application/problem+json", fixture(fixtureName));
    }

    /** An answer used once, before the route's own answer: queue two to script "422 first, then 200". */
    public FakeEngineServer queue(String path, int status, String contentType, String body) {
        queued.computeIfAbsent(path, key -> new ConcurrentLinkedQueue<>()).add(new Canned(status, contentType, body, null));
        return this;
    }

    public FakeEngineServer redirect(String path, String location) {
        routes.put(path, new Canned(302, "text/plain", "", location));
        return this;
    }

    /** Every answer waits this long first (a slow engine). */
    public FakeEngineServer delay(Duration delay) {
        this.delay = delay;
        return this;
    }

    /** The answer's headers go out at once, the body only after this long (an engine that stalls after the headers). */
    public FakeEngineServer stallBody(Duration stall) {
        this.bodyStall = stall;
        return this;
    }

    /** Forget the recorded requests, the canned answers and the delay (for a server shared by the tests of a class). */
    public FakeEngineServer reset() {
        routes.clear();
        queued.clear();
        requests.clear();
        delay = Duration.ZERO;
        bodyStall = Duration.ZERO;
        return this;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public int hits(String path) {
        return (int) requests.stream().filter(r -> r.path().equals(path)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        // the client's first-call replay-start check asks /v1/info; it is not a recorded request unless a test scripted it
        boolean implicitInfo = "/v1/info".equals(path) && !routes.containsKey(path) && !queued.containsKey(path);
        if (!implicitInfo) {
            requests.add(new Recorded(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
        }
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ConcurrentLinkedQueue<Canned> scripted = queued.get(path);
        Canned next = scripted == null ? null : scripted.poll();
        Canned fallback = implicitInfo
                ? new Canned(200, "application/json", fixture("info-response.json"), null)
                : new Canned(404, "text/plain", "", null);
        Canned canned = next != null ? next : routes.getOrDefault(path, fallback);
        byte[] bytes = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", canned.contentType());
        if (canned.location() != null) {
            exchange.getResponseHeaders().add("Location", canned.location());
        }
        try {
            exchange.sendResponseHeaders(canned.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0 && !bodyStall.isZero()) {
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(bodyStall);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // the client gave up (timeout test); nothing to answer
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

package antlab.engine.client;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The one way Spring talks to the Python engine. It sends the service token, applies a timeout per operation, never
 * follows a redirect or uses a proxy (see {@link EngineClientConfig}), parses answers strictly and reports every failure
 * as one Java outcome ({@link EngineErrorMapper}). It never retries: requests are deterministic, and a retry would
 * make the worst-case wait twice as long for a caller that may hold a lock.
 */
public final class EngineClient {

    static final String TOKEN_HEADER = "X-Antlab-Engine-Token";
    static final String PATHS_URI = "/v1/paths";
    static final String PROJECTIONS_URI = "/v1/projections";
    static final String PERCENTILES_URI = "/v1/percentiles";
    static final String REGIME_URI = "/v1/regime/evaluate";
    static final String MANAGER_PARTS_URI = "/v1/manager-parts";
    static final String INFO_URI = "/v1/info";
    /** A projection answer is about 15-100 KB and a regime answer about 0.7 KB per month; anything beyond this is not an engine answer. */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");
    private static final List<MediaType> ACCEPT = List.of(MediaType.APPLICATION_JSON, PROBLEM_JSON);

    private record Raw(int status, MediaType contentType, byte[] body, boolean tooLarge) {
    }

    private final RestClient paths;
    private final RestClient projections;
    private final RestClient regime;
    private final LocalDate expectedReplayStart;

    EngineClient(EngineProperties properties, RestClient.Builder pathsBuilder, RestClient.Builder projectionsBuilder,
            RestClient.Builder regimeBuilder) {
        this(properties, pathsBuilder, projectionsBuilder, regimeBuilder, null);
    }

    /**
     * @param expectedReplayStart the api's {@code antlab.history.replay-start}; when set, every path, projection,
     *        percentile or manager-parts call first asks {@code /v1/info} and fails if the engine reports another start. Nothing is cached, so an
     *        engine recreated with another start is caught on the next call; there is no check at startup, so an engine that
     *        is down does not block the api
     */
    EngineClient(EngineProperties properties, RestClient.Builder pathsBuilder, RestClient.Builder projectionsBuilder,
            RestClient.Builder regimeBuilder, LocalDate expectedReplayStart) {
        this.expectedReplayStart = expectedReplayStart;
        if (properties.configured()) {
            this.paths = build(pathsBuilder, properties);
            this.projections = build(projectionsBuilder, properties);
            this.regime = build(regimeBuilder, properties);
        } else {
            this.paths = null;
            this.projections = null;
            this.regime = null;
        }
    }

    private static RestClient build(RestClient.Builder builder, EngineProperties properties) {
        String token = properties.token();
        ClientHttpRequestInterceptor tokenHeader = (request, body, execution) -> {
            request.getHeaders().set(TOKEN_HEADER, token);
            return execution.execute(request, body);
        };
        return builder.baseUrl(properties.url()).requestInterceptor(tokenHeader).build();
    }

    public EnginePathResult createPath(EnginePathRequest request) {
        verifyReplayStart("createPath");
        return call("createPath", paths, true, PATHS_URI, EngineWire.write(request), EnginePathResult.class);
    }

    public EngineProjectionResult createProjection(EngineProjectionRequest request) {
        verifyReplayStart("createProjection");
        return call("createProjection", projections, true, PROJECTIONS_URI, EngineWire.write(request),
                EngineProjectionResult.class);
    }

    /** Same timeout family as projections: it runs the same path simulation. */
    public EnginePercentileResult createPercentile(EnginePercentileRequest request) {
        verifyReplayStart("createPercentile");
        return call("createPercentile", projections, true, PERCENTILES_URI, EngineWire.write(request),
                EnginePercentileResult.class);
    }

    public EngineRegimeResult evaluateRegime(EngineRegimeRequest request) {
        return call("evaluateRegime", regime, true, REGIME_URI, EngineWire.write(request), EngineRegimeResult.class);
    }

    /**
     * The return, risk-adjusted and risk parts of one finished season. Same timeout family as the regime call (60 s by
     * default): a cold reference build is a few seconds of engine CPU plus one history read. A deterministic refusal is an
     * {@link EngineRefusedException}; an outage is an {@link EngineUnavailableException}.
     */
    public EngineManagerPartsResult createManagerParts(EngineManagerPartsRequest request) {
        verifyReplayStart("createManagerParts");
        return call("createManagerParts", regime, true, MANAGER_PARTS_URI, EngineWire.write(request),
                EngineManagerPartsResult.class);
    }

    public EngineInfo info() {
        return call("getEngineInfo", paths, false, INFO_URI, null, EngineInfo.class);
    }

    /** Seasons and projections must draw from the same range as the engine; a mismatch is a deployment fault. */
    private void verifyReplayStart(String operation) {
        if (expectedReplayStart == null || paths == null) {
            return;
        }
        LocalDate reported = info().replayStart();
        if (!expectedReplayStart.equals(reported)) {
            throw EngineErrorMapper.replayStartMismatch(operation, expectedReplayStart, reported);
        }
    }

    private <T> T call(String operation, RestClient client, boolean post, String uri, byte[] body, Class<T> type) {
        if (client == null) {
            throw EngineErrorMapper.notConfigured(operation);
        }
        Raw raw;
        try {
            raw = post
                    ? client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).accept(ACCEPT.toArray(MediaType[]::new))
                            .body(body).exchange((req, res) -> read(res.getStatusCode().value(), res.getHeaders().getContentType(),
                                    res.getBody()), true)
                    : client.get().uri(uri).accept(ACCEPT.toArray(MediaType[]::new))
                            .exchange((req, res) -> read(res.getStatusCode().value(), res.getHeaders().getContentType(),
                                    res.getBody()), true);
        } catch (ResourceAccessException e) {
            throw EngineErrorMapper.transport(operation, e);
        } catch (RestClientException e) {
            throw EngineErrorMapper.badResponse(operation, 0);
        } catch (RuntimeException e) {
            // never let a transport message (URL, header value) reach a log or a response
            throw EngineErrorMapper.badResponse(operation, 0);
        }
        return interpret(operation, raw, type);
    }

    private static Raw read(int status, MediaType contentType, InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
        return new Raw(status, contentType, bytes, bytes.length > MAX_BODY_BYTES);
    }

    private static <T> T interpret(String operation, Raw raw, Class<T> type) {
        if (raw.tooLarge()) {
            throw EngineErrorMapper.badResponse(operation, raw.status());
        }
        try {
            if (raw.status() == 200) {
                if (!isJson(raw.contentType())) {
                    throw EngineErrorMapper.badResponse(operation, raw.status());
                }
                return EngineWire.read(type, raw.body());
            }
            if (!isProblem(raw.contentType())) {
                throw EngineErrorMapper.badResponse(operation, raw.status());
            }
            throw EngineErrorMapper.fromProblem(operation, raw.status(), EngineWire.read(EngineProblem.class, raw.body()));
        } catch (tools.jackson.core.JacksonException e) {
            throw EngineErrorMapper.badResponse(operation, raw.status());
        }
    }

    private static boolean isJson(MediaType type) {
        return type != null && MediaType.APPLICATION_JSON.isCompatibleWith(type) && !isProblem(type);
    }

    private static boolean isProblem(MediaType type) {
        return type != null && PROBLEM_JSON.isCompatibleWith(type);
    }
}

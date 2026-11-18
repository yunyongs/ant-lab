package antlab.engine.client;

import antlab.support.FakeEngineServer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** An engine client wired to mock servers (no network). Only visibly fake values. */
final class EngineHarness {

    static final String FAKE_TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    static final String BASE = "http://engine.test";
    static final String MARKER = "MARKER-ENGINE-DETAIL-7731";
    static final LocalDate BASE_DATE = LocalDate.of(2026, 1, 2);

    final MockRestServiceServer paths;
    final MockRestServiceServer projections;
    final MockRestServiceServer regime;
    final EngineClient client;

    EngineHarness() {
        RestClient.Builder pathsBuilder = RestClient.builder();
        RestClient.Builder projectionsBuilder = RestClient.builder();
        RestClient.Builder regimeBuilder = RestClient.builder();
        this.paths = MockRestServiceServer.bindTo(pathsBuilder).build();
        this.projections = MockRestServiceServer.bindTo(projectionsBuilder).build();
        this.regime = MockRestServiceServer.bindTo(regimeBuilder).build();
        this.client = new EngineClient(properties(BASE, FAKE_TOKEN), pathsBuilder, projectionsBuilder, regimeBuilder);
    }

    static EngineProperties properties(String url, String token) {
        return new EngineProperties(url, token, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(60),
                EngineProperties.PathSourceMode.JAVA, "m2-sb6-v1", 2000);
    }

    static EnginePathRequest pathRequest() {
        return EnginePathRequest.of("m2-sb6-v1", 14, 12, BASE_DATE);
    }

    static EngineProjectionRequest projectionRequest() {
        return new EngineProjectionRequest("14", BASE_DATE, 60, 100, "-0.20000000", List.of(12, 60), List.of(
                new EngineProjectionRequest.Portfolio("TEAM", "0.266666666667", List.of(
                        new EngineProjectionRequest.Holding("KR:A001", "0.333333333333"),
                        new EngineProjectionRequest.Holding("US:C003", "0.400000000000"))),
                new EngineProjectionRequest.Portfolio("CASH", "1", List.of())));
    }

    static EngineRegimeRequest regimeRequest() {
        return new EngineRegimeRequest(EngineRegimeRequest.LABEL_VERSION, List.of("1997-01", "1997-02"));
    }

    /** A fixture with its engine {@code detail} replaced by a marker that must never come back out. */
    static String problemWithMarker(String fixtureName) {
        JsonNode node = EngineWire.MAPPER.readTree(FakeEngineServer.fixture(fixtureName));
        ((tools.jackson.databind.node.ObjectNode) node).put("detail", MARKER);
        return EngineWire.MAPPER.writeValueAsString(node);
    }
}

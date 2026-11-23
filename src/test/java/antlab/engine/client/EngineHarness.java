package antlab.engine.client;

import antlab.member.MemberKind;
import antlab.support.FakeEngineServer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
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

    /** The request of the shared fixture manager-parts-request.json: a 12-turn human season of 2009. */
    static EngineManagerPartsRequest managerPartsRequest() {
        List<LocalDate> months = IntStream.rangeClosed(1, 12).mapToObj(m -> LocalDate.of(2009, m, 1)).toList();
        List<String> totals = List.of("101000000.0000", "100500000.0000", "102000000.0000", "99000000.0000", "98500000.0000",
                "100000000.0000", "103000000.0000", "104500000.0000", "104000000.0000", "106000000.0000", "105000000.0000",
                "108000000.0000");
        List<String> returns = List.of("0.01", "-0.004950495049504950495049504950", "0.014925373134328358208955223881",
                "-0.029411764705882352941176470588", "-0.005050505050505050505050505051", "0.015228426395939086294416243655",
                "0.03", "0.014563106796116504854368932039", "-0.004784688995215311004784688995",
                "0.019230769230769230769230769231", "-0.009433962264150943396226415094", "0.028571428571428571428571428571");
        List<String> bench = List.of("0.005", "0.002", "-0.001", "0.004", "0.003", "-0.002", "0.006", "0.001", "0", "0.002",
                "-0.003", "0.004");
        return new EngineManagerPartsRequest(EngineManagerPartsRequest.REFERENCE_VERSION, BASE_DATE, months,
                new EngineManagerPartsRequest.Settings(12, "100000000.0000", "0.00015000", "0.00250000", "0.00400000",
                        "-0.20000000", new EngineManagerPartsRequest.StartFormation(10, 30, 30, 30), MemberKind.HUMAN),
                new EngineManagerPartsRequest.Team(totals, returns, bench, "0.31250000", "0.25000000", "87.50"));
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

package antlab.engine.client;

import static antlab.engine.client.EngineHarness.BASE;
import static antlab.engine.client.EngineHarness.FAKE_TOKEN;
import static antlab.support.FakeEngineServer.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The api compares its replay start with the engine's {@code /v1/info} before the first path, projection or percentile call. */
class EngineReplayStartCheckTest {

    private static final LocalDate DEFAULT = LocalDate.of(2007, 1, 1);
    private static final LocalDate OTHER = LocalDate.of(1997, 1, 1);

    private record Wired(EngineClient client, MockRestServiceServer paths, MockRestServiceServer projections) {
    }

    private static Wired wired(LocalDate expected) {
        RestClient.Builder paths = RestClient.builder();
        RestClient.Builder projections = RestClient.builder();
        RestClient.Builder regime = RestClient.builder();
        MockRestServiceServer pathsServer = MockRestServiceServer.bindTo(paths).build();
        MockRestServiceServer projectionsServer = MockRestServiceServer.bindTo(projections).build();
        MockRestServiceServer.bindTo(regime).build();
        EngineClient client = new EngineClient(EngineHarness.properties(BASE, FAKE_TOKEN), paths, projections, regime, expected);
        return new Wired(client, pathsServer, projectionsServer);
    }

    private static String info(String start) {
        return "{\"service\":\"antlab-engine\",\"apiVersion\":\"v1\",\"version\":\"0.1.0\",\"replayStart\":\"" + start + "\"}";
    }

    @Test
    void a_matching_start_is_checked_before_every_call() {
        Wired w = wired(DEFAULT);
        w.paths.expect(twice(), requestTo(BASE + "/v1/info")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(info("2007-01-01"), MediaType.APPLICATION_JSON));
        w.paths.expect(twice(), requestTo(BASE + "/v1/paths")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("paths-response-m2.json"), MediaType.APPLICATION_JSON));

        w.client.createPath(EngineHarness.pathRequest());
        w.client.createPath(EngineHarness.pathRequest());

        w.paths.verify();
    }

    @Test
    void an_engine_recreated_with_another_start_is_caught_on_the_next_call() {
        Wired w = wired(DEFAULT);
        w.paths.expect(once(), requestTo(BASE + "/v1/info"))
                .andRespond(withSuccess(info("2007-01-01"), MediaType.APPLICATION_JSON));
        w.paths.expect(once(), requestTo(BASE + "/v1/paths")).andRespond(withSuccess(fixture("paths-response-m2.json"), MediaType.APPLICATION_JSON));
        w.paths.expect(once(), requestTo(BASE + "/v1/info"))
                .andRespond(withSuccess(info("1997-01-01"), MediaType.APPLICATION_JSON));

        w.client.createPath(EngineHarness.pathRequest());

        assertThatThrownBy(() -> w.client.createPath(EngineHarness.pathRequest())).isInstanceOf(DomainException.class);
        w.paths.verify();
    }

    @Test
    void a_different_start_refuses_the_path_call_without_sending_it() {
        Wired w = wired(OTHER);
        w.paths.expect(once(), requestTo(BASE + "/v1/info"))
                .andRespond(withSuccess(info("2007-01-01"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> w.client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        w.paths.verify();
    }

    @Test
    void a_different_start_refuses_projections_and_is_checked_again_on_the_next_call() {
        Wired w = wired(OTHER);
        w.paths.expect(twice(), requestTo(BASE + "/v1/info"))
                .andRespond(withSuccess(info("2007-01-01"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> w.client.createProjection(EngineHarness.projectionRequest())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> w.client.createProjection(EngineHarness.projectionRequest())).isInstanceOf(DomainException.class);

        w.paths.verify();
        w.projections.verify();
    }
}

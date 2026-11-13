package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.E2eHttp.Reply;
import antlab.support.RealPortTest;
import org.junit.jupiter.api.Test;

/** Real ports: MockMvc cannot reach the management port, so Actuator paths do not exist there. */
class ActuatorProbesTest extends RealPortTest {

    @Test
    void API_포트의_livez와_readyz는_200_UP() {
        for (String path : new String[] {"/livez", "/readyz"}) {
            Reply reply = api().get(path);
            assertThat(reply.status()).as(path).isEqualTo(200);
            assertThat(reply.json().get("status").asString()).as(path).isEqualTo("UP");
        }
    }

    @Test
    void 관리_포트의_liveness와_readiness는_200_UP() {
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            Reply reply = management().get(path);
            assertThat(reply.status()).as(path).isEqualTo(200);
            assertThat(reply.json().get("status").asString()).as(path).isEqualTo("UP");
        }
    }

    @Test
    void 관리_포트의_health는_세부_구성요소를_드러내지_않는다() {
        Reply reply = management().get("/actuator/health");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().has("components")).isFalse();
    }

    @Test
    void 관리_포트에는_health와_prometheus_외_엔드포인트가_없다() {
        for (String name : new String[] {"env", "info", "beans", "metrics", "loggers", "heapdump", "threaddump"}) {
            assertThat(management().get("/actuator/" + name).status()).as(name).isEqualTo(404);
        }
    }

    @Test
    void API_포트에는_actuator_경로가_없다() {
        for (String path : new String[] {
            "/actuator", "/actuator/health", "/actuator/health/readiness", "/actuator/prometheus"}) {
            assertThat(api().get(path).status()).as(path).isEqualTo(404);
        }
    }
}

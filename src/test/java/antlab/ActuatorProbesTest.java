package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ActuatorProbesTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void liveness와_readiness는_200_UP() {
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertThat(mvc.get().uri(path)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("UP");
        }
    }

    @Test
    void health는_세부_구성요소를_드러내지_않는다() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk().bodyJson().doesNotHavePath("$.components");
    }

    @Test
    void env_info_beans는_노출되지_않는다() {
        for (String path : new String[] {"/actuator/env", "/actuator/info", "/actuator/beans"}) {
            assertThat(mvc.get().uri(path)).as(path).hasStatus(404);
        }
    }
}

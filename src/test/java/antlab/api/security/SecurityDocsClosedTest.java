package antlab.api.security;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Where springdoc is off, its paths are not on the allow list. */
@IntegrationTest
@TestPropertySource(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class SecurityDocsClosedTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void springdoc이_꺼진_곳의_문서_경로는_401() {
        assertThat(mvc.get().uri("/v3/api-docs").requestAttr(ANONYMOUS, true)).hasStatus(401);
        assertThat(mvc.get().uri("/swagger-ui/index.html").requestAttr(ANONYMOUS, true)).hasStatus(401);
    }
}

package antlab.api.dev;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** local 프로필(test 와 함께)에서는 경로가 있지만 명세(/v3/api-docs)에는 나오지 않는다(@Hidden). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import(PostgresTestcontainersConfig.class)
class DevEndpointLocalProfileTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    MockMvcTester mvc;

    @Test
    void local에는_핸들러가_있고_명세에는_dev_경로가_없다() throws Exception {
        boolean mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.getPathPatternsCondition() != null
                        && info.getPathPatternsCondition().getPatternValues().contains("/api/v1/dev/seasons/{seasonId}/reconciliation"));
        assertThat(mapped).isTrue();
        String docs = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        assertThat(docs).contains("/api/v1/seasons").doesNotContain("/api/v1/dev").doesNotContain("/reconciliation");
    }
}

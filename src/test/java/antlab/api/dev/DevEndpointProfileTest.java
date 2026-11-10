package antlab.api.dev;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.ManualReconciliationService;
import antlab.support.IntegrationTest;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 개발 엔드포인트는 local 프로필에서만 존재한다 — test·기본·prod 에서는 빈도 경로도 없다. */
@IntegrationTest
class DevEndpointProfileTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    MockMvcTester mvc;

    @Test
    void test_프로필_문맥에는_dev_경로의_핸들러가_없고_요청은_404다() {
        Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> patternsOf(info).stream()).collect(Collectors.toSet());
        assertThat(patterns).isNotEmpty().noneMatch(p -> p.startsWith("/api/v1/dev"));
        MockHttpServletResponse r = mvc.post().uri("/api/v1/dev/seasons/1/reconciliation").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(404);
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }

    /** 컨트롤러 하나만 등록한 작은 문맥에서 프로필별 빈 유무를 본다. */
    private static boolean beanPresent(String... profiles) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles(profiles);
            ctx.registerBean(ManualReconciliationService.class, () -> Mockito.mock(ManualReconciliationService.class));
            ctx.register(DevReconciliationController.class);
            ctx.refresh();
            return ctx.getBeanNamesForType(DevReconciliationController.class).length == 1;
        }
    }

    @Test
    void 컨트롤러_빈은_local_프로필에만_있고_prod_test_기본에는_없다() {
        assertThat(beanPresent("local")).isTrue();
        assertThat(beanPresent("prod")).isFalse();
        assertThat(beanPresent("test")).isFalse();
        assertThat(beanPresent()).isFalse();
        assertThat(beanPresent("prod", "local")).isFalse();
        assertThat(beanPresent("test", "local")).isTrue();
    }
}

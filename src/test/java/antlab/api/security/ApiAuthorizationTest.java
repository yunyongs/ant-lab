package antlab.api.security;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Season paths need a token (401 without), then behave as before; unknown paths of a signed-in caller are 404. */
@IntegrationTest
class ApiAuthorizationTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void 토큰이_없으면_시즌_경로는_401이고_기본_토큰이면_열린다() {
        assertThat(mvc.get().uri("/api/v1/seasons").requestAttr(ANONYMOUS, true)).hasStatus(401)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
        assertThat(mvc.get().uri("/api/v1/seasons/1/holdings").requestAttr(ANONYMOUS, true)).hasStatus(401);
        assertThat(mvc.get().uri("/api/v1/seasons")).hasStatus(200);
        assertThat(mvc.get().uri("/api/v1/seasons/987654321/holdings")).hasStatus(404);
    }

    @Test
    void 직접_error를_부르면_401이다() {
        var result = mvc.get().uri("/error").requestAttr(ANONYMOUS, true).exchange();
        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 인증된_요청이_없는_경로로_오면_404이다() {
        assertThat(mvc.get().uri("/no/such/path")).hasStatus(404)
                .bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void 보안은_메서드_검사보다_먼저다() {
        assertThat(mvc.delete().uri("/api/v1/seasons").requestAttr(ANONYMOUS, true)).hasStatus(401);
        assertThat(mvc.delete().uri("/api/v1/seasons")).hasStatus(405);
    }
}

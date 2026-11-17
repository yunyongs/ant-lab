package antlab.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;

class ProblemRequestRejectedHandlerTest {

    private static final String BODY = "{\"title\":\"Bad Request\",\"status\":400,"
            + "\"detail\":\"요청의 주소나 헤더를 해석할 수 없다\",\"instance\":\"/\",\"code\":\"MALFORMED_REQUEST\"}";

    private final ProblemRequestRejectedHandler handler = new ProblemRequestRejectedHandler();

    @Test
    void 고정_본문과_헤더로_답하고_요청과_예외_메시지는_싣지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1//SECRET123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new RequestRejectedException("names SECRET123"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeader("Content-Length")).isEqualTo(String.valueOf(BODY.getBytes(StandardCharsets.UTF_8).length));
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SECRET123");
    }

    @Test
    void 이미_커밋된_응답은_건드리지_않는다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.getWriter().write("x");
        response.flushBuffer();
        assertThat(response.isCommitted()).isTrue();

        handler.handle(new MockHttpServletRequest("GET", "//x"), response, new RequestRejectedException("m"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("x");
    }
}

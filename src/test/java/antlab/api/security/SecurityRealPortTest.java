package antlab.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.E2eHttp.Reply;
import antlab.support.RealPortTest;
import org.junit.jupiter.api.Test;

/** The filter itself, not MockMvc, writes the 401 body. */
class SecurityRealPortTest extends RealPortTest {

    @Test
    void 허용_목록_밖_경로는_실제_포트에서도_401_문제_본문이다() {
        Reply reply = anonymousApi().get("/no/such/path");
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(reply.json().get("instance").asString()).isEqualTo("/no/such/path");
    }
}

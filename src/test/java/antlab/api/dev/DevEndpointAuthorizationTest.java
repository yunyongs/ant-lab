package antlab.api.dev;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.DefaultBearerConfig;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The dev endpoint needs role ADMIN (URL rule) and keeps its own header gate: anonymous 401, USER 403, ADMIN without the
 * header 403, ADMIN with the header 200. The path does not exist outside the local profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({PostgresTestcontainersConfig.class, TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
class DevEndpointAuthorizationTest {

    private static final String PATH = "/api/v1/dev/seasons/987654321/reconciliation";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    TestTokens tokens;

    @Autowired
    TestMembers members;

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.post().uri(PATH).requestAttr(ANONYMOUS, true)
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE))
                .hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void USER는_헤더가_있어도_403_FORBIDDEN이다() {
        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(tokens.user(7)))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE))
                .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN");
        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(tokens.user(7))))
                .hasStatus(403).bodyJson().extractingPath("$.detail").isEqualTo("권한이 없다");
    }

    @Test
    void ADMIN도_개발용_헤더가_없으면_403이다() {
        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(tokens.admin(7))))
                .hasStatus(403).bodyJson().extractingPath("$.detail").isEqualTo("개발용 헤더가 없다");
    }

    @Test
    void ADMIN과_헤더가_있으면_보안을_통과해_없는_시즌은_404이다() {
        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(tokens.admin(members.defaultId())))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE))
                .hasStatus(404);
    }
}

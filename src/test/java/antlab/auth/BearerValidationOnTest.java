package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.IntegrationTest;
import antlab.support.TestTokens;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;

@IntegrationTest
@Import(TestTokens.class)
class BearerValidationOnTest {

    @Autowired
    FilterChainProxy chain;
    @Autowired
    ApplicationContext context;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    TestTokens tokens;

    @Test
    void 체인에_베어러_필터가_있고_디코더_빈은_하나다() {
        List<Filter> filters = chain.getFilterChains().stream().flatMap(c -> c.getFilters().stream()).toList();
        assertThat(filters.stream().map(f -> f.getClass().getSimpleName())).contains("BearerTokenAuthenticationFilter");
        assertThat(context.getBeanNamesForType(JwtDecoder.class)).hasSize(1);
    }

    @Test
    void 디코더는_우리_키로_서명한_토큰을_읽고_다른_키의_토큰은_거절한다() {
        assertThat(decoder.decode(tokens.user(7)).getSubject()).isEqualTo("7");
        assertThatThrownBy(() -> decoder.decode(tokens.signedByOtherKey(tokens.header(), tokens.claims(7, "USER"))))
                .isInstanceOf(BadJwtException.class);
    }
}

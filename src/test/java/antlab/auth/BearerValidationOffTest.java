package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;

/**
 * SEC-03 only issues tokens. The resource-server starter is on the classpath, but no bearer filter runs and no decoder
 * exists until SEC-04, so no existing path starts to require or read a token.
 */
@IntegrationTest
class BearerValidationOffTest {

    @Autowired
    FilterChainProxy chain;

    @Autowired
    ApplicationContext context;

    @Test
    void 시즌_경로의_필터에_베어러_인증_필터가_없고_디코더_빈도_없다() {
        List<Filter> filters = chain.getFilterChains().stream().flatMap(c -> c.getFilters().stream()).toList();
        assertThat(filters).isNotEmpty();
        assertThat(filters.stream().map(f -> f.getClass().getSimpleName())).doesNotContain("BearerTokenAuthenticationFilter");
        assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
    }
}

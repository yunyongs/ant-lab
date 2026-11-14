package antlab.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Gives every MockMvc request a valid token of the default test member ({@link TestMembers}) unless the request sets its own {@code Authorization} header or the
 * {@link #ANONYMOUS} request attribute. The token still goes through the real decoder and validator.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DefaultBearerConfig {

    /** Set this request attribute (to any value) to send the request without the default token. */
    public static final String ANONYMOUS = "antlab.test.anonymous";

    @Bean
    MockMvcBuilderCustomizer defaultBearerCustomizer(TestTokens tokens, TestMembers members) {
        // Resolved when MockMvc is built (after Flyway, outside any test transaction), not when this bean is created.
        return builder -> {
            String header = "Bearer " + tokens.user(members.defaultId());
            builder.defaultRequest(get("/").with(request -> {
                if (request.getHeader("Authorization") == null && request.getAttribute(ANONYMOUS) == null) {
                    request.addHeader("Authorization", header);
                }
                return request;
            }));
        };
    }
}

package antlab.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * Every problem response an integration test provokes must be listed in the contract's {@code x-error-codes} for that
 * operation and status (or in {@code x-framework-errors} when no handler was matched). The check runs as a MockMvc
 * {@code alwaysDo}, so existing domain error tests become contract tests without being edited. Operations outside the
 * contract (the dev endpoint, test-only controllers) are skipped.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContractErrorCheck {

    private static final String PROBLEM = "application/problem+json";
    private static final AtomicInteger CHECKED = new AtomicInteger();
    private static JsonNode contract;

    /** Number of problem responses checked so far (vacuity guard for the hook itself). */
    public static int checked() {
        return CHECKED.get();
    }

    private static synchronized JsonNode contract() throws IOException {
        if (contract == null) {
            contract = OpenApiNormalizer.fromYaml(Files.readString(Path.of("docs/api/openapi.yaml")));
        }
        return contract;
    }

    @Bean
    MockMvcBuilderCustomizer contractErrorCodeCheck() {
        return builder -> builder.alwaysDo(result -> {
            var response = result.getResponse();
            String contentType = response.getContentType();
            if (contentType == null || !contentType.startsWith(PROBLEM)) {
                return;
            }
            JsonNode doc = contract();
            int status = response.getStatus();
            String code = OpenApiNormalizer.fromJson(response.getContentAsString()).path("code").asString("");
            Object pattern = result.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String key;
            if (pattern == null) {
                if (!ContractErrorCodes.framework(doc).containsKey(status)) {
                    return;
                }
                key = "(no handler)";
            } else {
                key = result.getRequest().getMethod() + " " + pattern;
                if (!ContractErrorCodes.perOperation(doc).containsKey(key)) {
                    return;
                }
            }
            CHECKED.incrementAndGet();
            if (!ContractErrorCodes.isListed(doc, key, status, code)) {
                throw new AssertionError("Contract does not list " + code + " for " + key + " " + status
                        + " (add it to x-error-codes and to the @ApiResponse text)");
            }
        });
    }
}

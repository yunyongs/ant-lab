package antlab.api.config;

import antlab.api.error.ApiProblem;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.math.BigDecimal;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 명세 머리말과 금액 스키마. BigDecimal 은 JSON 문자열이므로 명세도 {@code type: string} 으로 맞춘다. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** 모든 엔드포인트가 공유하는 500 응답 컴포넌트 이름(계약 docs/api/openapi.yaml 의 components.responses 와 같다). */
    static final String INTERNAL_ERROR_RESPONSE = "InternalError";

    static {
        // enum 은 이름 붙은 컴포넌트로 낸다(웹 타입 이름이 된다 — 계약 docs/api/openapi.yaml 과 같게).
        ModelResolver.enumsAsRef = true;
        SpringDocUtils.getConfig().replaceWithSchema(
                BigDecimal.class, new Schema<String>().type("string").format("decimal"));
    }

    @Bean
    OpenAPI antlabOpenApi() {
        return new OpenAPI().info(new Info().title("my Fund Manager API").version("0.0.1"));
    }

    /**
     * 모든 운영(개발용 엔드포인트 포함)에 공통 500 응답을 붙인다. 컨트롤러마다 적지 않아도 빠지는 곳이 없게
     * 명세를 다 만든 뒤 한 번에 처리한다. 운영이 500 을 이미 적었으면 그대로 둔다.
     */
    @Bean
    OpenApiCustomizer internalErrorResponseCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            Components components = openApi.getComponents();
            // 오류 본문 스키마(Problem·ErrorCode)가 컴포넌트에 있게 한다 — 다른 운영이 안 써도 500 이 참조한다.
            ModelConverters.getInstance(true).readAll(ApiProblem.class).forEach(components::addSchemas);
            components.addResponses(INTERNAL_ERROR_RESPONSE, new ApiResponse()
                    .description("예상하지 못한 서버 오류 — INTERNAL_ERROR. 예외 내용은 싣지 않는 고정 문장이다(내용은 서버 로그에만)")
                    .content(new Content().addMediaType("application/problem+json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")))));
            if (openApi.getPaths() == null) {
                return;
            }
            for (PathItem item : openApi.getPaths().values()) {
                for (Operation operation : item.readOperations()) {
                    if (operation.getResponses() == null) {
                        operation.setResponses(new ApiResponses());
                    }
                    operation.getResponses().putIfAbsent("500",
                            new ApiResponse().$ref("#/components/responses/" + INTERNAL_ERROR_RESPONSE));
                }
            }
        };
    }
}

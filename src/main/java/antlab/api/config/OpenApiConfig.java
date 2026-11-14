package antlab.api.config;

import antlab.api.error.ApiProblem;
import antlab.api.security.PublicPaths;
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
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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

    /** The 401 response component of the secured operations. */
    static final String UNAUTHENTICATED_RESPONSE = "Unauthenticated";

    /** The security scheme name used by every secured operation. */
    static final String BEARER_SCHEME = "bearerAuth";

    private static final String PATH_PREFIX = "/api/v1/";

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
                    .description("An unexpected server error — INTERNAL_ERROR. The message is a fixed sentence that "
                            + "carries no exception content (the content goes to the server log only)\n\n"
                            + "예상하지 못한 서버 오류 — INTERNAL_ERROR. 예외 내용은 싣지 않는 고정 문장이다(내용은 서버 로그에만)")
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

    /**
     * Marks every operation that is not in {@link PublicPaths#CONTRACT_PUBLIC} as needing the bearer token and lists the
     * shared 401 for it, so the contract follows the same list as the security rules. An operation that already
     * declares a 401 keeps it.
     */
    @Bean
    OpenApiCustomizer bearerSecurityCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            Components components = openApi.getComponents();
            components.addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                    .description("The access token from POST /api/v1/auth/login or /api/v1/auth/refresh, sent as "
                            + "Authorization: Bearer <token>. It lasts 15 minutes.\n\n"
                            + "POST /api/v1/auth/login이나 /api/v1/auth/refresh가 준 접근 토큰을 "
                            + "Authorization: Bearer <토큰>으로 보낸다. 15분 동안 쓴다."));
            components.addResponses(UNAUTHENTICATED_RESPONSE, new ApiResponse()
                    .description("The request has no valid bearer token: missing, expired, wrongly signed, or naming no member — "
                            + "UNAUTHENTICATED\n\n"
                            + "유효한 Bearer 토큰이 없다: 없거나 만료됐거나 서명이 틀렸거나 없는 회원의 토큰이다 — UNAUTHENTICATED")
                    .content(new Content().addMediaType("application/problem+json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")))));
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith(PATH_PREFIX) || PublicPaths.CONTRACT_PUBLIC.contains(path)) {
                    return;
                }
                for (Operation operation : item.readOperations()) {
                    operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
                    if (operation.getResponses() == null) {
                        operation.setResponses(new ApiResponses());
                    }
                    operation.getResponses().putIfAbsent("401",
                            new ApiResponse().$ref("#/components/responses/" + UNAUTHENTICATED_RESPONSE));
                }
            });
        };
    }
}

package antlab.api.config;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 명세 머리말과 금액 스키마. BigDecimal 은 JSON 문자열이므로 명세도 {@code type: string} 으로 맞춘다. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

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
}

package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** application.yml 의 문서 구성(공통·local·prod)을 단위로 확인한다. 앱을 띄우지 않는다. */
class ProfileDocumentTest {

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    }

    private static PropertySource<?> byProfile(List<PropertySource<?>> docs, String profile) {
        return docs.stream()
                .filter(d -> profile.equals(d.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 문서는_공통_local_prod_셋뿐이다() throws IOException {
        assertThat(documents()).hasSize(3);
    }

    @Test
    void 공통_문서는_SQL_로그가_없고_springdoc을_끈다() throws IOException {
        PropertySource<?> common = documents().get(0);
        assertThat(common.getProperty("logging.level.org.hibernate.SQL")).isNull();
        assertThat(common.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(common.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        assertThat(common.getProperty("spring.config.activate.on-profile")).isNull();
    }

    @Test
    void local_문서는_SQL_로그와_springdoc을_켠다() throws IOException {
        PropertySource<?> local = byProfile(documents(), "local");
        assertThat(local.getProperty("logging.level.org.hibernate.SQL")).isEqualTo("debug");
        assertThat(local.getProperty("springdoc.api-docs.enabled")).isEqualTo(true);
        assertThat(local.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(true);
    }

    @Test
    void prod_문서는_springdoc을_끄고_프록시_헤더를_따른다() throws IOException {
        PropertySource<?> prod = byProfile(documents(), "prod");
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
    }
}

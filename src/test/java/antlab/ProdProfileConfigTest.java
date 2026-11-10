package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * prod 프로필의 설정 경계를 앱을 띄우지 않고 확인한다.
 * 실행 기기의 실제 환경 변수·시스템 속성은 환경에서 지우고, 환경 변수 자리에는 시험용 속성원을 넣는다
 * (ANTLAB_DB_URL 등을 export한 셸에서도 같은 결과).
 */
class ProdProfileConfigTest {

    /** 실제 환경 변수·시스템 속성을 지운 환경. */
    private static StandardEnvironment isolatedEnvironment() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return env;
    }

    private static ConfigurableEnvironment prodEnvironment(Map<String, Object> envLike) {
        StandardEnvironment env = isolatedEnvironment();
        // 환경 변수에 해당하는 최우선 속성원(이름 규칙도 실제 환경 변수와 같다: ANTLAB_DB_URL, SPRING_DATASOURCE_PASSWORD)
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("envLike", envLike));
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), null, "prod");
        return env;
    }

    @Test
    void prod에서_ANTLAB_DB_URL이_없으면_localhost로_가지_않고_실패한다() {
        ConfigurableEnvironment env = prodEnvironment(Map.of());
        assertThatThrownBy(() -> env.getProperty("spring.datasource.url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTLAB_DB_URL");
    }

    @Test
    void prod에서_ANTLAB_DB_URL이_있으면_그_값을_쓴다() {
        ConfigurableEnvironment env =
                prodEnvironment(Map.of("ANTLAB_DB_URL", "jdbc:postgresql://db:5432/antlab_api"));
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/antlab_api");
    }

    @Test
    void prod에서_비밀_디렉터리가_없어도_설정을_읽는다() {
        ConfigurableEnvironment env = prodEnvironment(Map.of("ANTLAB_DB_URL", "jdbc:postgresql://db:5432/x"));
        // /run/secrets 가 있는 기기(컨테이너·CI)에서는 이 시험의 전제가 깨지므로 건너뛴다.
        Assumptions.assumeFalse(Files.exists(Path.of("/run/secrets")));
        assertThat(env.getProperty("spring.application.name")).isEqualTo("antlab");
        assertThat(env.getProperty("spring.datasource.password")).isEmpty();
    }

    @Test
    void configtree는_파일_이름을_속성_이름으로_읽고_환경_변수가_이긴다(@TempDir Path secrets) throws IOException {
        // 임시 시험값. 값은 단언에만 쓰고 출력하지 않는다.
        Files.writeString(secrets.resolve("spring.datasource.password"), "from-file");

        String importValue = "optional:configtree:" + secrets + "/";

        StandardEnvironment fileOnly = isolatedEnvironment();
        fileOnly.getPropertySources()
                .addFirst(new MapPropertySource("importOnly", Map.of("spring.config.import", importValue)));
        ConfigDataEnvironmentPostProcessor.applyTo(fileOnly, new DefaultResourceLoader(), null);
        assertThat(fileOnly.getProperty("spring.datasource.password")).isEqualTo("from-file");

        // 실제 환경 변수 이름(SPRING_DATASOURCE_PASSWORD)으로 줘도 같은 속성을 덮어쓴다.
        StandardEnvironment both = isolatedEnvironment();
        both.getPropertySources()
                .addFirst(new MapPropertySource("importOnly", Map.of("spring.config.import", importValue)));
        both.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "envLike", Map.of("SPRING_DATASOURCE_PASSWORD", "from-env")));
        ConfigDataEnvironmentPostProcessor.applyTo(both, new DefaultResourceLoader(), null);
        assertThat(both.getProperty("spring.datasource.password")).isEqualTo("from-env");
    }
}

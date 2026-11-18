package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.FAKE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

class EcosPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EcosProperties.class)
    static class Binding {
    }

    private static EcosProperties with(String key) {
        return new EcosProperties(true, key, 1, 20, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2));
    }

    @Test
    void 기본값은_꺼짐이고_키는_비어_있다() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            EcosProperties p = context.getBean(EcosProperties.class);
            assertThat(p).isEqualTo(new EcosProperties(false, "", 1, 20, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2)));
        });
    }

    @Test
    void 속성이_묶이고_범위를_벗어나면_기동이_실패한다() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withPropertyValues("antlab.collect.ecos.enabled=true", "antlab.collect.ecos.api-key=" + FAKE_KEY,
                        "antlab.collect.ecos.rps=2", "antlab.collect.ecos.page-limit=50", "antlab.collect.ecos.history-start=2000-01-01")
                .run(context -> assertThat(context.getBean(EcosProperties.class))
                        .isEqualTo(new EcosProperties(true, FAKE_KEY, 2, 20, 50, LocalDate.of(2000, 1, 1), 60, Duration.ofHours(2))));
        for (String bad : new String[] {"rps=3", "rps=0", "max-requests-per-run=0", "page-limit=100001", "page-limit=0", "lookback-days=-1"}) {
            new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues("antlab.collect.ecos." + bad)
                    .run(context -> assertThat(context).as(bad).hasFailed());
        }
    }

    @Test
    void 켠_채_키가_없거나_모양이_틀리면_이름만_말한다() {
        assertThatThrownBy(() -> with("").requireKey()).isInstanceOf(IllegalStateException.class).hasMessageContaining("ECOS_API_KEY");
        assertThatThrownBy(() -> with("   ").requireKey()).isInstanceOf(IllegalStateException.class).hasMessageContaining("ECOS_API_KEY");
        for (String bad : new String[] {"bad key/x", "short", "with-dash-1234567", "a/b/c/d/e/f/g/h"}) {
            assertThatThrownBy(() -> with(bad).requireKey()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ECOS_API_KEY").hasMessageNotContaining(bad);
        }
        with(FAKE_KEY).requireKey();
    }

    @Test
    void 문자열_표현에는_키가_없다() {
        assertThat(with(FAKE_KEY).toString()).doesNotContain(FAKE_KEY).contains("apiKey=****");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 설정_파일은_꺼진_상태와_환경_변수_자리표시자만_가진다() throws Exception {
        Map<String, Object> root = (Map<String, Object>) new Yaml()
                .loadAll(Files.readString(Path.of("src/main/resources/application.yml"))).iterator().next();
        Map<String, Object> ecos = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) root.get("antlab")).get("collect")).get("ecos");

        assertThat(ecos).containsEntry("enabled", false).containsEntry("api-key", "${ECOS_API_KEY:}").containsEntry("rps", 1)
                .containsEntry("max-requests-per-run", 20).containsEntry("page-limit", 100000);
    }
}

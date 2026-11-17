package antlab.engine.client;

import static antlab.engine.client.EngineHarness.FAKE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FakeEngineServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EnginePropertiesTest {

    private static final String LONG_TOKEN = "a".repeat(32);

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(EngineClientConfig.class);

    private static String failureText(AssertableApplicationContext context) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void 기본값은_자바_경로이고_URL이_비어도_뜨며_호출은_불가용이다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EngineClient.class);
            EngineProperties properties = context.getBean(EngineProperties.class);
            assertThat(properties.pathSource()).isEqualTo(EngineProperties.PathSourceMode.JAVA);
            assertThat(properties.url()).isEmpty();
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.pathsTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.projectionsTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.pathVersion()).isEqualTo("m2-sb6-v1");
            assertThat(properties.projectionPaths()).isEqualTo(2000);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(EngineClient.class).info())
                    .isInstanceOf(EngineUnavailableException.class);
        });
    }

    @Test
    void application_yml_의_antlab_engine_블록이_같은_값으로_묶인다() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("antlab.engine.url=", "antlab.engine.token=", "antlab.engine.path-source=java")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EngineProperties properties = context.getBean(EngineProperties.class);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.pathsTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.projectionsTimeout()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(properties.pathVersion()).isEqualTo("m2-sb6-v1");
                    assertThat(properties.projectionPaths()).isEqualTo(2000);
                });
    }

    @Test
    void 엔진_경로인데_URL이_비면_기동이_실패하고_변수_이름만_말한다() {
        runner.withPropertyValues("antlab.engine.path-source=engine", "antlab.engine.token=" + LONG_TOKEN).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureText(context)).contains("ANTLAB_ENGINE_URL").doesNotContain(LONG_TOKEN);
        });
    }

    @Test
    void 엔진_경로인데_토큰이_31자면_기동이_실패하고_토큰_값은_메시지에_없다() {
        String shortToken = "b".repeat(31);
        runner.withPropertyValues("antlab.engine.path-source=engine", "antlab.engine.url=http://engine:8000",
                "antlab.engine.token=" + shortToken).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureText(context)).contains("ANTLAB_ENGINE_TOKEN").doesNotContain(shortToken);
                });
    }

    @Test
    void 엔진_경로인데_토큰에_공백이나_비ASCII가_있으면_기동이_실패하고_값은_메시지에_없다() {
        for (String bad : new String[] { "c".repeat(15) + " " + "c".repeat(16), "d".repeat(31) + "é" }) {
            runner.withPropertyValues("antlab.engine.path-source=engine", "antlab.engine.url=http://engine:8000",
                    "antlab.engine.token=" + bad).run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(failureText(context)).contains("printable ASCII").doesNotContain(bad);
                    });
        }
    }

    @Test
    void 엔진_경로에_URL과_32자_토큰이면_뜬다() {
        runner.withPropertyValues("antlab.engine.path-source=engine", "antlab.engine.url=http://engine:8000",
                "antlab.engine.token=" + LONG_TOKEN).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EngineProperties.class).pathSource()).isEqualTo(EngineProperties.PathSourceMode.ENGINE);
                });
    }

    @Test
    void 잘못된_값은_기동을_막는다() {
        for (String bad : new String[] { "antlab.engine.path-version=m9", "antlab.engine.connect-timeout=0s",
                "antlab.engine.paths-timeout=-1s", "antlab.engine.projections-timeout=5m", "antlab.engine.projection-paths=99",
                "antlab.engine.projection-paths=5001", "antlab.engine.path-source=other" }) {
            runner.withPropertyValues(bad).run(context -> assertThat(context).as(bad).hasFailed());
        }
    }

    @Test
    void 토큰은_문자열_표현에서_가려진다() {
        EngineProperties properties = EngineHarness.properties("http://engine:8000", FAKE_TOKEN);

        assertThat(properties.toString()).doesNotContain(FAKE_TOKEN).contains("****").contains("http://engine:8000");
        assertThat(EngineHarness.properties("", "").toString()).doesNotContain("****");
    }

    @Test
    void 호출은_http_client_requests_관찰로_기록되고_uri_라벨은_경로_하나다() {
        List<Observation.Context> stopped = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        try (FakeEngineServer engine = FakeEngineServer.start()) {
            engine.respondFixture("/v1/info", 200, "info-response.json");
            var properties = EngineHarness.properties(engine.url(), FAKE_TOKEN);

            EngineClientConfig.newClient(properties, registry).info();
        }

        assertThat(stopped).hasSize(1);
        assertThat(stopped.getFirst().getName()).isEqualTo("http.client.requests");
        assertThat(stopped.getFirst().getLowCardinalityKeyValue("uri").getValue()).isEqualTo("/v1/info");
    }
}

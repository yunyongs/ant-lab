package antlab.engine.client;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineProperties.class)
class EngineClientConfig {

    @Bean
    EngineClient engineClient(EngineProperties properties, ObjectProvider<ObservationRegistry> observations) {
        return newClient(properties, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    static EngineClient newClient(EngineProperties properties, ObservationRegistry observations) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(properties.connectTimeout())
                .build();
        return new EngineClient(properties, builder(http, properties.pathsTimeout(), observations),
                builder(http, properties.projectionsTimeout(), observations));
    }

    /** One builder per operation family: the read timeout belongs to the request factory. */
    private static RestClient.Builder builder(HttpClient http, Duration readTimeout, ObservationRegistry observations) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).observationRegistry(observations);
    }
}

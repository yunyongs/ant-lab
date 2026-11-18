package antlab.collect.kis;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KisProperties.class)
public class KisClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    @ConditionalOnProperty(prefix = "antlab.collect.kis", name = "enabled", havingValue = "true")
    KisQuoteClientFactory kisQuoteClientFactory(KisProperties properties, Clock clock) {
        return new KisQuoteClientFactory(transportBuilder(), KisEndpoint.BASE_URI, properties, clock, Sleeper.SYSTEM);
    }

    /** JDK HTTP client that never follows a redirect and never uses a system proxy. */
    public static RestClient.Builder transportBuilder() {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }
}

package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class JwtKeyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtKeyConfig.class)
            .withPropertyValues("antlab.auth.access-token-ttl=PT15M", "antlab.auth.refresh-token-ttl=P14D");

    private static String base64OfBytes(int n) {
        byte[] bytes = new byte[n];
        java.util.Arrays.fill(bytes, (byte) 7);
        return Base64.getEncoder().encodeToString(bytes);
    }

    static Stream<String> badSecrets() {
        // Distinctive text so the "value never leaks" check cannot pass by accident.
        return Stream.of("", "   ", "not base64! LEAKCHECK-5f1c", base64OfBytes(31));
    }

    @ParameterizedTest
    @MethodSource("badSecrets")
    void 키가_없거나_깨졌거나_짧으면_시작하지_않고_값은_메시지에_없다(String secret) {
        runner.withPropertyValues("antlab.auth.jwt-secret=" + secret).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).contains("ANTLAB_JWT_SECRET");
            // The rejected value is in no message of the whole chain.
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                String message = String.valueOf(t.getMessage());
                if (!secret.isBlank()) {
                    assertThat(message).doesNotContain(secret);
                }
                assertThat(message).doesNotContain("LEAKCHECK");
            }
        });
    }

    @Test
    void 속성이_아예_없어도_시작하지_않는다() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 정확히_32바이트면_인코더가_만들어진다() {
        runner.withPropertyValues("antlab.auth.jwt-secret=" + base64OfBytes(32))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtEncoder.class));
    }

    static Stream<String[]> badLifetimes() {
        // {access, refresh, property named in the message}; null means the property is not set.
        return Stream.of(
                new String[] {"PT0S", "P14D", "access-token-ttl"},
                new String[] {"-PT1M", "P14D", "access-token-ttl"},
                new String[] {"PT2H", "P14D", "access-token-ttl"},
                new String[] {null, "P14D", "access-token-ttl"},
                new String[] {"PT15M", "PT0S", "refresh-token-ttl"},
                new String[] {"PT15M", "PT15M", "refresh-token-ttl"},
                new String[] {"PT15M", "P31D", "refresh-token-ttl"},
                new String[] {"PT15M", null, "refresh-token-ttl"});
    }

    @ParameterizedTest
    @MethodSource("badLifetimes")
    void 토큰_수명이_범위를_벗어나면_시작하지_않고_속성_이름을_알린다(String access, String refresh, String property) {
        java.util.List<String> values = new java.util.ArrayList<>();
        values.add("antlab.auth.jwt-secret=" + base64OfBytes(32));
        if (access != null) {
            values.add("antlab.auth.access-token-ttl=" + access);
        }
        if (refresh != null) {
            values.add("antlab.auth.refresh-token-ttl=" + refresh);
        }
        new ApplicationContextRunner()
                .withUserConfiguration(JwtKeyConfig.class)
                .withPropertyValues(values.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root.getMessage()).contains("antlab.auth." + property);
                });
    }

    @Test
    void AuthProperties_toString은_키를_숨긴다() {
        AuthProperties props = new AuthProperties("LEAKCHECK-secret", java.time.Duration.ofMinutes(15),
                java.time.Duration.ofDays(14));
        assertThat(props.toString()).doesNotContain("LEAKCHECK").contains("***");
    }
}

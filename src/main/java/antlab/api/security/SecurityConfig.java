package antlab.api.security;

import jakarta.servlet.DispatcherType;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * One stateless chain, default deny. A path outside the allow list answers 401 as a Problem body. Rules match by
 * path, never by method, so a wrong method on a public path stays 405. A new controller outside these prefixes needs a rule.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiChain(HttpSecurity http, SecurityProblemHandler problems, JwtDecoder decoder,
            JwtAuthenticationConverter converter,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocs,
            @Value("${springdoc.swagger-ui.enabled:false}") boolean swaggerUi) {
        RequestMatcher open = PublicPaths.matcher(apiDocs, swaggerUi);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(new AccessTokenResolver(open))
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems)
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)))
                .authorizeHttpRequests(a -> {
                    a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    a.requestMatchers("/error").permitAll();
                    // Actuator is gated by the exposure list and the unpublished management port, not by this chain.
                    a.requestMatchers(PublicPaths.OPERATIONS.toArray(String[]::new)).permitAll();
                    // Listed one by one: they are reached without an access token.
                    a.requestMatchers(PublicPaths.CONTRACT_PUBLIC.toArray(String[]::new)).permitAll();
                    // Until SEC-04: the fixed member serves these without a token.
                    a.requestMatchers("/api/v1/seasons", "/api/v1/seasons/**", "/api/v1/dev/**").permitAll();
                    if (apiDocs) {
                        a.requestMatchers(PublicPaths.API_DOCS.toArray(String[]::new)).permitAll();
                    }
                    if (swaggerUi) {
                        a.requestMatchers(PublicPaths.SWAGGER_UI.toArray(String[]::new)).permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // bcrypt only: the factory default also matches {noop}, {MD5}, {SHA-1} and other weak formats.
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
    }
}

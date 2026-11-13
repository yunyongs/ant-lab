package antlab.api.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One stateless chain, default deny. A path outside the allow list answers 401 as a Problem body. Rules match by
 * path, never by method, so a wrong method on a public path stays 405. A new controller outside these prefixes needs a rule.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiChain(HttpSecurity http, SecurityProblemHandler problems,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocs,
            @Value("${springdoc.swagger-ui.enabled:false}") boolean swaggerUi) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .authorizeHttpRequests(a -> {
                    a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    // Actuator is gated by the exposure list and the unpublished management port, not by this chain.
                    a.requestMatchers("/error", "/livez", "/readyz", "/actuator/**").permitAll();
                    // Auth endpoints are listed one by one (signup, login; refresh and logout follow).
                    a.requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/pool").permitAll();
                    // Until SEC-04: the fixed member serves these without a token.
                    a.requestMatchers("/api/v1/seasons", "/api/v1/seasons/**", "/api/v1/dev/**").permitAll();
                    if (apiDocs) {
                        a.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }
                    if (swaggerUi) {
                        a.requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll();
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

package antlab.api.security;

import antlab.agent.AgentActingResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The chain of the internal runner paths, ordered before the API chain. It has no JWT support: the service token is its
 * only credential, so a human access token never opens a runner path and the runner token never opens {@code /api/v1}.
 */
@Configuration(proxyBeanMethods = false)
class AgentRunnerSecurityConfig {

    static final String PREFIX = "/internal/agent-runner/";

    @Bean
    @Order(1)
    SecurityFilterChain agentRunnerChain(HttpSecurity http, SecurityProblemHandler problems, RunnerTokenCheck tokens,
            AgentActingResolver agents, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        return http
                .securityMatcher(PREFIX + "**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterBefore(new RunnerTokenFilter(tokens, agents, problems, resolver), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("AGENT_RUNNER"))
                .build();
    }
}

package antlab.common.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code @CreatedDate}·{@code @LastModifiedDate} 가 {@link Clock} 빈의 시각을 쓰게 한다.
 * 시각은 엔티티를 영속화하는 순간에 정해진다 — 서비스가 락을 얻은 뒤의 시각이다(erd 7.5 규칙 16).
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}

package antlab.persistence;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refuses to start when the app's DB user is not the least-privilege role (MVP-20). On in {@code prod}
 * ({@code antlab.db.least-privilege-check}); the check runs after Flyway, before the application reports ready.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "antlab.db", name = "least-privilege-check", havingValue = "true")
class DbPrivilegeGuardConfig {

    @Bean
    DbPrivilegeStartupCheck dbPrivilegeStartupCheck(JdbcTemplate jdbc, @Value("${spring.flyway.user:}") String flywayUser) {
        return new DbPrivilegeStartupCheck(jdbc, flywayUser);
    }

    static final class DbPrivilegeStartupCheck {

        private final JdbcTemplate jdbc;
        private final String flywayUser;

        DbPrivilegeStartupCheck(JdbcTemplate jdbc, String flywayUser) {
            this.jdbc = jdbc;
            this.flywayUser = flywayUser;
        }

        @EventListener(ApplicationStartedEvent.class)
        void check() {
            List<String> violations = DbPrivilegeGuard.violations(jdbc, flywayUser);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("antlab.db.least-privilege-check failed: " + String.join("; ", violations));
            }
        }
    }
}

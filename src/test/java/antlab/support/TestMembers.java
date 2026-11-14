package antlab.support;

import antlab.auth.JwtCurrentMember;
import antlab.member.CurrentMember;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The default test member: a real, committed {@code member} row that the default test token names. The password
 * column holds {@code '!'}, which no login password ever matches, so this member acts only through {@link TestTokens}.
 * The row is created on first use in a transaction of its own and stays for the whole Spring context.
 *
 * <p>Tests that call a service directly (no filter chain, so no JWT in the security context) would get 401 from
 * {@link JwtCurrentMember}. A primary {@link CurrentMember} covers exactly that case: when nothing is authenticated
 * it runs {@code JwtCurrentMember} as the default member; any existing authentication, JWT or not, goes straight to
 * {@code JwtCurrentMember}. Requests through MockMvc or a real port always carry a token and never take the fallback.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMembers {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final String email;
    private final String name;

    private volatile Long defaultId;

    public TestMembers(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            @Value("${antlab.test.default-member.email:player@example.invalid}") String email,
            @Value("${antlab.test.default-member.name:감독}") String name) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.email = email;
        this.name = name;
    }

    @Bean
    @Primary
    CurrentMember directCallMember(JwtCurrentMember jwt) {
        return new CurrentMember() {
            @Override
            public Optional<Long> existingId() {
                return asDefaultWhenAnonymous(jwt::existingId);
            }

            @Override
            public long ensureId() {
                return asDefaultWhenAnonymous(jwt::ensureId);
            }
        };
    }

    private <T> T asDefaultWhenAnonymous(Supplier<T> call) {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication before = context.getAuthentication();
        if (before != null) {
            return call.get();
        }
        Instant now = Instant.parse("2000-01-01T00:00:00Z");
        Jwt token = Jwt.withTokenValue("not-a-real-token").header("alg", "HS256")
                .subject(String.valueOf(defaultId())).issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        context.setAuthentication(new JwtAuthenticationToken(token));
        try {
            return call.get();
        } finally {
            context.setAuthentication(before);
        }
    }

    public String defaultEmail() {
        return email;
    }

    /** The id of the default member; inserts the row on the first call. */
    public long defaultId() {
        Long id = defaultId;
        if (id == null) {
            synchronized (this) {
                if (defaultId == null) {
                    defaultId = requiresNew.execute(status -> {
                        jdbc.update("INSERT INTO member (email, name, password) VALUES (?, ?, '!') ON CONFLICT DO NOTHING",
                                email, name);
                        return jdbc.queryForObject("SELECT id FROM member WHERE lower(email) = lower(?)", Long.class, email);
                    });
                }
                id = defaultId;
            }
        }
        return id;
    }
}

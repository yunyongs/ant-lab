package antlab.auth;

import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Checks a password with exactly one BCrypt match on every path, so an unknown email, the fixed member's marker and a
 * non-bcrypt row cost the same as a wrong password. Only a stored {@code {bcrypt}} hash can match.
 */
@Component
public class PasswordCheck {

    private static final String BCRYPT_PREFIX = "{bcrypt}";

    private final PasswordEncoder encoder;
    private final String dummyHash;

    public PasswordCheck(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public boolean matches(String raw, String stored) {
        boolean usable = stored != null && stored.startsWith(BCRYPT_PREFIX);
        boolean matched = encoder.matches(raw, usable ? stored : dummyHash);
        return usable && matched;
    }
}

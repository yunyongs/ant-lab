package antlab.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Every rule an access token must pass after the signature. Spring's timestamp validator accepts {@code now == exp} and skews
 * by a minute, so the time rules live here: {@code iat <= now < exp}, {@code nbf <= now}, no skew, the clock read once.
 * The failure text never contains a claim value.
 */
public final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final Pattern SUBJECT = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Set<String> ROLES = Set.of("USER", "ADMIN");

    private final Clock clock;

    public AccessTokenValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String problem = check(jwt, clock.instant());
        return problem == null ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", problem, null));
    }

    private static String check(Jwt jwt, Instant now) {
        if (!"JWT".equals(jwt.getHeaders().get("typ"))) {
            return "typ is not JWT";
        }
        if (!AccessTokenIssuer.ISSUER.equals(jwt.getClaimAsString("iss"))) {
            return "iss is not accepted";
        }
        String sub = jwt.getSubject();
        if (sub == null || !SUBJECT.matcher(sub).matches() || !fitsLong(sub)) {
            return "sub is not a member id";
        }
        if (!(jwt.getClaims().get("role") instanceof String role) || !ROLES.contains(role)) {
            return "role is not accepted";
        }
        Instant iat = jwt.getIssuedAt();
        if (iat == null || iat.isAfter(now)) {
            return "iat is missing or in the future";
        }
        Instant exp = jwt.getExpiresAt();
        if (exp == null || !now.isBefore(exp)) {
            return "exp is missing or passed";
        }
        Instant nbf = jwt.getNotBefore();
        if (nbf != null && nbf.isAfter(now)) {
            return "nbf is in the future";
        }
        return null;
    }

    private static boolean fitsLong(String digits) {
        try {
            Long.parseLong(digits);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

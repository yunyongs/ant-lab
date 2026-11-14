package antlab.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mints and forges access tokens by hand, so a test can break any single rule. The valid span 2000..2100 accepts any
 * scripted clock value in between. The key is the app's own bean; a forged key is a different random one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

    public static final Instant ISSUED = Instant.parse("2000-01-01T00:00:00Z");
    public static final Instant EXPIRES = Instant.parse("2100-01-01T00:00:00Z");

    /** The member of the default test token. The fixed member ignores it; a real member never has this id. */
    public static final long DEFAULT_SUBJECT = Long.MAX_VALUE;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;

    public TestTokens(SecretKey jwtSigningKey) {
        this.key = jwtSigningKey.getEncoded();
    }

    public String user(long sub) {
        return sign(header(), claims(sub, "USER"));
    }

    public String admin(long sub) {
        return sign(header(), claims(sub, "ADMIN"));
    }

    /** A valid header, mutable: remove or replace one entry to break one rule. */
    public Map<String, Object> header() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", "HS256");
        h.put("typ", "JWT");
        return h;
    }

    /** Valid claims, mutable. Times are epoch seconds. */
    public Map<String, Object> claims(long sub, String role) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "antlab");
        c.put("sub", String.valueOf(sub));
        c.put("iat", ISSUED.getEpochSecond());
        c.put("exp", EXPIRES.getEpochSecond());
        c.put("role", role);
        return c;
    }

    /** Signs with the app key; the header's {@code alg} picks the HMAC (HS384, HS512), any other value uses HS256, {@code none} gives an empty signature. */
    public String sign(Map<String, Object> header, Map<String, Object> claims) {
        return sign(key, header, claims);
    }

    /** Same, but with a key the app does not know. */
    public String signedByOtherKey(Map<String, Object> header, Map<String, Object> claims) {
        byte[] other = new byte[32];
        new SecureRandom().nextBytes(other);
        return sign(other, header, claims);
    }

    /** A valid user token whose payload says ADMIN while the signature stays the one of the USER payload. */
    public String tamper(long sub) {
        String[] parts = user(sub).split("\\.");
        String payload = B64.encodeToString(json(claims(sub, "ADMIN")).getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + payload + "." + parts[2];
    }

    private static String sign(byte[] key, Map<String, Object> header, Map<String, Object> claims) {
        String signingInput = part(header) + "." + part(claims);
        Object alg = header.get("alg");
        if ("none".equals(alg)) {
            return signingInput + ".";
        }
        String mac = "HS384".equals(alg) ? "HmacSHA384" : "HS512".equals(alg) ? "HmacSHA512" : "HmacSHA256";
        try {
            Mac hmac = Mac.getInstance(mac);
            hmac.init(new SecretKeySpec(key, mac));
            return signingInput + "." + B64.encodeToString(hmac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String part(Map<String, Object> map) {
        return B64.encodeToString(json(map).getBytes(StandardCharsets.UTF_8));
    }

    private static String json(Map<String, Object> map) {
        return JsonMapper.shared().writeValueAsString(map);
    }
}

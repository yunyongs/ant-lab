package antlab.collect.kis;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces secrets in text with {@value #MASK}: values it was told about (key, secret, tokens) verbatim, and anything
 * shaped like a bearer token or a secret-named JSON/query value. Last line of defence — messages are built without
 * secrets in the first place.
 */
final class SecretMasker {

    static final String MASK = "****";
    private static final int MIN_SECRET_LENGTH = 4;
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+");
    private static final Pattern NAMED = Pattern.compile(
            "(?i)((?:app[-_]?key|app[-_]?secret|access[-_]?token|token|secret|authorization)[\"']?\\s*(?::|=|%3D)\\s*[\"']?)[^\\s\"',&}]+");

    private final Set<String> secrets = new CopyOnWriteArraySet<>();

    void register(String secret) {
        if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
            secrets.add(secret);
        }
    }

    String mask(String text) {
        String out = text;
        for (String secret : secrets) {
            out = out.replace(secret, MASK);
        }
        out = replaceKeepingPrefix(BEARER, out);
        return replaceKeepingPrefix(NAMED, out);
    }

    private static String replaceKeepingPrefix(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(result -> Matcher.quoteReplacement(result.group(1) + MASK));
    }
}

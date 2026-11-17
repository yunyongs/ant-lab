package antlab.feed;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** The pick hash of {@code db/24}: the first 8 bytes of MD5, big-endian, with the sign bit cleared. */
public final class FeedHash {

    private FeedHash() {
    }

    /** SQL {@code tf_hash}: {@code ('x' || substr(md5(text), 1, 16))::bit(64)::bigint & 9223372036854775807}. */
    public static long hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    /** SQL {@code tf_pick_hash}: seed, turn, rule version, usage and natural key, colon separated. */
    public static long pick(long seed, int turnNo, String usage, String key) {
        return hash(Long.toString(seed) + ":" + turnNo + ":" + FeedRules.RULE_VERSION + ":" + usage + ":" + key);
    }

    /** Byte order of the UTF-8 encoding, the same order as PostgreSQL {@code COLLATE "C"} in a UTF-8 database. */
    public static int compareBytes(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

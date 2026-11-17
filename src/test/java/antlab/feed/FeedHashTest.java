package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fixed vectors of {@code tf_hash}. The values come from an independent MD5 computation; {@code FeedGeneratorSqlParityTest} runs the
 * same strings through PostgreSQL {@code tf_hash} and requires the Java hash to equal it, which ties these constants to the SQL.
 */
class FeedHashTest {

    static final Map<String, Long> VECTORS = Map.of(
            "", 6061155539545534980L,
            "1:1:p0-18:v2:count:", 2601431901593060967L,
            "7531902468135790246:7:p0-18:v2:tpl:event:aaaaaa_01:1", 662741730895423508L,
            "-4096209173561902478:14:p0-18:v2:pick:group:FEED_TECH:3:2", 1774508532670234767L,
            "가나다:order:\"\\", 7053989333462064509L);

    @Test
    void the_hash_is_the_first_eight_md5_bytes_big_endian_with_the_sign_bit_cleared() {
        VECTORS.forEach((text, expected) -> assertThat(FeedHash.hash(text)).as(text).isEqualTo(expected));
        assertThat(VECTORS.values()).allMatch(v -> v >= 0);
    }

    @Test
    void the_pick_hash_joins_seed_turn_rule_version_usage_and_key_with_colons() {
        assertThat(FeedHash.pick(1L, 1, "count", "")).isEqualTo(FeedHash.hash("1:1:p0-18:v2:count:"));
        assertThat(FeedHash.pick(-4096209173561902478L, 14, "pick", "group:FEED_TECH:3:2"))
                .isEqualTo(FeedHash.hash("-4096209173561902478:14:p0-18:v2:pick:group:FEED_TECH:3:2"));
    }

    @Test
    void byte_order_ranks_uppercase_before_lowercase_and_a_prefix_first() {
        assertThat(FeedHash.compareBytes("B", "a")).isNegative();
        assertThat(FeedHash.compareBytes("asset:KR:F1", "asset:KR:F10")).isNegative();
        assertThat(FeedHash.compareBytes("event:a", "event:a")).isZero();
    }
}

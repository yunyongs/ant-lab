package antlab.feed;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes template variables in the text form PostgreSQL gives a jsonb object, so stored and regenerated rows compare as text. */
final class FeedVarsJson {

    private FeedVarsJson() {
    }

    /** Keys ordered by UTF-8 length, then bytes; {@code {"k": v, "k2": v2}}; numbers keep their scale; strings escaped like jsonb output. */
    static String canonical(Map<String, Object> values) {
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort((a, b) -> {
            int byLength = Integer.compare(a.getBytes(StandardCharsets.UTF_8).length, b.getBytes(StandardCharsets.UTF_8).length);
            return byLength != 0 ? byLength : FeedHash.compareBytes(a, b);
        });
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            String key = keys.get(i);
            string(out, key);
            out.append(": ");
            Object value = values.get(key);
            if (value instanceof BigDecimal number) {
                out.append(number.toPlainString());
            } else if (value instanceof String text) {
                string(out, text);
            } else {
                throw new FeedGenerationException(FeedFailure.FEED_RENDER, "template variable of an unsupported type");
            }
        }
        return out.append('}').toString();
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

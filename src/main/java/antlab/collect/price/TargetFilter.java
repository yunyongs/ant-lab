package antlab.collect.price;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Narrows a run to some targets ("KR:005930,US:AAPL"); empty means every target of the catalog. */
public record TargetFilter(Set<String> keys) {

    private static final Pattern KEY = Pattern.compile("(KR|US):[A-Za-z0-9.]{1,10}");

    public static TargetFilter all() {
        return new TargetFilter(Set.of());
    }

    public static TargetFilter parse(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String part : text.split(",", -1)) {
            String key = part.strip();
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("a target is written MARKET:TICKER, for example KR:005930");
            }
            keys.add(key);
        }
        return new TargetFilter(Set.copyOf(keys));
    }

    public boolean isAll() {
        return keys.isEmpty();
    }
}

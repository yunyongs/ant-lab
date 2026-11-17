package antlab.feed;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Port of {@code tf_render}: one left-to-right pass over the sentence, each slot replaced by its variable rendered per its type.
 * A value is a {@link String} for a name slot and a {@link BigDecimal} for every numeric slot. Anything that does not fit the contract
 * (missing, extra or null value, wrong type, too long, fractional integer) is {@link FeedFailure#FEED_RENDER}.
 */
final class FeedRenderer {

    private static final Pattern SLOT_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int MAX_BODY = 300;

    private final Map<String, FeedVariable> variables;

    FeedRenderer(Map<String, FeedVariable> variables) {
        this.variables = variables;
    }

    String render(String text, Map<String, Object> values) {
        if (text == null || values == null) {
            throw failure("render input is missing");
        }
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            int open = text.indexOf('{', pos);
            if (open < 0) {
                out.append(text, pos, text.length());
                break;
            }
            out.append(text, pos, open);
            int close = text.indexOf('}', open + 1);
            if (close < 0) {
                throw failure("unclosed slot");
            }
            String name = text.substring(open + 1, close);
            if (!SLOT_NAME.matcher(name).matches()) {
                throw failure("malformed slot");
            }
            if (!values.containsKey(name)) {
                throw failure("missing value for slot " + name);
            }
            FeedVariable variable = variables.get(name);
            if (variable == null) {
                throw failure("slot is not a variable: " + name);
            }
            String rendered = renderValue(variable, values.get(name));
            if (variable.maxLen() != null && rendered.codePointCount(0, rendered.length()) > variable.maxLen()) {
                throw failure("value too long for slot " + name);
            }
            out.append(rendered);
            pos = close + 1;
        }
        Set<String> slots = Set.copyOf(FeedTemplate.placeholdersOf(text));
        if (!slots.containsAll(values.keySet())) {
            throw failure("value for a slot the sentence does not have");
        }
        if (out.codePointCount(0, out.length()) > MAX_BODY) {
            throw failure("body longer than " + MAX_BODY);
        }
        return out.toString();
    }

    private static String renderValue(FeedVariable variable, Object value) {
        switch (variable.type()) {
            case FeedVariable.TYPE_NAME:
                if (!(value instanceof String s)) {
                    throw failure("name slot needs a string: " + variable.name());
                }
                return s;
            case FeedVariable.TYPE_PCT_SIGNED: {
                BigDecimal percent = percent(variable, value);
                return percent.signum() > 0 ? "+" + percent.toPlainString() + "%"
                        : percent.signum() < 0 ? "-" + percent.abs().toPlainString() + "%" : "0%";
            }
            case FeedVariable.TYPE_PCT_UNSIGNED:
                return percent(variable, value).toPlainString() + "%";
            case FeedVariable.TYPE_INT: {
                if (!(value instanceof BigDecimal number)) {
                    throw failure("integer slot needs a number: " + variable.name());
                }
                try {
                    return number.toBigIntegerExact().longValueExact() + "";
                } catch (ArithmeticException e) {
                    throw failure("not a whole number: " + variable.name());
                }
            }
            default:
                throw failure("unknown variable type: " + variable.name());
        }
    }

    private static BigDecimal percent(FeedVariable variable, Object value) {
        if (!(value instanceof BigDecimal ratio)) {
            throw failure("percent slot needs a number: " + variable.name());
        }
        return MoneyMath.percentPoints(ratio);
    }

    private static FeedGenerationException failure(String reason) {
        return new FeedGenerationException(FeedFailure.FEED_RENDER, reason);
    }
}

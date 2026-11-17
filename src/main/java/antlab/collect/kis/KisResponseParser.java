package antlab.collect.kis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Reads the {@code output2} rows of a daily-quote response. Errors name the field, never the value. */
final class KisResponseParser {

    static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern PLAIN_PRICE = Pattern.compile("\\d{1,15}(\\.\\d+)?");
    private static final int MAX_DECIMALS = 4;

    private KisResponseParser() {
    }

    static List<DailyClose> rows(JsonNode response, String dateField, String closeField) {
        JsonNode rows = response.path("output2");
        List<DailyClose> out = new ArrayList<>();
        if (!rows.isArray()) {
            return out;
        }
        for (JsonNode row : rows) {
            String date = row.path(dateField).asString("");
            if (date.isEmpty()) {
                continue;
            }
            JsonNode close = row.get(closeField);
            if (close == null || !close.isString()) {
                throw parseError("response row has no text field '" + closeField + "'");
            }
            out.add(new DailyClose(parseDate(date, dateField), parsePrice(close.stringValue(), closeField)));
        }
        return out;
    }

    static LocalDate parseDate(String text, String field) {
        try {
            return LocalDate.parse(text, COMPACT_DATE);
        } catch (DateTimeParseException e) {
            throw parseError("field '" + field + "' is not a yyyyMMdd date");
        }
    }

    /** Exact decimal text: positive, at most 4 decimals once trailing zeros are dropped. No rounding anywhere. */
    static BigDecimal parsePrice(String text, String field) {
        if (!PLAIN_PRICE.matcher(text).matches()) {
            throw parseError("field '" + field + "' is not a plain positive decimal");
        }
        String trimmed = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
        BigDecimal price = new BigDecimal(trimmed);
        if (price.signum() <= 0 || price.scale() > MAX_DECIMALS) {
            throw parseError("field '" + field + "' is not positive with at most " + MAX_DECIMALS + " decimals");
        }
        return price;
    }

    static KisException parseError(String message) {
        return new KisException("KIS_PARSE", "KIS response not understood: " + message);
    }
}

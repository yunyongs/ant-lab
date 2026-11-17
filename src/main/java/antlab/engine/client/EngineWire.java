package antlab.engine.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON rules of the engine wire (docs/api/engine.yaml). Parsing is strict on purpose: both services ship from this
 * repository, so an unknown field, a number where a string is promised or a missing required field is contract drift
 * and must fail loudly, not be absorbed.
 */
final class EngineWire {

    static final Pattern MONTH = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");
    static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    static final Pattern SEED = Pattern.compile("^-?[0-9]{1,19}$");
    static final Pattern SHARE = Pattern.compile("^(0(\\.[0-9]{1,12})?|1(\\.0{1,12})?)$");
    static final Pattern RISK_LIMIT = Pattern.compile("^-0\\.[0-9]{1,8}$");
    static final Pattern ASSET = Pattern.compile("^(KR|US):[A-Z0-9.\\-]{1,16}$");
    static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    /** Ratios travel as decimal strings with exactly eight fractional digits. */
    static final int RATIO_SCALE = 8;
    private static final Pattern RATIO_TEXT = Pattern.compile("^-?[0-9]{1,24}\\.[0-9]{8}$");
    private static final BigDecimal MULTIPLE_LIMIT = BigDecimal.TEN.pow(24);

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .addModule(new SimpleModule().addDeserializer(BigDecimal.class, new RatioDeserializer()))
            .build();

    private EngineWire() {
    }

    static <T> T read(Class<T> type, byte[] body) {
        return MAPPER.readValue(body, type);
    }

    static byte[] write(Object request) {
        return MAPPER.writeValueAsBytes(request);
    }

    /** Accepts a JSON string only, never a number, and only the eight-digit plain decimal form. */
    private static final class RatioDeserializer extends ValueDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
            }
            String text = parser.getString();
            if (!RATIO_TEXT.matcher(text).matches()) {
                return (BigDecimal) context.handleWeirdStringValue(BigDecimal.class, "<ratio>", "not an eight-digit decimal");
            }
            return new BigDecimal(text);
        }
    }

    static String match(String name, String value, Pattern pattern) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " does not match " + pattern.pattern());
        }
        return value;
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static <T> List<T> list(List<T> value, String name) {
        return List.copyOf(Objects.requireNonNull(value, name));
    }

    /** A value multiple: not negative and below 10^24 (the wire pattern allows 24 integer digits). */
    static BigDecimal multiple(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0 || value.compareTo(MULTIPLE_LIMIT) >= 0) {
            throw new IllegalArgumentException(name + " is outside the multiple range");
        }
        return value;
    }

    /** A drawdown: from -1 to 0 inclusive. */
    static BigDecimal drawdown(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() > 0 || value.compareTo(BigDecimal.ONE.negate()) < 0) {
            throw new IllegalArgumentException(name + " is outside -1..0");
        }
        return value;
    }

    /** A probability: from 0 to 1 inclusive. */
    static BigDecimal probability(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " is outside 0..1");
        }
        return value;
    }
}

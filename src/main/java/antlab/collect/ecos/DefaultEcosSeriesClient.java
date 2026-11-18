package antlab.collect.ecos;

import antlab.collect.kis.SecretMasker;
import antlab.collect.kis.Sleeper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends the one allowed request; paging, retries and error mapping live here, the allow-list and limits in the client chain. */
final class DefaultEcosSeriesClient implements EcosSeriesClient {

    static final int PAGE_CAP = 20;
    /** A full daily history is a few MB; anything above this is not a statistic answer. */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    static final int MAX_DECIMALS = 8;
    static final List<Duration> RATE_RETRY_WAITS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10));
    static final String NO_DATA = "INFO-200";
    static final String BAD_KEY = "INFO-100";
    static final String TOO_MANY_CALLS = "ERROR-602";

    private static final Logger LOG = LoggerFactory.getLogger(DefaultEcosSeriesClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern STAT_CODE = Pattern.compile("[0-9A-Z]{1,12}");
    private static final Pattern ITEM_CODE = Pattern.compile("[0-9A-Za-z]{1,20}");
    private static final Pattern PLAIN_VALUE = Pattern.compile("-?\\d{1,16}(\\.\\d+)?");
    private static final Pattern RESULT_CODE = Pattern.compile("(ERROR|INFO)-\\d{3}");
    private static final int TOO_MANY_REQUESTS = 429;

    private record Raw(int status, String body) {
    }

    private final RestClient restClient;
    private final EcosProperties properties;
    private final SecretMasker masker;
    private final Sleeper sleeper;
    private final IntSupplier requestCounter;

    DefaultEcosSeriesClient(RestClient restClient, EcosProperties properties, SecretMasker masker, Sleeper sleeper,
            IntSupplier requestCounter) {
        this.restClient = restClient;
        this.properties = properties;
        this.masker = masker;
        this.sleeper = sleeper;
        this.requestCounter = requestCounter;
    }

    @Override
    public int requestsMade() {
        return requestCounter.getAsInt();
    }

    @Override
    public List<EcosObservation> observations(String statCode, String itemCode, EcosCycle cycle, LocalDate from, LocalDate to) {
        if (statCode == null || !STAT_CODE.matcher(statCode).matches() || itemCode == null || !ITEM_CODE.matcher(itemCode).matches()) {
            throw new IllegalArgumentException("an ECOS statistic code is 1-12 capital letters or digits and an item code 1-20 letters or digits");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("the start of the range is after its end");
        }
        List<EcosObservation> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < PAGE_CAP; page++) {
            JsonNode json = fetch(statCode, itemCode, cycle, from, to, offset);
            JsonNode body = json.get("StatisticSearch");
            if (body == null) {
                if (NO_DATA.equals(resultCode(json))) {
                    if (offset == 0) {
                        return out;
                    }
                    throw parseError("no data on a later page than the first");
                }
                throw parseError("the answer has no StatisticSearch element");
            }
            JsonNode rows = body.path("row");
            if (!rows.isArray() || !body.path("list_total_count").isNumber()) {
                throw parseError("the answer has no row list or list_total_count");
            }
            for (JsonNode row : rows) {
                EcosObservation parsed = parseRow(row, statCode, itemCode, cycle);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            offset += rows.size();
            long total = body.path("list_total_count").asLong(0);
            if (offset >= total) {
                return out;
            }
            if (rows.size() == 0) {
                throw parseError("an empty page before the announced count was reached");
            }
        }
        throw new EcosException("PAGE_CAP", "ECOS paging stopped after " + PAGE_CAP + " pages without reaching the last row");
    }

    private JsonNode fetch(String stat, String item, EcosCycle cycle, LocalDate from, LocalDate to, int offset) {
        int rateRetries = 0;
        while (true) {
            Raw raw = execute(restClient.get().uri(builder -> builder
                    .pathSegment(EcosEndpoint.SEGMENT_API, EcosEndpoint.SEGMENT_SEARCH, properties.apiKey(), EcosEndpoint.FORMAT,
                            EcosEndpoint.LANGUAGE, String.valueOf(offset + 1), String.valueOf(offset + properties.pageLimit()), stat,
                            cycle.code(), cycle.write(from), cycle.write(to), item)
                    .build()));
            if (raw.status() >= 300 && raw.status() < 400) {
                throw new EcosException("ECOS_HTTP", describe(raw.status(), null) + ": redirect not followed");
            }
            JsonNode json = parseObject(raw.body());
            if (raw.status() == TOO_MANY_REQUESTS) {
                if (rateRetries >= RATE_RETRY_WAITS.size()) {
                    throw new EcosException("ECOS_RATE_LIMIT", describe(raw.status(), json) + ": rate limit persists");
                }
                Duration wait = RATE_RETRY_WAITS.get(rateRetries++);
                LOG.info("ECOS rate limit hit, waiting {} s before retry {}", wait.toSeconds(), rateRetries);
                sleeper.sleep(wait);
                continue;
            }
            if (raw.status() != 200) {
                throw new EcosException("ECOS_HTTP", describe(raw.status(), json));
            }
            if (json == null) {
                throw new EcosException("ECOS_PARSE", describe(raw.status(), null) + ": body is not a JSON object");
            }
            String result = resultCode(json);
            if (BAD_KEY.equals(result)) {
                throw new EcosException("ECOS_AUTH", describe(raw.status(), json) + ": key not accepted");
            }
            if (TOO_MANY_CALLS.equals(result)) {
                throw new EcosException("ECOS_RATE_LIMIT", describe(raw.status(), json) + ": call limit reached");
            }
            if (result != null && !NO_DATA.equals(result)) {
                throw new EcosException("ECOS_REJECTED", describe(raw.status(), json) + ": request rejected");
            }
            return json;
        }
    }

    private Raw execute(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (status >= 300 && status < 400) {
                    return new Raw(status, "");
                }
                byte[] bytes = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new EcosException("ECOS_PARSE", describe(status, null) + ": response body too large");
                }
                return new Raw(status, new String(bytes, StandardCharsets.UTF_8));
            });
        } catch (EcosException e) {
            throw e;
        } catch (RuntimeException e) {
            // the framework's message carries the request URL, and the URL carries the key
            throw new EcosException("ECOS_HTTP", masker.mask("statistic request failed: " + e.getClass().getSimpleName()));
        }
    }

    /** A row without a value (null or blank) is skipped: ECOS publishes the date but not the number yet. */
    private static EcosObservation parseRow(JsonNode row, String stat, String item, EcosCycle cycle) {
        if (!stat.equals(row.path("STAT_CODE").asString("")) || !item.equals(row.path("ITEM_CODE1").asString(""))) {
            throw parseError("a row belongs to another statistic or item than the one asked for");
        }
        JsonNode time = row.get("TIME");
        if (time == null || !time.isString() || !cycle.matches(time.stringValue())) {
            throw parseError("field 'TIME' does not fit the cycle");
        }
        LocalDate refDate;
        try {
            refDate = cycle.parse(time.stringValue());
        } catch (IllegalArgumentException e) {
            throw parseError("field 'TIME' is not a calendar date");
        }
        if (!row.has("DATA_VALUE")) {
            throw parseError("response row has no field 'DATA_VALUE'");
        }
        JsonNode value = row.get("DATA_VALUE");
        if (value.isNull() || value.isString() && value.stringValue().isBlank()) {
            return null;
        }
        if (!value.isString()) {
            throw parseError("field 'DATA_VALUE' is not text");
        }
        return new EcosObservation(refDate, parseValue(value.stringValue()));
    }

    /** Exact decimal text, at most 8 decimals once trailing zeros are dropped: no rounding anywhere. */
    static BigDecimal parseValue(String text) {
        if (!PLAIN_VALUE.matcher(text).matches()) {
            throw parseError("field 'DATA_VALUE' is not a plain decimal");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw parseError("field 'DATA_VALUE' has more than " + MAX_DECIMALS + " decimals");
        }
        return value;
    }

    private static JsonNode parseObject(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The RESULT code of an answer without data or with an error, or null when the answer has none. Only the shape is kept. */
    private static String resultCode(JsonNode json) {
        if (json == null) {
            return null;
        }
        JsonNode code = json.path("RESULT").path("CODE");
        if (!code.isString()) {
            return null;
        }
        return RESULT_CODE.matcher(code.stringValue()).matches() ? code.stringValue() : "ERROR-000";
    }

    private static EcosException parseError(String message) {
        return new EcosException("ECOS_PARSE", "ECOS response not understood: " + message);
    }

    /** Endpoint name, status and the result code only; the whole text also passes the masker. */
    private String describe(int status, JsonNode json) {
        String code = resultCode(json);
        return masker.mask("GET StatisticSearch HTTP " + status + (code != null ? " result=" + code : ""));
    }
}

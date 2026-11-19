package antlab.collect.fred;

import antlab.collect.kis.SecretMasker;
import antlab.collect.kis.Sleeper;
import antlab.observability.CollectMetrics;
import antlab.observability.CollectMetrics.Source;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
final class DefaultFredSeriesClient implements FredSeriesClient {

    static final int PAGE_CAP = 20;
    /** A full-history vintage answer is a few MB; anything above this is not an observations response. */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    static final int MAX_DECIMALS = 8;
    static final List<Duration> RATE_RETRY_WAITS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static final Logger LOG = LoggerFactory.getLogger(DefaultFredSeriesClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SERIES_ID = Pattern.compile("[A-Z0-9_]{1,25}");
    private static final Pattern PLAIN_VALUE = Pattern.compile("-?\\d{1,16}(\\.\\d+)?");
    private static final String MISSING = ".";
    private static final int TOO_MANY_REQUESTS = 429;

    private record Raw(int status, String body) {
    }

    private final RestClient restClient;
    private final FredProperties properties;
    private final SecretMasker masker;
    private final Sleeper sleeper;
    private final IntSupplier requestCounter;
    private final CollectMetrics metrics;

    DefaultFredSeriesClient(RestClient restClient, FredProperties properties, SecretMasker masker, Sleeper sleeper,
            IntSupplier requestCounter, CollectMetrics metrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.masker = masker;
        this.sleeper = sleeper;
        this.requestCounter = requestCounter;
        this.metrics = metrics;
    }

    @Override
    public int requestsMade() {
        return requestCounter.getAsInt();
    }

    @Override
    public List<FredObservation> observations(String sourceSeriesId, LocalDate observationStart, boolean allVintages) {
        if (sourceSeriesId == null || !SERIES_ID.matcher(sourceSeriesId).matches()) {
            throw new IllegalArgumentException("a FRED series id is 1-25 capital letters, digits or underscores");
        }
        List<FredObservation> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < PAGE_CAP; page++) {
            JsonNode json = fetch(sourceSeriesId, observationStart, allVintages, offset);
            int received;
            long total;
            try {
                JsonNode rows = json.path("observations");
                if (!rows.isArray() || !json.path("count").isNumber()) {
                    throw parseError("the answer has no observations list or count");
                }
                int before = out.size();
                for (JsonNode row : rows) {
                    out.add(parseRow(row));
                }
                received = out.size() - before;
                total = json.path("count").asLong(0);
                if (offset + received < total && received == 0) {
                    throw parseError("an empty page before the announced count was reached");
                }
            } catch (FredException e) {
                // the request was answered, but its content was not understood: that is its final outcome
                metrics.request(Source.FRED, e.code());
                throw e;
            }
            metrics.request(Source.FRED, CollectMetrics.OK);
            offset += received;
            if (offset >= total) {
                return out;
            }
        }
        throw new FredException("PAGE_CAP", "FRED paging stopped after " + PAGE_CAP + " pages without reaching the last row");
    }

    /** Counts every request it sends except the one it returns: the caller counts that one once the page is read. */
    private JsonNode fetch(String seriesId, LocalDate start, boolean allVintages, int offset) {
        int rateRetries = 0;
        while (true) {
            Duration wait;
            try {
                Raw raw = execute(restClient.get().uri(builder -> {
                    builder.path(FredEndpoint.OBSERVATIONS_PATH)
                            .queryParam("series_id", seriesId)
                            .queryParam("api_key", properties.apiKey())
                            .queryParam("file_type", "json")
                            .queryParam("observation_start", start.toString());
                    if (allVintages) {
                        builder.queryParam("realtime_start", FredEndpoint.ALL_VINTAGES_START)
                                .queryParam("realtime_end", FredEndpoint.ALL_VINTAGES_END);
                    }
                    return builder.queryParam("limit", properties.pageLimit()).queryParam("offset", offset).build();
                }));
                if (raw.status() >= 300 && raw.status() < 400) {
                    throw new FredException("FRED_HTTP", describe(raw.status(), null) + ": redirect not followed");
                }
                JsonNode json = parseObject(raw.body());
                if (raw.status() != TOO_MANY_REQUESTS) {
                    return accepted(raw, json);
                }
                if (rateRetries >= RATE_RETRY_WAITS.size()) {
                    throw new FredException("FRED_RATE_LIMIT", describe(raw.status(), json) + ": rate limit persists");
                }
                wait = RATE_RETRY_WAITS.get(rateRetries++);
                metrics.request(Source.FRED, CollectMetrics.RATE_RETRY);
            } catch (FredException e) {
                metrics.request(Source.FRED, e.code());
                throw e;
            }
            LOG.info("FRED rate limit hit, waiting {} s before retry {}", wait.toSeconds(), rateRetries);
            sleeper.sleep(wait);
        }
    }

    private JsonNode accepted(Raw raw, JsonNode json) {
        if (raw.status() != 200) {
            throw new FredException("FRED_HTTP", describe(raw.status(), json));
        }
        if (json == null) {
            throw new FredException("FRED_PARSE", describe(raw.status(), null) + ": body is not a JSON object");
        }
        if (json.has("error_code")) {
            throw new FredException("FRED_REJECTED", describe(raw.status(), json) + ": request rejected");
        }
        return json;
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
                    throw new FredException("FRED_PARSE", describe(status, null) + ": response body too large");
                }
                return new Raw(status, new String(bytes, StandardCharsets.UTF_8));
            });
        } catch (FredException e) {
            throw e;
        } catch (RuntimeException e) {
            // the framework's message carries the request URL, and the URL carries the key
            throw new FredException("FRED_HTTP", masker.mask("observations request failed: " + e.getClass().getSimpleName()));
        }
    }

    private static FredObservation parseRow(JsonNode row) {
        JsonNode value = row.get("value");
        if (value == null || !value.isString()) {
            throw parseError("response row has no text field 'value'");
        }
        return new FredObservation(date(row, "date"), parseValue(value.stringValue()), date(row, "realtime_start"),
                date(row, "realtime_end"));
    }

    private static LocalDate date(JsonNode row, String field) {
        try {
            return LocalDate.parse(row.path(field).asString(""));
        } catch (DateTimeParseException e) {
            throw parseError("field '" + field + "' is not a yyyy-MM-dd date");
        }
    }

    /** Exact decimal text; "." means FRED has no value. At most 8 decimals once trailing zeros are dropped: no rounding anywhere. */
    static BigDecimal parseValue(String text) {
        if (MISSING.equals(text)) {
            return null;
        }
        if (!PLAIN_VALUE.matcher(text).matches()) {
            throw parseError("field 'value' is not a plain decimal");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw parseError("field 'value' has more than " + MAX_DECIMALS + " decimals");
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

    private static FredException parseError(String message) {
        return new FredException("FRED_PARSE", "FRED response not understood: " + message);
    }

    /** Endpoint name, status and the numeric error code only; the whole text also passes the masker. */
    private String describe(int status, JsonNode json) {
        JsonNode code = json == null ? null : json.get("error_code");
        return masker.mask("GET observations HTTP " + status + (code != null && code.isIntegralNumber() ? " error_code=" + code.asLong() : ""));
    }
}

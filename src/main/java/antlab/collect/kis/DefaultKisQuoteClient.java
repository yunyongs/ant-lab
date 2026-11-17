package antlab.collect.kis;

import antlab.collect.PriceBasis;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends the three allowed requests; paging, retries and error mapping live here, the allow-list and limits in the client chain. */
final class DefaultKisQuoteClient implements KisQuoteClient {

    static final int PAGE_CAP = 20;
    /** A daily page is about 30-60 KB; anything above this is not a quote response. */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final int DOMESTIC_PAGE_ROWS = 100;
    static final String RATE_LIMIT_CODE = "EGW00201";
    /** Expired and invalid token codes; assumed from the KIS error list and unverified against a live response. */
    static final Set<String> TOKEN_REJECTED_CODES = Set.of("EGW00123", "EGW00121");
    static final List<Duration> RATE_RETRY_WAITS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    private static final Logger LOG = LoggerFactory.getLogger(DefaultKisQuoteClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TICKER = Pattern.compile("[A-Za-z0-9.]{1,10}");
    private static final Pattern MESSAGE_CODE = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private record Raw(int status, String body) {
    }

    private final RestClient restClient;
    private final KisProperties properties;
    private final KisTokenService tokens;
    private final SecretMasker masker;
    private final Clock clock;
    private final Sleeper sleeper;
    private final IntSupplier requestCounter;

    DefaultKisQuoteClient(RestClient restClient, KisProperties properties, KisTokenService tokens, SecretMasker masker,
            Clock clock, Sleeper sleeper, IntSupplier requestCounter) {
        this.restClient = restClient;
        this.properties = properties;
        this.tokens = tokens;
        this.masker = masker;
        this.clock = clock;
        this.sleeper = sleeper;
        this.requestCounter = requestCounter;
    }

    @Override
    public int requestsMade() {
        return requestCounter.getAsInt();
    }

    @Override
    public List<DailyClose> domesticDaily(String ticker, LocalDate from, LocalDate to, PriceBasis basis) {
        checkArguments(ticker, from, to);
        String adjustment = basis == PriceBasis.ADJUSTED ? "0" : "1"; // the parameter name is inverted: 0 = adjusted prices
        return collect(from, to, DOMESTIC_PAGE_ROWS, cursor -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J");
            params.put("FID_INPUT_ISCD", ticker);
            params.put("FID_INPUT_DATE_1", KisResponseParser.COMPACT_DATE.format(from));
            params.put("FID_INPUT_DATE_2", KisResponseParser.COMPACT_DATE.format(cursor));
            params.put("FID_PERIOD_DIV_CODE", "D");
            params.put("FID_ORG_ADJ_PRC", adjustment);
            return KisResponseParser.rows(quotes(KisEndpoint.DOMESTIC_DAILY, params), "stck_bsop_date", "stck_clpr");
        });
    }

    @Override
    public List<DailyClose> overseasDaily(String ticker, KisExchange exchange, LocalDate from, LocalDate to) {
        checkArguments(ticker, from, to);
        return collect(from, to, 0, cursor -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("AUTH", "");
            params.put("EXCD", exchange.name());
            params.put("SYMB", ticker);
            params.put("GUBN", "0");
            params.put("BYMD", KisResponseParser.COMPACT_DATE.format(cursor));
            params.put("MODP", "1");
            return KisResponseParser.rows(quotes(KisEndpoint.OVERSEAS_DAILY, params), "xymd", "clos");
        });
    }

    private static void checkArguments(String ticker, LocalDate from, LocalDate to) {
        if (ticker == null || !TICKER.matcher(ticker).matches()) {
            throw new IllegalArgumentException("ticker must be 1-10 letters, digits or dots");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    /**
     * Pages backward from {@code to}: the next cursor is the day before the earliest date received. Stops on an empty page,
     * when {@code from} is reached, on a short page, or when a page makes no progress; more than {@link #PAGE_CAP} pages fail.
     */
    private List<DailyClose> collect(LocalDate from, LocalDate to, int fullPageRows, Function<LocalDate, List<DailyClose>> page) {
        Map<LocalDate, DailyClose> byDate = new TreeMap<>();
        LocalDate cursor = to;
        for (int pageNo = 0; pageNo < PAGE_CAP; pageNo++) {
            List<DailyClose> rows = page.apply(cursor);
            if (rows.isEmpty()) {
                return new ArrayList<>(byDate.values());
            }
            LocalDate earliest = rows.getFirst().tradeDate();
            for (DailyClose row : rows) {
                if (row.tradeDate().isBefore(earliest)) {
                    earliest = row.tradeDate();
                }
                if (row.tradeDate().isBefore(from) || row.tradeDate().isAfter(to)) {
                    continue;
                }
                DailyClose known = byDate.putIfAbsent(row.tradeDate(), row);
                if (known != null && known.close().compareTo(row.close()) != 0) {
                    throw KisResponseParser.parseError("two different closes for one trade date");
                }
            }
            LocalDate next = earliest.minusDays(1);
            if (!earliest.isAfter(from) || rows.size() < fullPageRows || !next.isBefore(cursor)) {
                return new ArrayList<>(byDate.values());
            }
            cursor = next;
        }
        throw new KisException("PAGE_CAP", "KIS paging stopped after " + PAGE_CAP + " pages without reaching the start date");
    }

    private JsonNode quotes(KisEndpoint endpoint, Map<String, String> params) {
        boolean reissued = false;
        int rateRetries = 0;
        while (true) {
            String token = tokens.accessToken(this::issueToken);
            Raw raw = execute(endpoint, restClient.get()
                    .uri(builder -> {
                        builder.path(endpoint.path());
                        params.forEach((name, value) -> builder.queryParam(name, value));
                        return builder.build();
                    })
                    .headers(headers -> {
                        headers.set("content-type", "application/json; charset=utf-8");
                        headers.set("authorization", "Bearer " + token);
                        headers.set("appkey", properties.appKey());
                        headers.set("appsecret", properties.appSecret());
                        headers.set("tr_id", endpoint.trId());
                        headers.set("custtype", "P");
                    }));
            failOnRedirect(endpoint, raw);
            JsonNode json = parseObject(raw.body());
            String messageCode = messageCode(json);
            if (RATE_LIMIT_CODE.equals(messageCode)) {
                if (rateRetries >= RATE_RETRY_WAITS.size()) {
                    throw new KisException("KIS_RATE_LIMIT", describe(endpoint, raw.status(), messageCode) + ": rate limit persists");
                }
                Duration wait = RATE_RETRY_WAITS.get(rateRetries++);
                LOG.info("KIS rate limit hit on {}, waiting {} s before retry {}", endpoint, wait.toSeconds(), rateRetries);
                sleeper.sleep(wait);
                continue;
            }
            if (TOKEN_REJECTED_CODES.contains(messageCode)) {
                if (reissued) {
                    throw new KisException("KIS_REJECTED", describe(endpoint, raw.status(), messageCode) + ": token rejected again after reissue");
                }
                reissued = true;
                tokens.reject(token);
                continue;
            }
            if (raw.status() != 200) {
                throw new KisException("KIS_HTTP", describe(endpoint, raw.status(), messageCode));
            }
            if (json == null) {
                throw new KisException("KIS_PARSE", describe(endpoint, raw.status(), messageCode) + ": body is not a JSON object");
            }
            if (!"0".equals(json.path("rt_cd").asString(""))) {
                throw new KisException("KIS_REJECTED", describe(endpoint, raw.status(), messageCode) + ": request rejected");
            }
            return json;
        }
    }

    private StoredToken issueToken() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", properties.appKey());
        body.put("appsecret", properties.appSecret());
        Raw raw = execute(KisEndpoint.TOKEN, restClient.post()
                .uri(builder -> builder.path(KisEndpoint.TOKEN.path()).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(body)));
        failOnRedirect(KisEndpoint.TOKEN, raw);
        JsonNode json = parseObject(raw.body());
        String token = json == null ? "" : json.path("access_token").asString("");
        long ttlSeconds = json == null ? 0 : json.path("expires_in").asLong(0);
        if (raw.status() != 200 || token.isEmpty() || ttlSeconds <= 0) {
            throw new KisException("KIS_REJECTED", describe(KisEndpoint.TOKEN, raw.status(), messageCode(json)) + ": no usable token issued");
        }
        masker.register(token);
        return new StoredToken(token, clock.instant().getEpochSecond() + ttlSeconds);
    }

    private Raw execute(KisEndpoint endpoint, RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                boolean redirect = status >= 300 && status < 400;
                if (redirect) {
                    return new Raw(status, "");
                }
                byte[] bytes = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new KisException("KIS_PARSE", describe(endpoint, status, "") + ": response body too large");
                }
                return new Raw(status, new String(bytes, StandardCharsets.UTF_8));
            });
        } catch (KisException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KisException("KIS_HTTP", masker.mask(endpoint + " request failed: " + e.getClass().getSimpleName()));
        }
    }

    private void failOnRedirect(KisEndpoint endpoint, Raw raw) {
        if (raw.status() >= 300 && raw.status() < 400) {
            throw new KisException("KIS_HTTP", describe(endpoint, raw.status(), "") + ": redirect not followed");
        }
    }

    private static JsonNode parseObject(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String messageCode(JsonNode json) {
        String code = json == null ? "" : json.path("msg_cd").asString("");
        return MESSAGE_CODE.matcher(code).matches() ? code : "";
    }

    /** Method, endpoint name, status and the strictly shaped message code; the whole text also passes the masker. */
    private String describe(KisEndpoint endpoint, int status, String messageCode) {
        return masker.mask(endpoint.method() + " " + endpoint + " HTTP " + status
                + (messageCode.isEmpty() ? "" : " msg_cd=" + messageCode));
    }
}

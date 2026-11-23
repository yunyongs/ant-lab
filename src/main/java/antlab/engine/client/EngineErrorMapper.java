package antlab.engine.client;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns what the engine said, or failed to say, into one Java outcome. Rules: the engine's {@code detail} text is
 * never forwarded or logged (only the code, the status and field names are), a code that arrives with the wrong HTTP
 * status is treated as contract drift, and nothing is retried here.
 */
final class EngineErrorMapper {

    static final String INTERNAL_MESSAGE = "The engine call failed.";
    static final String PATH_TOO_LONG_MESSAGE = "지금 재생 구간으로는 요청한 길이의 경로를 만들 수 없다";

    private static final Logger LOG = LoggerFactory.getLogger(EngineErrorMapper.class);
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,80}");
    private static final int MAX_LOGGED_FIELDS = 10;

    private enum Outcome { INTERNAL, PATH_TOO_LONG, HISTORY_UNAVAILABLE, BUSY, LABELS_MISSING, REFUSED }

    private record Rule(int status, String logKey, Outcome outcome) {
    }

    private static final Map<EngineErrorCode, Rule> RULES = new EnumMap<>(EngineErrorCode.class);

    static {
        // Java built the request, so a 400 is a Java bug; a 401 is a deployment fault.
        RULES.put(EngineErrorCode.INVALID_ARGUMENT, new Rule(400, "engine.invalid_request", Outcome.INTERNAL));
        RULES.put(EngineErrorCode.UNAUTHENTICATED, new Rule(401, "engine.unauthenticated", Outcome.INTERNAL));
        // Java checked the pool before the call, so the engine's view of the history disagrees with Java's.
        RULES.put(EngineErrorCode.POOL_NOT_FOUND, new Rule(404, "engine.view_mismatch", Outcome.INTERNAL));
        RULES.put(EngineErrorCode.NOT_FOUND, new Rule(404, "engine.route_missing", Outcome.INTERNAL));
        RULES.put(EngineErrorCode.METHOD_NOT_ALLOWED, new Rule(405, "engine.route_missing", Outcome.INTERNAL));
        RULES.put(EngineErrorCode.PATH_TOO_LONG, new Rule(422, "engine.path_too_long", Outcome.PATH_TOO_LONG));
        RULES.put(EngineErrorCode.HISTORY_UNAVAILABLE, new Rule(503, "engine.history_unavailable", Outcome.HISTORY_UNAVAILABLE));
        RULES.put(EngineErrorCode.ENGINE_BUSY, new Rule(503, "engine.busy", Outcome.BUSY));
        RULES.put(EngineErrorCode.REGIME_DATA_INVALID, new Rule(422, "engine.regime_data_invalid", Outcome.INTERNAL));
        // A data state (too few stored regime labels for m2-sbc6-v1), not the player's fault; the engine's counts stay unlogged.
        RULES.put(EngineErrorCode.REGIME_LABELS_MISSING, new Rule(422, "engine.labels_missing", Outcome.LABELS_MISSING));
        // Only POST /v1/manager-parts answers these three: deterministic refusals of one season that the caller records.
        RULES.put(EngineErrorCode.REFERENCE_MISMATCH, new Rule(409, "engine.reference_mismatch", Outcome.REFUSED));
        RULES.put(EngineErrorCode.FEED_INPUT, new Rule(422, "engine.feed_input", Outcome.REFUSED));
        RULES.put(EngineErrorCode.NO_REFERENCE, new Rule(422, "engine.no_reference", Outcome.REFUSED));
        RULES.put(EngineErrorCode.INTERNAL_ERROR, new Rule(500, "engine.bad_response", Outcome.INTERNAL));
    }

    private EngineErrorMapper() {
    }

    /** The codes this mapper has a rule for; the contract test compares it with the yaml enum. */
    static Set<EngineErrorCode> knownCodes() {
        return Set.copyOf(RULES.keySet());
    }

    static RuntimeException fromProblem(String operation, int httpStatus, EngineProblem problem) {
        Rule rule = RULES.get(problem.code());
        if (rule == null || rule.status() != httpStatus || problem.status() != httpStatus) {
            return badResponse(operation, httpStatus);
        }
        return switch (rule.outcome()) {
            case INTERNAL -> {
                LOG.error("{} operation={} status={}{}", rule.logKey(), operation, httpStatus, fieldNames(problem));
                yield new DomainException(ErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE);
            }
            case PATH_TOO_LONG -> new DomainException(ErrorCode.PATH_TOO_LONG, PATH_TOO_LONG_MESSAGE);
            case HISTORY_UNAVAILABLE -> unavailable(rule.logKey(), operation, EngineUnavailableException.Reason.HISTORY_UNAVAILABLE);
            case BUSY -> unavailable(rule.logKey(), operation, EngineUnavailableException.Reason.BUSY);
            case LABELS_MISSING -> unavailable(rule.logKey(), operation, EngineUnavailableException.Reason.LABELS_MISSING);
            case REFUSED -> {
                LOG.warn("{} operation={}", rule.logKey(), operation);
                yield new EngineRefusedException(EngineRefusedException.Reason.valueOf(problem.code().name()));
            }
        };
    }

    /** The engine and the api read different replay starts; both values are settings, not data, so they may be logged. */
    static RuntimeException replayStartMismatch(String operation, LocalDate api, LocalDate engine) {
        LOG.error("engine.replay_start_mismatch operation={} api={} engine={}", operation, api, engine);
        return new DomainException(ErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE);
    }

    /** A body or status the contract does not allow: wrong media type, schema violation, unknown code, redirect. */
    static RuntimeException badResponse(String operation, int httpStatus) {
        LOG.error("engine.bad_response operation={} status={}", operation, httpStatus);
        return new DomainException(ErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE);
    }

    static RuntimeException notConfigured(String operation) {
        return unavailable("engine.not_configured", operation, EngineUnavailableException.Reason.NOT_CONFIGURED);
    }

    /** A transport failure; only the exception class is logged, because its message may hold a URL. */
    static RuntimeException transport(String operation, Throwable failure) {
        boolean timeout = false;
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                // never connected: the same as "refused" for the log key (a read timeout means the engine took the request)
                return unavailable("engine.unreachable", operation, EngineUnavailableException.Reason.UNREACHABLE);
            }
            timeout |= t instanceof HttpTimeoutException || t instanceof SocketTimeoutException;
        }
        if (timeout) {
            return unavailable("engine.timeout", operation, EngineUnavailableException.Reason.TIMEOUT);
        }
        return unavailable("engine.unreachable", operation, EngineUnavailableException.Reason.UNREACHABLE);
    }

    private static RuntimeException unavailable(String logKey, String operation, EngineUnavailableException.Reason reason) {
        LOG.warn("{} operation={}", logKey, operation);
        return new EngineUnavailableException(reason);
    }

    private static String fieldNames(EngineProblem problem) {
        List<String> names = problem.errors().stream().map(EngineProblem.FieldViolation::field)
                .filter(field -> SAFE_FIELD.matcher(field).matches()).limit(MAX_LOGGED_FIELDS).toList();
        return names.isEmpty() ? "" : " fields=" + names;
    }
}

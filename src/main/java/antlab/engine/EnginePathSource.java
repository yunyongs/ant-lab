package antlab.engine;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.engine.client.EngineClient;
import antlab.engine.client.EnginePathRequest;
import antlab.engine.client.EnginePathResult;
import antlab.engine.client.EngineProperties;
import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Season paths from the Python engine (antlab.engine.path-source=engine). The engine answers with months; this class
 * turns them into {@code month_block} ids of the same database and checks the answer before anything is stored: the
 * engine version, the history digest (both sides must have read the same months), that every month exists, and the
 * path rules. The months stay in this method: only ids and the version leave it. A failed check is a bug or a view
 * mismatch, answered as 500 with the rule name in the log and never a month.
 */
public final class EnginePathSource implements PathSource {

    private static final Logger LOG = LoggerFactory.getLogger(EnginePathSource.class);

    private final EngineClient client;
    private final MonthBlockRepository monthBlocks;
    private final String engineVersion;

    public EnginePathSource(EngineClient client, EngineProperties properties, MonthBlockRepository monthBlocks) {
        this.client = client;
        this.monthBlocks = monthBlocks;
        this.engineVersion = properties.pathVersion();
    }

    @Override
    public GeneratedPath generate(PathRequest request) {
        requireAllowedLength(request.length());
        List<ReplayMonth> replayable = monthBlocks.findReplayMonths(
                SimpleBootstrap.REPLAY_START, SimpleBootstrap.replayUpperBound(request.baseDate()));

        EnginePathResult result = client.createPath(
                EnginePathRequest.of(engineVersion, request.seed(), request.length(), request.baseDate()));

        if (!engineVersion.equals(result.engineVersion())) {
            throw mismatch("engine.version_mismatch", "engine version differs from the requested one");
        }
        String expectedDigest = HistoryDigest.of(engineVersion,
                replayable.stream().map(ReplayMonth::monthStart).toList(), request.baseDate());
        if (!expectedDigest.equals(result.historyDigest())) {
            throw mismatch("engine.view_mismatch", "engine history differs from the database history");
        }

        List<LocalDate> pathMonths = result.months().stream().map(EnginePathSource::parse).toList();
        List<String> violations = violations(pathMonths, request);
        if (!violations.isEmpty()) {
            throw mismatch("engine.path_rule", "engine path breaks " + violations);
        }

        Map<LocalDate, Long> idByMonth = new HashMap<>();
        for (ReplayMonth m : replayable) {
            idByMonth.put(m.monthStart(), m.monthBlockId());
        }
        List<Long> ids = new ArrayList<>(pathMonths.size());
        for (LocalDate month : pathMonths) {
            Long id = idByMonth.get(month);
            if (id == null) {
                throw mismatch("engine.month_missing", "engine month is not in the database history");
            }
            ids.add(id);
        }
        return new GeneratedPath(ids, engineVersion);
    }

    /** Same length policy as the in-process bootstrap, decided before any call. */
    private static void requireAllowedLength(int length) {
        if (length > SimpleBootstrap.MAX_LENGTH) {
            throw new DomainException(ErrorCode.PATH_TOO_LONG, "경로 길이 " + length + "턴은 만들 수 없다(가능: 12·24·60)");
        }
        if (!SimpleBootstrap.ALLOWED_LENGTHS.contains(length)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "경로 길이는 12·24·60 중 하나다");
        }
    }

    /**
     * p0: every rule including the calendar and the 6-month blocks. m2: count, range and reuse only; its calendar
     * tiers (aligned, near, free) are the engine's own rules, so the calendar rule S4 does not apply.
     */
    private List<String> violations(List<LocalDate> months, PathRequest request) {
        if (SimpleBootstrap.ENGINE_VERSION.equals(engineVersion)) {
            return PathRules.violations(months, request.baseDate(), request.length(), SimpleBootstrap.BLOCK_LENGTH);
        }
        return PathRules.violations(months, request.baseDate(), request.length()).stream()
                .filter(rule -> !"S4".equals(rule)).toList();
    }

    private static LocalDate parse(String text) {
        try {
            return LocalDate.parse(text + "-01");
        } catch (DateTimeParseException e) {
            throw mismatch("engine.bad_month", "engine month is not a calendar month");
        }
    }

    private static IllegalStateException mismatch(String logKey, String message) {
        LOG.error("{}", logKey);
        return new IllegalStateException(message);
    }
}

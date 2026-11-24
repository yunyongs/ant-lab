package antlab.leaderboard;

import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineManagerPartsRequest;
import antlab.engine.client.EngineManagerPartsResult;
import antlab.engine.client.EngineProperties;
import antlab.engine.client.EngineRefusedException;
import antlab.engine.client.EngineUnavailableException;
import antlab.scoring.SeasonScoreService;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Computes and stores the manager parts of finished seasons, earliest finish first. <b>No transaction here</b>: each season
 * is read in one read-only snapshot ({@link ManagerPartsReader}), the engine is called with no transaction open, and the
 * row is one insert. The scheduled job and the administrator run share one PostgreSQL advisory lock, so a second run skips
 * instead of paying the engine for the same season.
 *
 * <p>Each season is scored first when it has no score yet, one season per attempt and in the member's finish order: a
 * season whose earlier season still has no score waits without an engine call, so neither the run limit nor the job's
 * wait for that earlier season is bypassed.
 *
 * <p>Three failure classes: a deterministic engine refusal stores an exclusion row so the queue moves on; an outage
 * (unreachable, busy, timeout), while scoring or while asking for the parts, stores nothing and stops the run; anything
 * else (a bad answer, a value that does not fit the wire, a refused insert, a score that failed) is a failure of that
 * season only, which the job backs off from. A deployment setting that breaks "same costs as the reference" refuses the
 * whole run.
 */
@Service
public class ManagerPartsService {

    private static final Logger LOG = LoggerFactory.getLogger(ManagerPartsService.class);

    static final String LOCK_KEY = "season_manager_parts.run";
    private static final String TRY_LOCK = "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK = "SELECT pg_advisory_unlock(hashtextextended(?, 0))";
    static final int REFERENCE_SIZE = 200;
    static final int MAX_VERSION_LENGTH = 20;
    /** A cold 60-turn reference build is a few seconds of engine CPU plus one history read (E1 review N9). */
    public static final Duration MIN_ENGINE_TIMEOUT = Duration.ofSeconds(30);
    public static final int MAX_LIMIT = 20;
    /**
     * Seasons one run may pass over because an earlier season of their member has no score. The queue already leaves
     * those out, so this only bounds the work under the lock if that rule and the score order ever disagree; a constant
     * rather than a multiple of the limit, because the job runs with a limit of 1.
     */
    static final int MAX_WAITING_SKIPS = 20;

    public enum Outcome { RANKED, EXCLUDED, ALREADY_STORED, FAILED }

    public record Attempt(long seasonId, Outcome outcome) {
    }

    /** @param pending finished seasons still without a row after the run */
    public record Run(ManagerPartsRunStatus status, List<Attempt> attempts, int pending) {

        public Run {
            attempts = List.copyOf(attempts);
        }

        public int count(Outcome outcome) {
            return (int) attempts.stream().filter(a -> a.outcome() == outcome).count();
        }
    }

    private final DataSource dataSource;
    private final ManagerPartsQuery queue;
    private final ManagerPartsReader reader;
    private final ManagerPartsWriter writer;
    private final SeasonScoreService scoring;
    private final EngineClient engine;
    private final String refusal;

    public ManagerPartsService(DataSource dataSource, ManagerPartsQuery queue, ManagerPartsReader reader,
            ManagerPartsWriter writer, SeasonScoreService scoring, EngineClient engine, EngineProperties properties,
            @Value("${antlab.game.single-asset-limit:#{null}}") BigDecimal singleAssetLimit) {
        this.dataSource = dataSource;
        this.queue = queue;
        this.reader = reader;
        this.writer = writer;
        this.scoring = scoring;
        this.engine = engine;
        this.refusal = refusal(properties.regimeTimeout(), singleAssetLimit);
    }

    /**
     * Why no run may call the engine under these settings, or null. Reference bots play without a single-asset limit, so a
     * deployment with one would not be ranked against the same costs (E1 review N6); a timeout below
     * {@link #MIN_ENGINE_TIMEOUT} would turn every cold reference build into an outage that stores nothing.
     */
    static String refusal(Duration partsTimeout, BigDecimal singleAssetLimit) {
        if (singleAssetLimit != null) {
            return "single_asset_limit";
        }
        if (partsTimeout.compareTo(MIN_ENGINE_TIMEOUT) < 0) {
            return "engine_timeout_below_minimum";
        }
        return null;
    }

    /** Pending seasons without a row, for the job's quiet check and the answer of a refused run. */
    public int pending() {
        return queue.pendingCount(EngineManagerPartsRequest.REFERENCE_VERSION);
    }

    /**
     * Stores rows for up to {@code limit} pending seasons, earliest finish first, leaving out {@code skip}. A season that
     * fails is not tried again in the same run. Never throws for an engine or season failure.
     */
    public Run run(int limit, Collection<Long> skip) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("manager parts run outside any transaction only: no connection may wait on the engine");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
        if (refusal != null) {
            LOG.warn("manager_parts.refused reason={}", refusal);
            return new Run(ManagerPartsRunStatus.REFUSED, List.of(), pending());
        }
        Connection lock;
        try {
            lock = dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("manager parts lock unavailable", e);
        }
        // the session lock lives as long as the connection: one that may still hold it never goes back to the pool
        boolean lockFree = false;
        try {
            lock.setAutoCommit(true);
            if (!advisory(lock, TRY_LOCK)) {
                lockFree = true;
                LOG.info("manager_parts.skipped reason=already_running");
                return new Run(ManagerPartsRunStatus.ALREADY_RUNNING, List.of(), pending());
            }
            try {
                return runLocked(limit, skip);
            } finally {
                lockFree = released(lock);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("manager parts lock unavailable", e);
        } finally {
            if (lockFree) {
                closeQuietly(lock);
            } else {
                discard(lock);
            }
        }
    }

    /** Runs the unlock; false when it failed or found no lock, so the lock state of the session is unknown. */
    private boolean released(Connection lock) {
        try {
            if (unlock(lock)) {
                return true;
            }
            LOG.error("manager_parts.unlock_failed reason=not_held");
        } catch (SQLException | RuntimeException e) {
            LOG.error("manager_parts.unlock_failed reason={}", e.getClass().getSimpleName());
        }
        return false;
    }

    /** The unlock statement; a seam for the test that makes it fail. */
    boolean unlock(Connection lock) throws SQLException {
        return advisory(lock, UNLOCK);
    }

    /**
     * Ends the session of a connection that may still hold the lock. A pooled connection is evicted, which closes it in
     * the pool (closing the handle again would only make the pool warn); anything else is aborted and closed.
     */
    private void discard(Connection connection) {
        try {
            // Hikari ignores evictConnection for a handle it did not issue (a proxying DataSource), so check the class.
            if (connection.getClass().getName().startsWith("com.zaxxer.hikari.")
                    && dataSource.isWrapperFor(HikariDataSource.class)) {
                dataSource.unwrap(HikariDataSource.class).evictConnection(connection);
                return;
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("manager_parts.evict_failed reason={}", e.getClass().getSimpleName());
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            LOG.error("manager_parts.abort_failed reason={}", e.getClass().getSimpleName());
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("manager_parts.close_failed reason={}", e.getClass().getSimpleName());
        }
    }

    private static boolean advisory(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private Run runLocked(int limit, Collection<Long> skip) {
        List<Attempt> attempts = new ArrayList<>();
        Set<Long> tried = new HashSet<>(skip);
        Set<Long> waitingMembers = new HashSet<>();
        int waitingSkips = 0;
        ManagerPartsRunStatus status = ManagerPartsRunStatus.DONE;
        while (attempts.size() < limit) {
            if (waitingSkips >= MAX_WAITING_SKIPS) {
                // the rest stays pending for the next run
                LOG.warn("manager_parts.scan_budget_spent skipped={}", waitingSkips);
                break;
            }
            Optional<ManagerPartsQuery.Pending> next = queue.next(EngineManagerPartsRequest.REFERENCE_VERSION, tried,
                    waitingMembers);
            if (next.isEmpty()) {
                break;
            }
            ManagerPartsQuery.Pending season = next.get();
            tried.add(season.seasonId());
            Outcome outcome;
            try {
                outcome = one(season);
            } catch (Outage outage) {
                LOG.warn("manager_parts.pending seasonId={} reason={}", season.seasonId(), outage.reason);
                status = ManagerPartsRunStatus.ENGINE_UNAVAILABLE;
                break;
            }
            if (outcome == null) {
                // an earlier season of the member has no score: the member's later seasons wait too, without a call
                LOG.info("manager_parts.skipped seasonId={} reason=earlier_season_unscored", season.seasonId());
                waitingMembers.add(season.memberId());
                waitingSkips++;
                continue;
            }
            attempts.add(new Attempt(season.seasonId(), outcome));
        }
        return new Run(status, attempts, pending());
    }

    /** The engine could not answer, while scoring or while asking for the parts: the run stops and nothing is stored. */
    private static final class Outage extends RuntimeException {
        private final String reason;

        Outage(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /**
     * One season, or null when it waits for an earlier season of its member. An outage propagates as {@link Outage}; every
     * other failure is this season's {@link Outcome#FAILED}.
     */
    private Outcome one(ManagerPartsQuery.Pending season) {
        long id = season.seasonId();
        SeasonScoreService.Step scored;
        try {
            scored = scoring.ensureSeason(season.memberId(), id);
        } catch (RuntimeException e) {
            LOG.warn("manager_parts.failed seasonId={} reason=score_{}", id, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
        switch (scored) {
            case UNAVAILABLE -> throw new Outage("score_engine_unavailable");
            case BLOCKED -> {
                return null;
            }
            case FAILED -> {
                LOG.warn("manager_parts.failed seasonId={} reason=score_failed", id);
                return Outcome.FAILED;
            }
            case SCORED -> {
                // go on
            }
        }
        try {
            Optional<ManagerPartsBasis> read = reader.read(id);
            if (read.isEmpty()) {
                LOG.warn("manager_parts.failed seasonId={} reason=score_missing", id);
                return Outcome.FAILED;
            }
            ManagerPartsBasis basis = read.get();
            ManagerPartsExclusion known = ManagerPartsRequestFactory.knownExclusion(basis.score().ratingExclusion(),
                    basis.settings().pathLength());
            if (known != null) {
                return store(ManagerPartsRequestFactory.excluded(basis, known));
            }
            EngineManagerPartsRequest request = ManagerPartsRequestFactory.request(basis);
            EngineManagerPartsResult answer;
            try {
                answer = engine.createManagerParts(request);
            } catch (EngineRefusedException refused) {
                return store(ManagerPartsRequestFactory.excluded(basis, exclusionOf(refused.reason())));
            }
            String rule = answerProblem(request, answer);
            if (rule != null) {
                LOG.error("manager_parts.bad_answer seasonId={} rule={}", id, rule);
                return Outcome.FAILED;
            }
            return store(ManagerPartsRequestFactory.ranked(basis, answer));
        } catch (EngineUnavailableException outage) {
            throw new Outage(outage.reason().name());
        } catch (RuntimeException e) {
            // the class name only: a database error echoes the failing row
            LOG.warn("manager_parts.failed seasonId={} reason={}", id, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    static ManagerPartsExclusion exclusionOf(EngineRefusedException.Reason reason) {
        return reason == EngineRefusedException.Reason.REFERENCE_MISMATCH ? ManagerPartsExclusion.REFERENCE_MISMATCH
                : ManagerPartsExclusion.NO_REFERENCE;
    }

    /**
     * What the client records cannot check before a row is stored (erd §17 F3), or null when the answer may be stored: the
     * reference is the one asked for and has 200 bots, the version texts fit their columns, and a risk-item percentile is
     * there exactly when the season sent a risk item.
     */
    static String answerProblem(EngineManagerPartsRequest request, EngineManagerPartsResult answer) {
        if (!request.referenceVersion().equals(answer.referenceVersion())) {
            return "reference_version";
        }
        if (answer.referenceSize() != REFERENCE_SIZE) {
            return "reference_size";
        }
        String engineVersion = answer.engineVersion();
        if (engineVersion.isBlank() || !engineVersion.equals(engineVersion.strip()) || engineVersion.length() > MAX_VERSION_LENGTH) {
            return "engine_version";
        }
        if (answer.botVersion().length() > MAX_VERSION_LENGTH) {
            return "bot_version";
        }
        if ((answer.percentiles().riskItem() == null) != (request.team().riskScore() == null)) {
            return "risk_item";
        }
        return null;
    }

    private Outcome store(SeasonManagerParts row) {
        if (!writer.insert(row)) {
            return Outcome.ALREADY_STORED;
        }
        LOG.info("manager_parts.written seasonId={} exclusion={}", row.seasonId(), row.exclusion());
        return row.exclusion() == null ? Outcome.RANKED : Outcome.EXCLUDED;
    }
}

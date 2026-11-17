package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.observability.GameMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Season abandon (MVP-14): marks the season at its current turn, then runs the remaining turns with no trades through
 * the normal turn path, one transaction per turn ({@link TurnFacade#advanceAbandoned}), until the season is finished or
 * the per-request cap is reached. <b>No transaction on this method</b>, for the same reason as {@link TurnFacade}: each
 * turn must commit (or roll back and be logged) on its own. The same request both starts and continues an abandon, so
 * a request that stopped midway is finished by repeating it.
 *
 * <p>The first failure of an auto turn ends the request (no retry inside one request), so one click spends one of the
 * failed attempts that halt a season.
 */
@Service
public class SeasonAbandonService {

    private static final Logger log = LoggerFactory.getLogger(SeasonAbandonService.class);

    private final SeasonAbandonMarker marker;
    private final TurnFacade turns;
    private final AbandonProperties properties;
    private final GameMetrics metrics;

    public SeasonAbandonService(SeasonAbandonMarker marker, TurnFacade turns, AbandonProperties properties,
            GameMetrics metrics) {
        this.marker = marker;
        this.turns = turns;
        this.properties = properties;
        this.metrics = metrics;
    }

    public AbandonResultDto abandon(long seasonId, int expectedTurnNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("포기 서비스는 트랜잭션 밖에서만 부른다 — 턴마다 따로 커밋해야 한다");
        }
        SeasonAbandonMarker.State state = marker.mark(seasonId, expectedTurnNo);
        if (state.markedNow()) {
            // After the mark committed: the counter ignores transactions.
            metrics.seasonAbandoned(state.notStarted());
            log.info("시즌 포기: seasonId={} abandonedTurnNo={} notStarted={}", seasonId, state.abandonedTurnNo(),
                    state.notStarted());
        }
        int advanced = 0;
        for (int iteration = 0; iteration < properties.maxTurnsPerRequest()
                && state.status() == SeasonStatus.ABANDONING; iteration++) {
            try {
                turns.advanceAbandoned(seasonId, state.currentTurnNo());
                advanced++;
            } catch (DomainException moved) {
                // A parallel request advanced the season first: read it again and carry on from there.
                if (moved.getCode() != ErrorCode.TURN_MISMATCH && moved.getCode() != ErrorCode.SEASON_NOT_ACTIVE) {
                    throw moved;
                }
            }
            state = marker.read(seasonId, state.memberKind());
        }
        return new AbandonResultDto(seasonId, state.status(), state.currentTurnNo(), state.pathLength(),
                state.abandonedTurnNo(), state.notStarted(), advanced, state.pathLength() - state.currentTurnNo());
    }
}

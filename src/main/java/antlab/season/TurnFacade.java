package antlab.season;

import antlab.common.error.DomainException;
import antlab.observability.GameMetrics;
import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import java.sql.SQLException;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 턴 전환의 바깥 문 — <b>트랜잭션이 없다</b>(클래스·메서드 모두 {@code @Transactional} 없음). {@code db/23} 의
 * {@code ROLLBACK → recon_log → COMMIT → RAISE} 의 Java 짝이다. 호출자가 트랜잭션 안이면 아무것도 하지 않고
 * {@code IllegalStateException} 을 던진다.
 *
 * <p>{@code TurnService.advance} 는 다른 빈이라 호출이 프록시를 거치고, 대사 불일치 예외가 나면 프록시가 안쪽 트랜잭션을
 * 끝까지 롤백한 <em>뒤</em> 예외가 여기로 돌아온다(행·잠금 모두 사라진 상태). 그다음 기록기를 부르면 바깥 트랜잭션이 없으므로
 * 기록기의 {@code REQUIRED} 가 새 트랜잭션을 연다. {@code REQUIRES_NEW} 를 안쪽에서 쓰면 바깥이 쥔 시즌 잠금을 스스로 기다리게 된다.
 *
 * <p>기록의 23505(같은 시도 번호 유일 위반)는 트랜잭션을 망가뜨리므로 같은 트랜잭션에서 다시 읽을 수 없다. 여기는 바깥 트랜잭션이
 * 없어 호출마다 새 트랜잭션이므로 새로 읽고 다시 기록한다(최대 3번, {@code db/22} 의 {@code recon_log} 와 같은 상한).
 * 시즌 잠금 아래에서는 도달하지 않는 안전망이다.
 *
 * <p>기록이 재시도 밖의 이유로 실패하면 기록 예외를 던지고 원래 대사 불일치 예외는 {@code suppressed} 로 붙인다(500).
 * 상태는 롤백된 그대로다. 전제 오류({@code TURN_MISMATCH} 등)는 기록하지 않고 그대로 올린다.
 */
@Service
public class TurnFacade {

    private static final Logger log = LoggerFactory.getLogger(TurnFacade.class);

    /** 시도 번호 유일 제약 이름({@code erd} 6.3). 이것만 재시도 대상이다. */
    static final String ATTEMPT_UNIQUE_CONSTRAINT = "uq_reconciliation_log_season_turn_attempt_code";
    static final int MAX_RECORD_TRIES = 3;

    private final TurnService turns;
    private final ReconciliationLogWriter logWriter;
    private final GameMetrics metrics;

    public TurnFacade(TurnService turns, ReconciliationLogWriter logWriter, GameMetrics metrics) {
        this.turns = turns;
        this.logWriter = logWriter;
        this.metrics = metrics;
    }

    public TurnResultDto advance(long seasonId, int expectedTurnNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("턴 전환 퍼사드는 트랜잭션 밖에서만 부른다 — 안에서 부르면 롤백 뒤 기록이 바깥 롤백과 함께 사라진다");
        }
        TurnResultDto result;
        try {
            result = turns.advance(seasonId, expectedTurnNo);
        } catch (ReconciliationFailedException failed) {
            metrics.turnRejected(failed.getCode());
            metrics.mismatchRules((int) failed.getResults().stream()
                    .filter(r -> r.status() == ReconciliationStatus.MISMATCH).count());
            recordAfterRollback(failed);
            throw failed;
        } catch (DomainException rejected) {
            metrics.turnRejected(rejected.getCode());
            throw rejected;
        }
        metrics.turnCommitted();
        return result;
    }

    private void recordAfterRollback(ReconciliationFailedException failed) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RECORD_TRIES; attempt++) {
            try {
                int attemptNo = logWriter.record(failed.getSeasonId(), failed.getTurnNo(), failed.getResults());
                log.warn("대사 불일치 기록: seasonId={} turnNo={} attemptNo={} rules={}", failed.getSeasonId(),
                        failed.getTurnNo(), attemptNo, mismatchedRules(failed));
                return;
            } catch (DataIntegrityViolationException e) {
                if (!isAttemptCollision(e)) {
                    e.addSuppressed(failed);
                    throw e;
                }
                last = e; // 시도 번호가 겹쳤다 — 새 트랜잭션으로 다시 읽어 다시 기록한다
            } catch (RuntimeException e) {
                e.addSuppressed(failed);
                throw e;
            }
        }
        IllegalStateException exhausted = new IllegalStateException("시도 번호를 " + MAX_RECORD_TRIES + "번 시도해도 정하지 못했다", last);
        exhausted.addSuppressed(failed);
        throw exhausted;
    }

    private static String mismatchedRules(ReconciliationFailedException failed) {
        return failed.getResults().stream().filter(r -> r.status() != ReconciliationStatus.MATCH)
                .map(RuleResult::ruleNo).map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 원인 사슬에 SQLState 23505 이고 제약 이름이 시도 번호 유일 제약인 예외가 있는가. 다른 제약 위반은 재시도하지 않는다. */
    static boolean isAttemptCollision(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && "23505".equals(cve.getSQLState()) && ATTEMPT_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState()) && sql.getMessage() != null
                    && sql.getMessage().toLowerCase().contains(ATTEMPT_UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}

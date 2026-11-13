package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.scoring.CheckCode;
import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import antlab.season.TurnFacade;
import antlab.season.TurnService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnFacadeMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TurnService turns = mock(TurnService.class);
    private final ReconciliationLogWriter writer = mock(ReconciliationLogWriter.class);
    private final TurnFacade facade = new TurnFacade(turns, writer, new GameMetrics(registry));

    private double turn(String outcome, String code) {
        var c = registry.find("antlab.turn.advance").tags("outcome", outcome, "code", code).counter();
        return c == null ? 0 : c.count();
    }

    private double mismatchRules() {
        return registry.find("antlab.reconciliation.mismatch.rules").counter().count();
    }

    private static ReconciliationFailedException failedWithTwoMismatches() {
        List<RuleResult> rows = new ArrayList<>();
        CheckCode[] codes = CheckCode.values();
        for (int i = 1; i <= 17; i++) {
            boolean mismatch = i == 17 || i == 15;
            rows.add(new RuleResult(i, codes[i - 1], mismatch ? ReconciliationStatus.MISMATCH : ReconciliationStatus.MATCH,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, mismatch ? 1 : 0, null, null));
        }
        return new ReconciliationFailedException(1L, 1, rows);
    }

    @Test
    void 커밋된_전환은_committed_none을_올린다() {
        facade.advance(1L, 0);
        assertThat(turn("committed", "none")).isEqualTo(1);
        assertThat(turn("rejected", "RECONCILIATION_FAILED")).isZero();
    }

    @Test
    void 대사_불일치는_rejected와_불일치_규칙_수를_올리고_committed는_올리지_않는다() {
        when(turns.advance(anyLong(), anyInt())).thenThrow(failedWithTwoMismatches());
        assertThatThrownBy(() -> facade.advance(1L, 0)).isInstanceOf(ReconciliationFailedException.class);
        assertThat(turn("rejected", "RECONCILIATION_FAILED")).isEqualTo(1);
        assertThat(turn("committed", "none")).isZero();
        assertThat(mismatchRules()).isEqualTo(2);
    }

    @Test
    void 기록기가_실패해도_불일치는_이미_센다() {
        when(turns.advance(anyLong(), anyInt())).thenThrow(failedWithTwoMismatches());
        IllegalStateException writerFailure = new IllegalStateException("log down");
        when(writer.record(anyLong(), anyInt(), anyList())).thenThrow(writerFailure);
        assertThatThrownBy(() -> facade.advance(1L, 0)).isSameAs(writerFailure);
        assertThat(turn("rejected", "RECONCILIATION_FAILED")).isEqualTo(1);
        assertThat(mismatchRules()).isEqualTo(2);
        assertThat(turn("committed", "none")).isZero();
    }

    @Test
    void 전제_오류는_코드와_함께_rejected로_센다() {
        when(turns.advance(anyLong(), anyInt())).thenThrow(new DomainException(ErrorCode.TURN_MISMATCH, "x"));
        assertThatThrownBy(() -> facade.advance(1L, 0)).isInstanceOf(DomainException.class);
        assertThat(turn("rejected", "TURN_MISMATCH")).isEqualTo(1);
        assertThat(mismatchRules()).isZero();
    }

    @Test
    void 도메인이_아닌_예외는_아무것도_세지_않는다() {
        when(turns.advance(anyLong(), anyInt())).thenThrow(new IllegalStateException("commit failed"));
        assertThatThrownBy(() -> facade.advance(1L, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(turn("committed", "none")).isZero();
        assertThat(registry.find("antlab.turn.advance").counters()).hasSize(1);
    }
}

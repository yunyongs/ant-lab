package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import antlab.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GameMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final GameMetrics metrics = new GameMetrics(registry);

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 시작_때_성공_두_계열과_불일치_카운터가_0으로_있다() {
        assertThat(registry.find("antlab.season.start").tags("outcome", "committed", "code", "none").counter()).isNotNull();
        assertThat(registry.find("antlab.turn.advance").tags("outcome", "committed", "code", "none").counter()).isNotNull();
        assertThat(registry.find("antlab.reconciliation.mismatch.rules").counter().count()).isZero();
        assertThat(registry.find("antlab.order.rejected").counter()).isNull();
    }

    @Test
    void 이름_태그_설명이_계약과_같다() {
        metrics.turnRejected(ErrorCode.TURN_MISMATCH);
        metrics.seasonRejected(ErrorCode.SEASON_LIMIT);
        metrics.orderRejected(ErrorCode.INSUFFICIENT_CASH);
        assertThat(count("antlab.turn.advance", "outcome", "rejected", "code", "TURN_MISMATCH")).isEqualTo(1);
        assertThat(count("antlab.season.start", "outcome", "rejected", "code", "SEASON_LIMIT")).isEqualTo(1);
        assertThat(count("antlab.order.rejected", "code", "INSUFFICIENT_CASH")).isEqualTo(1);
        assertThat(registry.find("antlab.turn.advance").counter().getId().getDescription()).startsWith("Turn advance attempts");
        assertThat(registry.find("antlab.order.rejected").counter().getId().getDescription()).isNotBlank();
    }

    @Test
    void 성공_카운터는_올리고_불일치_0개는_아무것도_하지_않는다() {
        metrics.seasonStarted();
        metrics.turnCommitted();
        metrics.mismatchRules(0);
        metrics.mismatchRules(-1);
        metrics.mismatchRules(2);
        assertThat(count("antlab.season.start", "outcome", "committed", "code", "none")).isEqualTo(1);
        assertThat(count("antlab.turn.advance", "outcome", "committed", "code", "none")).isEqualTo(1);
        assertThat(count("antlab.reconciliation.mismatch.rules")).isEqualTo(2);
    }

    @Test
    void 레지스트리가_던져도_증가_메서드는_던지지_않는다() {
        AtomicBoolean down = new AtomicBoolean();
        MeterRegistry failing = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                if (down.get()) {
                    throw new IllegalStateException("registry down");
                }
                return super.newCounter(id);
            }
        };
        GameMetrics broken = new GameMetrics(failing);
        down.set(true);
        assertThatCode(() -> {
            broken.seasonStarted();
            broken.seasonRejected(ErrorCode.NOT_FOUND);
            broken.turnCommitted();
            broken.turnRejected(ErrorCode.NOT_FOUND);
            broken.orderRejected(ErrorCode.NOT_FOUND);
        }).doesNotThrowAnyException();
    }
}

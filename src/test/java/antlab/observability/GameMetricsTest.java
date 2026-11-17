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
    }

    @Test
    void 정지와_재개_카운터는_0으로_있고_정지_거절_조합도_미리_있다() {
        assertThat(registry.find("antlab.season.halted").counter().count()).isZero();
        assertThat(registry.find("antlab.season.resumed").counter().count()).isZero();
        assertThat(GameMetrics.TURN_REJECTIONS).contains(ErrorCode.SEASON_HALTED);
        metrics.seasonHalted();
        metrics.seasonHalted();
        metrics.seasonResumed();
        assertThat(registry.find("antlab.season.halted").counter().count()).isEqualTo(2);
        assertThat(registry.find("antlab.season.resumed").counter().count()).isEqualTo(1);
    }

    @Test
    void 알려진_거절_조합은_시작_때_0으로_있어_첫_이벤트도_센다() {
        for (ErrorCode code : GameMetrics.SEASON_REJECTIONS) {
            assertThat(registry.find("antlab.season.start").tags("outcome", "rejected", "code", code.name()).counter())
                    .as("season " + code).isNotNull().extracting(Counter::count).isEqualTo(0.0);
        }
        for (ErrorCode code : GameMetrics.TURN_REJECTIONS) {
            assertThat(registry.find("antlab.turn.advance").tags("outcome", "rejected", "code", code.name()).counter())
                    .as("turn " + code).isNotNull().extracting(Counter::count).isEqualTo(0.0);
        }
        for (ErrorCode code : GameMetrics.ORDER_REJECTIONS) {
            assertThat(registry.find("antlab.order.rejected").tags("code", code.name()).counter())
                    .as("order " + code).isNotNull().extracting(Counter::count).isEqualTo(0.0);
        }
        metrics.turnRejected(ErrorCode.TURN_MISMATCH);
        assertThat(count("antlab.turn.advance", "outcome", "rejected", "code", "TURN_MISMATCH")).isEqualTo(1);
    }

    @Test
    void 사전_등록은_유한하고_목록_밖_코드는_첫_이벤트에_생긴다() {
        long before = registry.find("antlab.order.rejected").counters().size();
        assertThat(before).isEqualTo(GameMetrics.ORDER_REJECTIONS.size());
        assertThat(registry.find("antlab.order.rejected").tags("code", "FORBIDDEN").counter()).isNull();
        metrics.orderRejected(ErrorCode.FORBIDDEN);
        assertThat(registry.find("antlab.order.rejected").counters()).hasSize((int) before + 1);
        assertThat(registry.find("antlab.season.start").counters())
                .hasSize(1 + GameMetrics.SEASON_REJECTIONS.size());
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

    @Test
    void abandonCounterHasTwoSeriesAtZeroAndCountsEachOnItsOwn() {
        assertThat(count("antlab.season.abandoned", "not_started", "true")).isZero();
        assertThat(count("antlab.season.abandoned", "not_started", "false")).isZero();
        assertThat(registry.find("antlab.season.abandoned").tags("not_started", "true").counter()).isNotNull();
        assertThat(registry.find("antlab.season.abandoned").tags("not_started", "false").counter()).isNotNull();
        metrics.seasonAbandoned(true);
        metrics.seasonAbandoned(true);
        metrics.seasonAbandoned(false);
        assertThat(count("antlab.season.abandoned", "not_started", "true")).isEqualTo(2);
        assertThat(count("antlab.season.abandoned", "not_started", "false")).isEqualTo(1);
        assertThat(registry.find("antlab.season.abandoned").counters()).hasSize(2);
    }
}

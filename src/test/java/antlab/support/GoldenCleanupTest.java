package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GoldenCleanupTest {

    @Test
    void close가_실패해도_컨테이너_정지는_실행되고_첫_예외가_남는다() {
        AtomicBoolean stopped = new AtomicBoolean();
        IllegalStateException closeFailure = new IllegalStateException("close failed");

        assertThatThrownBy(() -> GoldenCleanup.closeThenStop(() -> {
            throw closeFailure;
        }, () -> stopped.set(true))).isSameAs(closeFailure);

        assertThat(stopped).isTrue();
    }

    @Test
    void 둘다_실패하면_첫_예외를_던지고_둘째는_suppressed로_붙인다() {
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        IllegalArgumentException stopFailure = new IllegalArgumentException("stop failed");

        assertThatThrownBy(() -> GoldenCleanup.closeThenStop(() -> {
            throw closeFailure;
        }, () -> {
            throw stopFailure;
        })).isSameAs(closeFailure).hasSuppressedException(stopFailure);
    }

    @Test
    void close만_성공하고_stop이_실패하면_stop_예외를_던진다() {
        IllegalArgumentException stopFailure = new IllegalArgumentException("stop failed");

        assertThatThrownBy(() -> GoldenCleanup.closeThenStop(() -> {
        }, () -> {
            throw stopFailure;
        })).isSameAs(stopFailure);
    }
}

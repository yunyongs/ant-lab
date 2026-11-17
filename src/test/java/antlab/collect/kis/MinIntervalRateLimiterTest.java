package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.kis.KisHarness.AdvancingClock;
import antlab.collect.kis.KisHarness.RecordingSleeper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MinIntervalRateLimiterTest {

    @Test
    void 초당_둘이면_여섯_번째_허가까지_가짜_시계로_2초_반_걸린다() {
        AdvancingClock clock = new AdvancingClock(KisHarness.START);
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(clock, sleeper, 2);

        for (int i = 0; i < 6; i++) {
            limiter.acquire();
        }

        assertThat(sleeper.sleeps).hasSize(5).containsOnly(Duration.ofMillis(500));
        assertThat(sleeper.total()).isEqualTo(Duration.ofMillis(2500));
    }

    @Test
    void 이미_간격이_지났으면_기다리지_않는다() {
        AdvancingClock clock = new AdvancingClock(KisHarness.START);
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(clock, sleeper, 2);

        limiter.acquire();
        clock.advance(Duration.ofMillis(500));
        limiter.acquire();

        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void 초당_하나면_간격은_1초다() {
        AdvancingClock clock = new AdvancingClock(KisHarness.START);
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(clock, sleeper, 1);

        limiter.acquire();
        limiter.acquire();

        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 초당_0이하는_거절한다() {
        AdvancingClock clock = new AdvancingClock(KisHarness.START);
        assertThatThrownBy(() -> new MinIntervalRateLimiter(clock, new RecordingSleeper(clock), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

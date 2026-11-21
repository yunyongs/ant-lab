package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.PoolProperties;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class HistoryPropertiesTest {

    @Configuration
    @EnableConfigurationProperties({HistoryProperties.class, PoolProperties.class})
    @Import(ReplayStartGuard.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("antlab.pool.base-date=2026-01-02");

    @Test
    void the_default_is_the_phase_0_start() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(HistoryProperties.class).replayStart()).isEqualTo(HistoryProperties.DEFAULT_REPLAY_START);
        });
    }

    @Test
    void a_valid_value_is_bound() {
        runner.withPropertyValues("antlab.history.replay-start=1997-01-01").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(HistoryProperties.class).replayStart()).isEqualTo(LocalDate.of(1997, 1, 1));
        });
    }

    @Test
    void a_day_other_than_the_first_fails_startup() {
        runner.withPropertyValues("antlab.history.replay-start=2007-01-15").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("replay-start 2007-01-15");
        });
    }

    @Test
    void a_start_in_or_after_the_base_date_year_fails_startup() {
        for (String value : new String[] {"2026-01-01", "2030-01-01"}) {
            runner.withPropertyValues("antlab.history.replay-start=" + value).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("replay-start " + value);
            });
        }
        runner.withPropertyValues("antlab.history.replay-start=2025-12-01").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void the_record_checks_itself() {
        assertThatThrownBy(() -> new HistoryProperties(LocalDate.of(2007, 1, 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoryProperties(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HistoryProperties.defaults().requireBefore(LocalDate.of(2007, 6, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

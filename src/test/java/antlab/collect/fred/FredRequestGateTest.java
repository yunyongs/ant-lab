package antlab.collect.fred;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FredRequestGateTest {

    @Test
    void 상한_바로_전까지_보내고_넘는_요청은_기다리지_않고_거절한다() {
        var h = new FredHarness(FredHarness.properties(1, 2, 1));
        h.respond(FredHarness.page(3, 0, FredHarness.row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        h.respond(FredHarness.page(3, 1, FredHarness.row("2025-01-02", "1", "2025-01-03", "9999-12-31")));
        var client = h.factory.newClient();

        assertThatThrownBy(() -> client.observations("DGS2", LocalDate.of(2025, 1, 1), false))
                .isInstanceOfSatisfying(FredException.class, e -> assertThat(e.code()).isEqualTo("REQUEST_CAP"));

        assertThat(client.requestsMade()).isEqualTo(2);
        assertThat(h.sleeper.sleeps).as("only the second request waited").containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 두_클라이언트는_속도_제한을_나누고_요청_수는_따로_센다() {
        var h = new FredHarness(FredHarness.properties(1, 5, 100000));
        h.respond(FredHarness.page(1, 0, FredHarness.row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        h.respond(FredHarness.page(1, 0, FredHarness.row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        var first = h.factory.newClient();
        var second = h.factory.newClient();

        first.observations("DGS2", LocalDate.of(2025, 1, 1), false);
        second.observations("DGS2", LocalDate.of(2025, 1, 1), false);

        assertThat(first.requestsMade()).isEqualTo(1);
        assertThat(second.requestsMade()).isEqualTo(1);
        assertThat(h.sleeper.sleeps).containsExactly(Duration.ofSeconds(1));
    }
}

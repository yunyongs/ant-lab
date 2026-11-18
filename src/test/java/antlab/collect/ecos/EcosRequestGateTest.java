package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.answer;
import static antlab.collect.ecos.EcosHarness.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EcosRequestGateTest {

    private static final LocalDate D1 = LocalDate.of(2025, 11, 3);
    private static final LocalDate D2 = LocalDate.of(2025, 11, 18);

    @Test
    void 상한_바로_전까지_보내고_넘는_요청은_기다리지_않고_거절한다() {
        var h = new EcosHarness(EcosHarness.properties(1, 2, 1));
        h.respond(answer(3, row("817Y002", "010200000", "20251103", "1")));
        h.respond(answer(3, row("817Y002", "010200000", "20251104", "1")));
        var client = h.factory.newClient();

        assertThatThrownBy(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2))
                .isInstanceOfSatisfying(EcosException.class, e -> assertThat(e.code()).isEqualTo("REQUEST_CAP"));

        assertThat(client.requestsMade()).isEqualTo(2);
        assertThat(h.sleeper.sleeps).as("only the second request waited").containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 두_클라이언트는_속도_제한을_나누고_요청_수는_따로_센다() {
        var h = new EcosHarness(EcosHarness.properties(1, 5, 100000));
        h.respond(answer(1, row("817Y002", "010200000", "20251103", "1")));
        h.respond(answer(1, row("817Y002", "010200000", "20251103", "1")));
        var first = h.factory.newClient();
        var second = h.factory.newClient();

        first.observations("817Y002", "010200000", EcosCycle.D, D1, D2);
        second.observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(first.requestsMade()).isEqualTo(1);
        assertThat(second.requestsMade()).isEqualTo(1);
        assertThat(h.sleeper.sleeps).containsExactly(Duration.ofSeconds(1));
    }
}

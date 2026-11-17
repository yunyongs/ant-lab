package antlab.engine.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A request that breaks the contract must fail before any call: it would be a Java bug, not an engine answer. */
class EngineRequestRulesTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);

    private static EngineProjectionRequest.Portfolio cash() {
        return new EngineProjectionRequest.Portfolio("CASH", "1", List.of());
    }

    private static EngineProjectionRequest request(int horizon, int n, String risk, List<Integer> turns,
            List<EngineProjectionRequest.Portfolio> portfolios) {
        return new EngineProjectionRequest("14", BASE, horizon, n, risk, turns, portfolios);
    }

    @Test
    void 경로_요청은_버전_길이_시드_범위를_본다() {
        assertThat(EnginePathRequest.of("p0-boot6-v1", Long.MIN_VALUE, 120, BASE).seed()).isEqualTo("-9223372036854775808");
        assertThatThrownBy(() -> EnginePathRequest.of("m9", 1, 12, BASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnginePathRequest.of("m2-sb6-v1", 1, 36, BASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePathRequest("m2-sb6-v1", "9223372036854775808", 12, BASE, false))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> new EnginePathRequest("m2-sb6-v1", "1.5", 12, BASE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePathRequest("m2-sb6-v1", "1", 12, BASE, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnginePathRequest.of("m2-sb6-v1", 1, 12, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 포트폴리오_합이_1이_아니거나_종목이_겹치거나_자리수가_넘으면_거부한다() {
        var holding = new EngineProjectionRequest.Holding("KR:A001", "0.5");
        assertThat(new EngineProjectionRequest.Portfolio("TEAM", "0.5", List.of(holding)).weights()).hasSize(1);
        assertThatThrownBy(() -> new EngineProjectionRequest.Portfolio("TEAM", "0.4", List.of(holding)))
                .hasMessageContaining("exactly 1");
        assertThatThrownBy(() -> new EngineProjectionRequest.Portfolio("TEAM", "0",
                List.of(holding, new EngineProjectionRequest.Holding("KR:A001", "0.5")))).hasMessageContaining("repeat");
        assertThatThrownBy(() -> new EngineProjectionRequest.Portfolio("TEAM", "0.5000000000000", List.of(holding)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineProjectionRequest.Portfolio("team", "1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineProjectionRequest.Holding("ZZ:A001", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineProjectionRequest.Holding("KR:a001", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예상_분포_요청은_구간_한도와_키_유일성을_본다() {
        assertThat(request(60, 100, "-0.2", List.of(60), List.of(cash())).portfolios()).hasSize(1);
        assertThatThrownBy(() -> request(24, 100, "-0.2", List.of(60), List.of(cash()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 99, "-0.2", List.of(60), List.of(cash()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 5001, "-0.2", List.of(60), List.of(cash()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 100, "0.2", List.of(60), List.of(cash()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 100, "-0.2", List.of(), List.of(cash()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 100, "-0.2", List.of(121), List.of(cash()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 100, "-0.2", List.of(60), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(60, 100, "-0.2", List.of(60), List.of(cash(), cash())))
                .hasMessageContaining("unique");
    }
}

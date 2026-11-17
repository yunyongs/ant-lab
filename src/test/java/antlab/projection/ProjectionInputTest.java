package antlab.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shares sent to the engine: exact sum, rounding down, cash as the remainder, and the refusals. */
class ProjectionInputTest {

    private static BigDecimal won(long value) {
        return BigDecimal.valueOf(value).setScale(4);
    }

    private static BigDecimal sum(ProjectionInput input) {
        return input.shares().stream().map(ProjectionInput.Share::weight).reduce(input.cashShare(), BigDecimal::add);
    }

    @Test
    void 비중과_현금의_합은_정확히_1이다() {
        // 3 assets over 7 won: repeating decimals everywhere
        ProjectionInput input = ProjectionInput.of(won(1),
                List.of(new PricedHolding("KR:A001", 1, won(2)), new PricedHolding("KR:A002", 1, won(2)),
                        new PricedHolding("US:C003", 1, won(2))));

        assertThat(input.totalValue()).isEqualByComparingTo("7");
        assertThat(sum(input)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(input.shares()).extracting(ProjectionInput.Share::weight)
                .allSatisfy(w -> assertThat(w).isEqualTo(new BigDecimal("0.285714285714")));
        assertThat(input.cashShare()).isEqualTo(new BigDecimal("0.142857142858"));
    }

    @Test
    void 비중은_12자리에서_내림하고_현금이_나머지를_흡수한다() {
        ProjectionInput input = ProjectionInput.of(won(1), List.of(new PricedHolding("KR:A001", 1, won(2))));

        // 2/3 = 0.666666666666|67 → down; the remainder 1 - 0.666666666666 is cash, above its true share 1/3
        assertThat(input.shares().get(0).weight()).isEqualTo(new BigDecimal("0.666666666666"));
        assertThat(input.cashShare()).isEqualTo(new BigDecimal("0.333333333334"));
        assertThat(input.cashShare()).isGreaterThan(new BigDecimal("0.333333333333333333"));
    }

    @Test
    void 한_종목이_전부면_비중_1이고_현금_0이다() {
        ProjectionInput input = ProjectionInput.of(won(0), List.of(new PricedHolding("US:SPY", 5, won(100))));

        assertThat(input.shares().get(0).weight()).isEqualByComparingTo("1");
        assertThat(input.cashShare()).isEqualByComparingTo("0");
        assertThat(input.cashShare().signum()).isZero();
        assertThat(sum(input)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 현금만이면_종목이_없고_현금_1이다() {
        ProjectionInput input = ProjectionInput.of(won(10_000_000), List.of());

        assertThat(input.shares()).isEmpty();
        assertThat(input.cashShare().toPlainString()).isEqualTo("1");
        assertThat(input.totalValue()).isEqualByComparingTo("10000000");
    }

    @Test
    void 수량_0인_보유는_뺀다() {
        ProjectionInput input = ProjectionInput.of(won(100),
                List.of(new PricedHolding("KR:A001", 0, won(50)), new PricedHolding("KR:A002", 2, won(50))));

        assertThat(input.shares()).extracting(ProjectionInput.Share::asset).containsExactly("KR:A002");
        assertThat(input.totalValue()).isEqualByComparingTo("200");
        assertThat(input.shares().get(0).weight()).isEqualByComparingTo("0.5");
        assertThat(input.cashShare()).isEqualByComparingTo("0.5");
    }

    @Test
    void 서른_종목의_순환소수도_합이_1이고_현금은_음수가_아니다() {
        List<PricedHolding> holdings = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            holdings.add(new PricedHolding("KR:" + String.format("%06d", i), 1 + i % 7, won(1000 + 37L * i)));
        }
        ProjectionInput input = ProjectionInput.of(won(123_457), holdings);

        assertThat(input.shares()).hasSize(30);
        assertThat(sum(input)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(input.cashShare().signum()).isGreaterThanOrEqualTo(0);
        assertThat(input.shares()).allSatisfy(s -> assertThat(s.weight().scale()).isEqualTo(12));
    }

    @Test
    void 엔진이_받지_않는_종목_키는_거절한다() {
        for (String key : List.of("KR:a001", "JP:A001", "KR:", "KR A001", "KR:A/001", "KR:" + "A".repeat(17))) {
            assertThatThrownBy(() -> ProjectionInput.of(won(1), List.of(new PricedHolding(key, 1, won(1)))))
                    .as(key).isInstanceOf(IllegalStateException.class).hasMessage("projection.bad_asset_key");
        }
    }

    @Test
    void 같은_종목이_두_번이면_거절한다() {
        assertThatThrownBy(() -> ProjectionInput.of(won(1),
                List.of(new PricedHolding("KR:A001", 1, won(1)), new PricedHolding("KR:A001", 2, won(1)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 총액이_0이면_거절한다() {
        assertThatThrownBy(() -> ProjectionInput.of(won(0), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("projection.empty_team");
    }

    @Test
    void 가격이_원_단위_정수가_아니면_거절한다() {
        assertThatThrownBy(() -> ProjectionInput.of(won(1),
                List.of(new PricedHolding("KR:A001", 1, new BigDecimal("1.5000")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

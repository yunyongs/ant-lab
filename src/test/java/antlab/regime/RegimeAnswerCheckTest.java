package antlab.regime;

import static antlab.regime.RegimeFixtures.edited;
import static antlab.regime.RegimeFixtures.month;
import static antlab.regime.RegimeFixtures.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.engine.client.EngineRegimeRequest;
import antlab.engine.client.EngineRegimeResult;
import antlab.history.AxisDirection;
import antlab.history.RegimePool;
import antlab.regime.RegimeAnswerCheck.AnswerRejectedException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Everything the job refuses before the first write, on constructed answers. Wire-shape refusals are the client's (EngineClientTest). */
class RegimeAnswerCheckTest {

    private static final EngineRegimeRequest REQUEST = new EngineRegimeRequest(EngineRegimeRequest.LABEL_VERSION, List.of("1997-01", "1997-02"));

    private static List<MonthRegime> check(Consumer<ObjectNode> change) {
        return RegimeAnswerCheck.check(REQUEST, parse(edited(change)));
    }

    @Test
    void theFixtureAnswerBecomesTheColumnValuesOfBothMonths() {
        List<MonthRegime> months = RegimeAnswerCheck.check(REQUEST, RegimeFixtures.parsed());

        assertThat(months).hasSize(2);
        MonthRegime first = months.getFirst();
        assertThat(first.month()).isEqualTo("1997-01");
        assertThat(first.rate()).isEqualTo(new MonthRegime.AxisValue(AxisDirection.UP, new BigDecimal("0.41250000")));
        assertThat(first.growth().direction()).isEqualTo(AxisDirection.NEUTRAL);
        assertThat(first.fx()).isEqualTo(new MonthRegime.AxisValue(AxisDirection.DOWN, new BigDecimal("0.30000000")));
        assertThat(first.sentiment()).isNull();
        assertThat(first.probabilities()).containsExactly(new BigDecimal("0.2731"), new BigDecimal("0.1284"), new BigDecimal("0.1907"),
                new BigDecimal("0.4078"));
        assertThat(first.label()).isEqualTo(RegimePool.P4);
        assertThat(first.indicators()).hasSize(EngineRegimeResult.INDICATOR_KEYS.size());
        assertThat(first.indicators().get("1")).isEqualByComparingTo("0.52000000");
        assertThat(first.indicators().get("2")).isNull();
        assertThat(first.indicators().get("14US")).isEqualByComparingTo("-0.30000000");

        MonthRegime second = months.get(1);
        assertThat(second.month()).isEqualTo("1997-02");
        assertThat(second.rate()).isNull();
        assertThat(second.fx()).isNull();
        assertThat(second.probabilities()).isNull();
        assertThat(second.label()).isNull();
        assertThat(second.indicators()).isNull();
    }

    @Test
    void theColumnMapHasTheFourteenColumnsInTheDocumentedOrder() {
        MonthRegime first = RegimeAnswerCheck.check(REQUEST, RegimeFixtures.parsed()).getFirst();

        assertThat(first.columns().keySet()).containsExactlyElementsOf(MonthRegime.COLUMNS);
        assertThat(MonthRegime.COLUMNS).hasSize(14);
        assertThat(first.columns().get("regime_label")).isEqualTo(RegimePool.P4);
        assertThat(first.columns().get("sentiment_dir")).isNull();
    }

    private static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("label version differs", (Consumer<ObjectNode>) n -> n.put("labelVersion", "m2-rl-v2")),
                Arguments.of("month missing", (Consumer<ObjectNode>) n -> ((ArrayNode) n.get("months")).remove(1)),
                Arguments.of("month added", (Consumer<ObjectNode>) n -> ((ArrayNode) n.get("months")).add(month(n, 1).deepCopy())),
                Arguments.of("months swapped", (Consumer<ObjectNode>) n -> {
                    ArrayNode months = (ArrayNode) n.get("months");
                    var first = months.get(0);
                    months.set(0, months.get(1));
                    months.set(1, first);
                }),
                Arguments.of("other month", (Consumer<ObjectNode>) n -> month(n, 1).put("month", "1997-03")),
                Arguments.of("EVALUATED without probabilities", (Consumer<ObjectNode>) n -> month(n, 0).putNull("probabilities")),
                Arguments.of("EVALUATED without label", (Consumer<ObjectNode>) n -> month(n, 0).putNull("label")),
                Arguments.of("EVALUATED without phase", (Consumer<ObjectNode>) n -> month(n, 0).putNull("phase")),
                Arguments.of("label without probabilities", (Consumer<ObjectNode>) n -> {
                    month(n, 0).putNull("probabilities");
                    month(n, 0).put("status", "CARRIED");
                }),
                Arguments.of("NO_DATA with probabilities", (Consumer<ObjectNode>) n -> month(n, 1).set("probabilities", month(n, 0).get("probabilities"))),
                Arguments.of("NO_DATA with a label", (Consumer<ObjectNode>) n -> month(n, 1).put("label", "P1")),
                Arguments.of("CARRIED with a rate axis", (Consumer<ObjectNode>) n -> {
                    month(n, 1).put("status", "CARRIED");
                    month(n, 1).set("axes", month(n, 0).get("axes"));
                }),
                Arguments.of("coverage evaluated count off", (Consumer<ObjectNode>) n -> ((ObjectNode) n.get("coverage")).put("evaluated", 2)),
                Arguments.of("coverage noData count off", (Consumer<ObjectNode>) n -> ((ObjectNode) n.get("coverage")).put("noData", 0)),
                Arguments.of("probabilities sum 0.001 too high", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("probabilities")).put("p1", "0.2741")),
                Arguments.of("probabilities sum 0.0003 too high", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("probabilities")).put("p4", "0.4081")),
                Arguments.of("probabilities sum 0.0003 too low", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("probabilities")).put("p4", "0.4075")),
                Arguments.of("indicator key missing", (Consumer<ObjectNode>) n -> ((ObjectNode) month(n, 0).get("indicators")).remove("14KR")),
                Arguments.of("no indicators at all", (Consumer<ObjectNode>) n -> ((ObjectNode) month(n, 1).get("indicators")).removeAll()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void aWrongAnswerIsRefusedWithoutNamingAMonthOrAValue(String name, Consumer<ObjectNode> change) {
        assertThatThrownBy(() -> check(change)).as(name).isInstanceOf(AnswerRejectedException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("1997").doesNotContain("0.27").doesNotContain("0.4"));
    }

    @Test
    void theProbabilitySumMayBeOffByExactlyTheDatabaseToleranceAndNotMore() {
        // 0.2731 + 0.1284 + 0.1907 + 0.4080 = 1.0002 and 0.4076 gives 0.9998: both inside ck_month_block_prob_sum
        assertThat(check(n -> ((ObjectNode) month(n, 0).get("probabilities")).put("p4", "0.4080"))).hasSize(2);
        assertThat(check(n -> ((ObjectNode) month(n, 0).get("probabilities")).put("p4", "0.4076"))).hasSize(2);
        assertThat(RegimeAnswerCheck.SUM_TOLERANCE).isEqualByComparingTo("0.0002");
    }

    @Test
    void aMonthThatIsNotEvaluatedCarriesNoColumnValue() {
        List<MonthRegime> carried = check(n -> {
            month(n, 1).put("status", "CARRIED");
            ((ObjectNode) month(n, 1).get("axes")).set("sentiment", month(n, 0).get("axes").get("fx"));
            ((ObjectNode) n.get("coverage")).put("carried", 1).put("noData", 0);
        });
        assertThat(carried.get(1).columns().values()).containsOnlyNulls();

        List<MonthRegime> noData = check(n -> { });
        assertThat(noData.get(1).columns().values()).containsOnlyNulls();
        assertThat(noData.get(0).columns().get("regime_label")).isEqualTo(RegimePool.P4);
    }

    @Test
    void theAnswerObjectsNeverPrintAMonthOrAValue() {
        MonthRegime first = RegimeAnswerCheck.check(REQUEST, RegimeFixtures.parsed()).getFirst();

        assertThat(first.toString()).doesNotContain("1997").doesNotContain("0.41");
    }

    @Test
    void valuesAreComparedByNumberAndJsonObjectsKeyByKey() {
        assertThat(MonthRegime.sameValue(new BigDecimal("0.5"), new BigDecimal("0.50000000"))).isTrue();
        assertThat(MonthRegime.sameValue(new BigDecimal("0.5"), new BigDecimal("0.5001"))).isFalse();
        assertThat(MonthRegime.sameValue(null, null)).isTrue();
        assertThat(MonthRegime.sameValue(null, BigDecimal.ZERO)).isFalse();
        assertThat(MonthRegime.sameValue(BigDecimal.ZERO, null)).isFalse();
        assertThat(MonthRegime.sameValue(RegimePool.P1, RegimePool.P2)).isFalse();
        java.util.Map<String, BigDecimal> a = new java.util.LinkedHashMap<>();
        a.put("1", new BigDecimal("1.0"));
        a.put("2", null);
        java.util.Map<String, BigDecimal> b = new java.util.LinkedHashMap<>();
        b.put("2", null);
        b.put("1", new BigDecimal("1.00"));
        assertThat(MonthRegime.sameValue(a, b)).isTrue();
        b.put("2", BigDecimal.ONE);
        assertThat(MonthRegime.sameValue(a, b)).isFalse();
        b.remove("2");
        assertThat(MonthRegime.sameValue(a, b)).isFalse();
    }

    @Test
    void theIndicatorJsonKeepsEveryDigitAndNull() {
        java.util.Map<String, BigDecimal> values = new java.util.LinkedHashMap<>();
        values.put("1", new BigDecimal("0.52000000"));
        values.put("2", null);
        values.put("14US", new BigDecimal("-0.30000000"));

        assertThat(MonthRegimeWriter.indicatorJson(values)).isEqualTo("{\"1\":0.52000000,\"2\":null,\"14US\":-0.30000000}");
    }
}

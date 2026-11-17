package antlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.SeasonAnalysisDto;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Shape and identity checks of a season analysis response, shared by the API test and the two end-to-end tests. They
 * need the report of the same season: the market baseline and the trading amount are computed twice, in two places.
 */
public final class AnalysisAssertions {

    static final List<String> BASELINE_ORDER = List.of("MARKET", "SIXTY_FORTY", "FORMATION_FOLLOW", "FORMATION_FIXED");
    static final String WON = "-?\\d+";
    static final String RATIO = "-?\\d+\\.\\d{8}";
    static final Set<String> FORBIDDEN_KEYS = Set.of("seasonId", "id", "monthBlockId", "month", "date", "baseDate", "seed",
            "engineVersion", "dataSnapshotDate", "changeId", "name", "assetId");

    private AnalysisAssertions() {
    }

    public static BigDecimal won(JsonNode node) {
        assertThat(node.isString()).as("a won amount is a JSON string").isTrue();
        assertThat(node.asString()).matches(WON);
        return new BigDecimal(node.asString());
    }

    private static void ratio(JsonNode node, String name) {
        if (!node.isNull()) {
            assertThat(node.isString()).as(name).isTrue();
            assertThat(node.asString()).as(name).matches(RATIO);
        }
    }

    /** Keys of an object in order. */
    static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * @param analysis    the response of GET .../report/analysis
     * @param report      the response of GET .../report of the same season
     * @param availableBaselines how many of the four baselines can be built from the pool of the season
     */
    public static void assertConsistent(JsonNode analysis, JsonNode report, int availableBaselines) {
        assertThat(keys(analysis)).as("top-level keys and order").containsExactlyElementsOf(components(SeasonAnalysisDto.class));
        assertThat(analysis.get("analysisVersion").asString()).isEqualTo("mvp17-v1");
        assertThat(analysis.get("representativeVersion").asString()).isEqualTo("rep-v1");
        int turns = analysis.get("reportTurns").asInt();
        assertThat(turns).isEqualTo(report.get("reportTurns").asInt());
        BigDecimal initial = won(report.get("initialCash"));
        BigDecimal userFinal = won(report.get("finalValue"));

        JsonNode baselines = analysis.get("baselines");
        assertThat(baselines.size()).isEqualTo(4);
        int available = 0;
        for (int i = 0; i < 4; i++) {
            JsonNode b = baselines.get(i);
            assertThat(b.get("key").asString()).as("order of baselines").isEqualTo(BASELINE_ORDER.get(i));
            if (b.get("available").asBoolean()) {
                available++;
                assertThat(b.get("reason").isNull()).isTrue();
                assertThat(b.get("legs").size()).isPositive();
                BigDecimal fin = won(b.get("finalValue"));
                assertThat(won(b.get("excessWon"))).as(b.get("key").asString() + " excess = user − baseline")
                        .isEqualByComparingTo(userFinal.subtract(fin));
                assertThat(b.get("turnValues").size()).as("turn values 0..T").isEqualTo(turns + 1);
                assertThat(won(b.get("turnValues").get(turns))).isEqualByComparingTo(fin);
                for (JsonNode v : b.get("turnValues")) {
                    assertThat(won(v).signum()).isPositive();
                }
                for (String name : List.of("totalReturn", "excessReturn")) {
                    ratio(b.get(name), name);
                }
                assertThat(won(b.get("totalFee")).signum()).isGreaterThanOrEqualTo(0);
                assertThat(b.get("forcedChangeCount").asInt()).isLessThanOrEqualTo(b.get("rebalanceCount").asInt());
                assertThat(b.get("bandNoFillCount").asInt()).isGreaterThanOrEqualTo(0);
                assertThat(won(b.get("forcedChangeFee"))).isLessThanOrEqualTo(won(b.get("totalFee")));
                assertThat(b.get("totalReturn").asString()).as("return = (final − initial) ÷ initial")
                        .isEqualTo(fin.subtract(initial).divide(initial, 8, java.math.RoundingMode.HALF_UP).toPlainString());
            } else {
                assertThat(b.get("reason").asString()).isEqualTo("REPRESENTATIVE_MISSING");
                for (String name : List.of("finalValue", "totalReturn", "excessWon", "excessReturn", "totalFee",
                        "rebalanceCount", "bandNoFillCount", "forcedChangeCount", "forcedChangeFee", "turnValues")) {
                    assertThat(b.get(name).isNull()).as(b.get("key").asString() + "." + name).isTrue();
                }
                assertThat(b.get("legs").size()).isZero();
            }
        }
        assertThat(available).isEqualTo(availableBaselines);

        // The market baseline is the report's own baseline, computed by another class
        JsonNode market = baselines.get(0);
        assertThat(market.get("available").asBoolean()).isTrue();
        assertThat(market.get("finalValue").asString()).isEqualTo(report.get("baselineFinalValue").asString());
        assertThat(market.get("totalReturn").asString()).isEqualTo(report.get("baselineReturn").asString());
        assertThat(market.get("excessReturn").asString()).isEqualTo(report.get("excessReturn").asString());
        assertThat(market.get("rebalanceCount").asInt()).as("the market baseline never trades again").isZero();
        assertThat(market.get("bondLegSubstituted").asBoolean()).isFalse();

        JsonNode d = analysis.get("decomposition");
        if (availableBaselines == 4) {
            BigDecimal selection = won(d.get("selectionWon"));
            BigDecimal tactic = won(d.get("tacticWon"));
            BigDecimal structure = won(d.get("structureWon"));
            BigDecimal total = won(d.get("totalWon"));
            assertThat(selection.add(tactic).add(structure)).as("exact identity in won").isEqualByComparingTo(total);
            assertThat(total).isEqualByComparingTo(userFinal.subtract(won(market.get("finalValue"))));
            BigDecimal follow = won(baselines.get(2).get("finalValue"));
            BigDecimal fixed = won(baselines.get(3).get("finalValue"));
            assertThat(selection).as("selection = user − following baseline").isEqualByComparingTo(userFinal.subtract(follow));
            assertThat(tactic).as("tactic = following − fixed").isEqualByComparingTo(follow.subtract(fixed));
            assertThat(structure).as("structure = fixed − market").isEqualByComparingTo(fixed.subtract(won(market.get("finalValue"))));
            for (String name : List.of("selectionReturn", "tacticReturn", "structureReturn", "totalReturn")) {
                assertThat(d.get(name).asString()).matches(RATIO);
            }
            assertThat(d.get("totalReturn").asString()).as("one division by the initial capital")
                    .isEqualTo(total.divide(initial, 8, java.math.RoundingMode.HALF_UP).toPlainString());
            BigDecimal ratioSum = new BigDecimal(d.get("selectionReturn").asString())
                    .add(new BigDecimal(d.get("tacticReturn").asString())).add(new BigDecimal(d.get("structureReturn").asString()));
            assertThat(ratioSum.subtract(new BigDecimal(d.get("totalReturn").asString())).abs())
                    .as("ratios differ from the total by rounding only").isLessThanOrEqualTo(new BigDecimal("0.00000003"));
        } else {
            assertThat(d.isNull()).as("no decomposition without both formation baselines").isTrue();
        }

        JsonNode disc = analysis.get("discipline");
        assertThat(disc.get("dropThreshold").asString()).isEqualTo("-0.08000000");
        assertThat(disc.get("riseThreshold").asString()).isEqualTo("0.08000000");
        assertThat(won(disc.get("tradeNotional"))).as("same trade amount as the report")
                .isEqualByComparingTo(won(report.get("tradeNotional")));
        assertThat(won(disc.get("sellNotional")).add(won(disc.get("buyNotional")))).isEqualByComparingTo(won(disc.get("tradeNotional")));
        assertThat(won(disc.get("panicSellNotional"))).isLessThanOrEqualTo(won(disc.get("sellNotional")));
        assertThat(won(disc.get("chaseBuyNotional"))).isLessThanOrEqualTo(won(disc.get("buyNotional")));
        for (String name : List.of("panicSellShare", "chaseBuyShare", "undisciplinedShare")) {
            ratio(disc.get(name), name);
        }
        assertThat(disc.get("formationChanges").asInt()).isEqualTo(report.get("formationChanges").asInt());
        assertThat(disc.get("panicFormationChanges").asInt()).isLessThanOrEqualTo(disc.get("formationChanges").asInt());

        String body = analysis.toString();
        assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}");
        allKeys(analysis).forEach(k -> assertThat(FORBIDDEN_KEYS).as("forbidden key " + k).doesNotContain(k));
    }

    private static List<String> allKeys(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(e -> {
                out.add(e.getKey());
                out.addAll(allKeys(e.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(item -> out.addAll(allKeys(item)));
        }
        return out;
    }
}

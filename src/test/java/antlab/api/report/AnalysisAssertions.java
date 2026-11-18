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
        assertThat(analysis.get("analysisVersion").asString()).isEqualTo("mvp19-v1");
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

        assertNoiseAndPlatform(analysis, report);

        String body = analysis.toString();
        assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}");
        allKeys(analysis).forEach(k -> assertThat(FORBIDDEN_KEYS).as("forbidden key " + k).doesNotContain(k));
    }

    /** Identities of the noise and platform blocks with the 37-column report of the same season (MVP-19). */
    static void assertNoiseAndPlatform(JsonNode analysis, JsonNode report) {
        JsonNode noise = analysis.get("noise");
        assertThat(keys(noise)).containsExactly("noiseShare", "tradingTurns", "followedBuyNotional", "followedSellNotional",
                "tradeNotional");
        ratio(noise.get("noiseShare"), "noiseShare");
        assertThat(noise.get("noiseShare").isNull()).as("a share exists exactly when some turn traded")
                .isEqualTo(noise.get("tradingTurns").asInt() == 0);
        assertThat(noise.get("tradingTurns").asInt()).isBetween(0, analysis.get("reportTurns").asInt() - 1);
        assertThat(won(noise.get("tradeNotional"))).as("same trade amount as the report")
                .isEqualByComparingTo(won(report.get("tradeNotional")));
        assertThat(won(noise.get("followedBuyNotional")).add(won(noise.get("followedSellNotional"))))
                .isLessThanOrEqualTo(won(noise.get("tradeNotional")));
        assertThat(won(noise.get("followedBuyNotional")).signum()).isGreaterThanOrEqualTo(0);
        assertThat(won(noise.get("followedSellNotional")).signum()).isGreaterThanOrEqualTo(0);
        if (!noise.get("noiseShare").isNull()) {
            BigDecimal share = new BigDecimal(noise.get("noiseShare").asString());
            assertThat(share.signum()).isGreaterThanOrEqualTo(0);
            assertThat(share).isLessThanOrEqualTo(BigDecimal.ONE);
        }

        JsonNode platform = analysis.get("platform");
        assertThat(keys(platform)).containsExactly("totalFee", "teamFee", "bySource", "nudgedFee", "nudgedFeeShare");
        JsonNode rows = platform.get("bySource");
        assertThat(rows.size()).isEqualTo(5);
        List<String> sources = List.of("RANKING", "FEED", "ALERT", "BENCH", "DIRECT");
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal notionals = BigDecimal.ZERO;
        BigDecimal nudged = BigDecimal.ZERO;
        for (int i = 0; i < 5; i++) {
            JsonNode row = rows.get(i);
            assertThat(keys(row)).containsExactly("source", "orders", "tradeNotional", "fee");
            assertThat(row.get("source").asString()).as("fixed order of sources").isEqualTo(sources.get(i));
            assertThat(row.get("orders").asInt()).isGreaterThanOrEqualTo(0);
            fees = fees.add(won(row.get("fee")));
            notionals = notionals.add(won(row.get("tradeNotional")));
            if (i == 0 || i == 2) {
                nudged = nudged.add(won(row.get("fee")));
            }
        }
        assertThat(rows.get(3).get("orders").asInt()).as("BENCH is reserved and refused, so it never has an order").isZero();
        assertThat(won(platform.get("teamFee"))).isEqualByComparingTo(won(report.get("teamFee")));
        assertThat(won(platform.get("totalFee"))).isEqualByComparingTo(won(report.get("totalFee")));
        assertThat(fees.add(won(platform.get("teamFee")))).as("sources + team = total").isEqualByComparingTo(won(platform.get("totalFee")));
        assertThat(won(platform.get("nudgedFee"))).as("RANKING + ALERT only, equal to the report's platform fee")
                .isEqualByComparingTo(won(report.get("platformNudgeFee"))).isEqualByComparingTo(nudged);
        assertThat(notionals).as("the sources split the trade amount").isEqualByComparingTo(won(noise.get("tradeNotional")));
        ratio(platform.get("nudgedFeeShare"), "nudgedFeeShare");
        assertThat(platform.get("nudgedFeeShare").isNull()).isEqualTo(won(platform.get("totalFee")).signum() == 0);
        if (!platform.get("nudgedFeeShare").isNull()) {
            assertThat(platform.get("nudgedFeeShare").asString()).as("one division")
                    .isEqualTo(won(platform.get("nudgedFee")).divide(won(platform.get("totalFee")), 8,
                            java.math.RoundingMode.HALF_UP).toPlainString());
        }
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

package antlab.feed;

import static antlab.feed.FeedFacts.asset;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.feed.AlertRules.Family;
import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every ACTIVE {@code NUDGE} sentence of {@code feed/templates.csv} has a family by its shape and can be picked by some input, so a
 * later edit of the file cannot add a sentence that the generator would silently never use.
 */
class AlertTemplateFamilyTest {

    private static final int TURN = 9;

    private final FeedTemplateCatalog catalog = new FeedTemplateCatalog();

    private List<FeedTemplate> activeNudges() {
        return catalog.templates().stream().filter(t -> t.kind() == FeedKind.NUDGE && t.active()).toList();
    }

    private static FeedTemplate template(String id, String target, String text) {
        return new FeedTemplate(id, FeedKind.NUDGE, true, target, null, null, null, null, null, false, text);
    }

    @Test
    void every_active_nudge_template_belongs_to_exactly_one_family() {
        assertThat(activeNudges()).hasSizeGreaterThanOrEqualTo(Family.values().length);
        Map<Family, Integer> perFamily = new EnumMap<>(Family.class);
        for (FeedTemplate t : activeNudges()) {
            Family family = AlertRules.familyOf(t).orElse(null);
            assertThat(family).as(t.id() + " fits a family").isNotNull();
            perFamily.merge(family, 1, Integer::sum);
        }
        for (Family family : Family.values()) {
            assertThat(perFamily).as("a sentence for " + family).containsKey(family);
        }
    }

    @Test
    void a_template_that_fits_no_family_is_reported() {
        assertThat(AlertRules.familyOf(template("nudge.grp.v1", "GROUP", "{group_name} 흐름을 보세요"))).isEmpty();
        assertThat(AlertRules.familyOf(template("nudge.mix.v1", "NONE", "{cash_weight} 그리고 {idle_turns}"))).isEmpty();
        assertThat(AlertRules.familyOf(template("nudge.assetgrp.v1", "ASSET", "{asset_name} {group_name}"))).isEmpty();
        assertThat(AlertRules.familyOf(template("nudge.none.v1", "NONE", "{asset_name} 을 보세요"))).isEmpty();
        assertThat(AlertRules.familyOf(template("nudge.ok.v1", "NONE", "한번 둘러보세요"))).contains(Family.FREE);
        assertThat(AlertRules.familyOf(template("nudge.ok2.v1", "NONE", "{cash_weight}"))).contains(Family.CASH);
        assertThat(AlertRules.familyOf(template("nudge.ok3.v1", "NONE", "{trade_count} {idle_turns}"))).contains(Family.IDLE);
        assertThat(AlertRules.familyOf(new FeedTemplate("rise.x.v1", FeedKind.RISE, true, "ASSET", null, null, null, null, null,
                false, "{asset_name}"))).isEmpty();
    }

    @Test
    void every_active_nudge_template_is_picked_by_some_input() {
        AlertGenerator generator = new AlertGenerator(catalog);
        List<AssetFacts> assets = List.of(
                asset(1, "A1", "가나").turn(TURN).ret("0.08000000").streak(3).attention("8", 2, 4, null, null).build(),
                asset(2, "A2", "다라").turn(TURN).ret("-0.05000000").streak(1).attention("7", 3, 4, null, null).build(),
                asset(3, "A3", "마바").turn(TURN).ret("0.00000000").streak(1).attention("9", 1, 4, null, null).build(),
                asset(4, "A4", "사아").turn(TURN).ret("0.02000000").streak(1).attention("6", 4, 4, null, null).build());
        Set<String> picked = new HashSet<>();
        for (long seed = 1; seed <= 400; seed++) {
            for (String cash : List.of("0.50000000", "0.10000000")) {
                AlertInput input = new AlertInput(seed, TURN, assets, new PlayerFacts(TURN, new BigDecimal(cash), 0, 3));
                generator.generate(input, 1).forEach(r -> picked.add(r.templateId()));
            }
        }
        Set<String> all = new HashSet<>();
        activeNudges().forEach(t -> all.add(t.id()));
        assertThat(picked).as("templates never picked").containsExactlyInAnyOrderElementsOf(all);
        assertThat(BigDecimal.ONE).isNotNull();
    }
}

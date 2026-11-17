package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Catches a template the v2 generator can never pick. The fillable slots below are what the generator fills for each (kind, target):
 * a sentence with any other slot has no candidate that carries every slot, so it would sit in the file unused.
 */
class FeedTemplateReachabilityTest {

    private static final Map<String, Set<String>> FILLABLE = Map.of(
            "RISE/ASSET", Set.of("asset_name", "group_name", "ret", "streak"),
            "FALL/ASSET", Set.of("asset_name", "group_name", "ret", "streak"),
            "RISE/GROUP", Set.of("group_name", "group_ret"),
            "FALL/GROUP", Set.of("group_name", "group_ret"),
            "SPREAD/GROUP", Set.of("group_name", "group_ret"),
            "ECHO/ASSET", Set.of("asset_name", "group_name", "ret", "prev_ret", "streak", "repeat"),
            "FADE/ASSET", Set.of("asset_name", "focus_name", "attention_rank"),
            "EVENT/NONE", Set.of("reaction"));

    /** {@code {rank}} is never filled by rule version p0-18:v2, so this id is dead on purpose until a later rule version. */
    private static final Set<String> KNOWN_DEAD = Set.of("rise.rank.v1");

    private final FeedTemplateCatalog catalog = new FeedTemplateCatalog();

    @Test
    void every_active_template_outside_the_alert_kind_can_be_filled_by_the_generator() {
        for (FeedTemplate t : catalog.templates()) {
            if (!t.active() || t.kind() == FeedKind.NUDGE || KNOWN_DEAD.contains(t.id())) {
                continue;
            }
            Set<String> fillable = FILLABLE.get(t.kind() + "/" + t.target());
            assertThat(fillable).as(t.id() + " has a (kind, target) the generator produces").isNotNull();
            assertThat(fillable).as(t.id() + " slots").containsAll(t.placeholders());
        }
        assertThat(catalog.templates().stream().filter(t -> KNOWN_DEAD.contains(t.id()))
                .allMatch(t -> t.placeholders().contains("rank"))).isTrue();
    }

    @Test
    void every_reachable_combination_reaches_the_mvp_08_count_targets() {
        Map<String, Integer> counts = new HashMap<>();
        for (FeedTemplate t : catalog.templates()) {
            if (t.active() && !KNOWN_DEAD.contains(t.id())) {
                counts.merge(t.kind() + "/" + t.target() + "/" + t.direction(), 1, Integer::sum);
            }
        }
        Map<String, Integer> minimum = Map.of(
                "RISE/ASSET/UP", 6, "FALL/ASSET/DOWN", 6, "RISE/GROUP/UP", 5, "FALL/GROUP/DOWN", 5,
                "SPREAD/GROUP/UP", 4, "SPREAD/GROUP/DOWN", 4, "ECHO/ASSET/UP", 4, "ECHO/ASSET/DOWN", 4, "FADE/ASSET/null", 5);
        minimum.forEach((combination, floor) -> assertThat(counts.getOrDefault(combination, 0)).as(combination)
                .isGreaterThanOrEqualTo(floor));
    }

    @Test
    void a_template_the_pool_can_choose_for_a_full_candidate_exists_for_every_story_kind() {
        assertThat(catalog.pool(FeedKind.FADE, "ASSET", null, null, Set.of("asset_name", "focus_name", "attention_rank"), false, null))
                .isNotEmpty();
        assertThat(catalog.pool(FeedKind.SPREAD, "GROUP", MoveDirection.DOWN, -5, Set.of("group_name", "group_ret"), false, null))
                .isNotEmpty();
        assertThat(catalog.pool(FeedKind.ECHO, "ASSET", MoveDirection.UP, 5,
                Set.of("asset_name", "group_name", "ret", "prev_ret", "streak", "repeat"), false, null)).isNotEmpty();
        assertThat(List.of(FeedKind.values())).contains(FeedKind.NUDGE);
    }
}

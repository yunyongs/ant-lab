package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.money.MoneyMath;
import antlab.history.MoveDirection;
import antlab.season.FeedItemDto;
import antlab.season.FeedKind;
import antlab.season.TurnFeed;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * "Same clothes" (feed/README promise 12, PLAN §4.13) as a property over every event sentence: whatever the player is shown for an
 * event card, a group noise row can be shown identically. Both go through the one DTO conversion, so the public fields cannot tell
 * them apart: the event has no asset, no group chip, and the same text. Checked for every ACTIVE EVENT template (public and hidden
 * wording) and reactions on both sides of 0, on the rounding edge and exactly 0.
 */
class FeedSameClothesTest {

    private static final List<BigDecimal> REACTIONS = List.of(new BigDecimal("0.18234567"), new BigDecimal("0.35"),
            new BigDecimal("0.005"), new BigDecimal("0.004"), new BigDecimal("0.00000000"), new BigDecimal("-0.004"),
            new BigDecimal("-0.005"), new BigDecimal("-0.04"), new BigDecimal("-0.18234567"));

    private final FeedTemplateCatalog catalog = new FeedTemplateCatalog();
    private final FeedRenderer renderer = new FeedRenderer(catalog.variables());

    private static FeedItemDto shown(FeedKind kind, boolean hidden, Long groupId, Long eventId, String templateId, String body) {
        TurnFeed row = new TurnFeed(null, 1, 1, kind, null, groupId, eventId, hidden, null, 2, 0, templateId, "{}", body);
        return FeedItemDto.from(row);
    }

    /** What a group noise row (a group rise or fall, or a spread) can show for a group return of {@code ret}: the real pool rules, then the real renderer. */
    private Set<FeedItemDto> noiseDtos(BigDecimal ret) {
        Set<FeedItemDto> out = new LinkedHashSet<>();
        boolean up = ret.signum() > 0;
        Integer r = MoneyMath.percentPoints(ret).intValueExact();
        for (FeedKind kind : List.of(up ? FeedKind.RISE : FeedKind.FALL, FeedKind.SPREAD)) {
            List<FeedTemplate> pool = catalog.pool(kind, "GROUP", up ? MoveDirection.UP : MoveDirection.DOWN, r,
                    Set.of("group_name", "group_ret"), false, null);
            for (FeedTemplate template : pool) {
                Map<String, Object> values = new java.util.LinkedHashMap<>();
                for (String slot : template.placeholders()) {
                    values.put(slot, slot.equals("group_name") ? "예시그룹" : ret);
                }
                out.add(shown(kind, false, 5L, null, template.id(), renderer.render(template.text(), values)));
            }
        }
        return out;
    }

    @Test
    void every_event_sentence_is_also_a_sentence_a_group_row_can_show() {
        List<FeedTemplate> events = catalog.templates().stream().filter(t -> t.kind() == FeedKind.EVENT && t.active()).toList();
        assertThat(events).as("event templates").hasSizeGreaterThanOrEqualTo(9);
        assertThat(events).anySatisfy(t -> assertThat(t.categoryHidden()).isTrue());
        assertThat(events).anySatisfy(t -> assertThat(t.categoryHidden()).isFalse());
        int checked = 0;
        for (FeedTemplate event : events) {
            for (BigDecimal reaction : REACTIONS) {
                FeedItemDto card = shown(FeedKind.EVENT, event.categoryHidden(), null, 77L, event.id(),
                        renderer.render(event.text(), Map.of("reaction", reaction)));
                Set<FeedItemDto> producible = new LinkedHashSet<>();
                if (MoneyMath.percentPoints(reaction).signum() == 0) {
                    // A reaction that rounds to 0 is shown as 0%, whatever its sign; so is a nonzero group return of either sign.
                    producible.addAll(noiseDtos(new BigDecimal("0.004")));
                    producible.addAll(noiseDtos(new BigDecimal("-0.004")));
                } else {
                    producible.addAll(noiseDtos(reaction));
                }
                assertThat(producible).as(event.id() + " at " + reaction).contains(card);
                assertThat(card.assetId()).isNull();
                checked++;
            }
        }
        assertThat(checked).isEqualTo(events.size() * REACTIONS.size());
    }

    @Test
    void the_dto_of_a_stored_row_does_not_carry_kind_group_or_event() {
        FeedItemDto event = shown(FeedKind.EVENT, true, null, 77L, "event.hidden.v1", "문장");
        FeedItemDto noise = shown(FeedKind.RISE, false, 5L, null, "rise.group.v1", "문장");

        assertThat(event).isEqualTo(noise);
        assertThat(event.getClass().getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("turnNo", "seq", "assetId", "body");
    }
}

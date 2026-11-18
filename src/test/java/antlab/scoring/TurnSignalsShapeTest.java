package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MoveDirection;
import antlab.scoring.TurnSignals.FeedRow;
import antlab.season.FeedKind;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Look-ahead guard at the type level, as for the feed and ranking inputs: the signals hold turn numbers and asset id sets, nothing
 * that can carry a block, a month, a date or a path, and no component is named like a future value.
 */
class TurnSignalsShapeTest {

    private static final Pattern FUTURE_NAME = Pattern.compile("(?i)^(monthBlock|next|future|forward|fwd|lead|plus).*|.*t1$");
    private static final Pattern CALENDAR_NAME = Pattern.compile("(?i).*(date|month|block|path|seed|day|year).*");

    @Test
    void theSignalsHoldOnlyTurnNumbersAndAssetIdSets() {
        for (RecordComponent c : TurnSignals.class.getRecordComponents()) {
            Type type = c.getGenericType();
            if (c.getName().equals("turnNo")) {
                assertThat(type).isEqualTo(int.class);
            } else {
                assertThat(type).as(c.getName()).isInstanceOf(ParameterizedType.class);
                ParameterizedType p = (ParameterizedType) type;
                assertThat(p.getRawType()).isEqualTo(Set.class);
                assertThat(p.getActualTypeArguments()).containsExactly(Long.class);
            }
        }
    }

    @Test
    void theFeedRowHoldsOnlyTheTurnTheKindTheAssetAndTheDirection() {
        List<Class<?>> types = new ArrayList<>();
        for (RecordComponent c : FeedRow.class.getRecordComponents()) {
            types.add(c.getType());
        }
        assertThat(types).containsExactly(int.class, FeedKind.class, Long.class, MoveDirection.class);
    }

    @Test
    void noComponentIsNamedLikeAFutureOrCalendarValue() {
        List<String> names = new ArrayList<>();
        for (Class<?> record : List.of(TurnSignals.class, FeedRow.class)) {
            for (RecordComponent c : record.getRecordComponents()) {
                names.add(c.getName());
            }
        }
        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertThat(FUTURE_NAME.matcher(name).matches()).as(name).isFalse();
            assertThat(CALENDAR_NAME.matcher(name).matches()).as(name).isFalse();
        }
    }
}

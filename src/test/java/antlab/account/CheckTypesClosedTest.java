package antlab.account;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.FormationCheck;
import antlab.season.FormationPlan;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The scope, plan and check types can be made only by the service that locked and checked: no public constructor. */
class CheckTypesClosedTest {

    @Test
    void no_type_of_the_check_and_apply_split_has_a_public_constructor() {
        for (Class<?> type : List.of(OrderScope.class, OrderPlan.class, OrderCheck.class, FormationPlan.class,
                FormationCheck.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isFalse();
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                assertThat(Modifier.isPublic(c.getModifiers())).as(type.getSimpleName()).isFalse();
                assertThat(Modifier.isProtected(c.getModifiers())).as(type.getSimpleName()).isFalse();
            }
        }
    }
}

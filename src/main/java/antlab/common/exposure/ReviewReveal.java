package antlab.common.exposure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Handler reveals the {@code [복기 전용]} values of a finished season (real months, event names) and the season's
 * reproducibility triple. Requires {@link FinishedSeasonOnly}; the exposure contract test freezes the set of handlers
 * that may carry it (SPRING_JPA §4.6).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReviewReveal {
}

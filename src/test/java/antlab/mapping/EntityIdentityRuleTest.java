package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** One identity rule for every entity (SPRING_JPA §4.2): no equals/hashCode override. Scans all of {@code antlab}. */
class EntityIdentityRuleTest {

    @Test
    void noEntityOverridesEqualsOrHashCode() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("antlab")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            scanned.add(type.getSimpleName());
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (var m : c.getDeclaredMethods()) {
                    boolean equalsOverride = m.getName().equals("equals") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Object.class;
                    boolean hashCodeOverride = m.getName().equals("hashCode") && m.getParameterCount() == 0;
                    if (equalsOverride || hashCodeOverride) {
                        offenders.add(c.getSimpleName() + "." + m.getName());
                    }
                }
            }
        }
        assertThat(scanned).as("entities found").hasSizeGreaterThan(20);
        assertThat(offenders).as("entities overriding equals/hashCode").isEmpty();
    }
}

package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;

/** 읽기 전용 저장소의 모양. 컴파일 수준 보장(save·delete 를 받지 않음)을 리플렉션으로 확인한다. 스프링 문맥 없이 돈다. */
class ReadOnlyRepositoryShapeTest {

    private static final List<String> PACKAGES = List.of("antlab.asset", "antlab.history");

    private static List<Class<?>> repositories() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                return bd.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> result = new ArrayList<>();
        for (String pkg : PACKAGES) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                result.add(Class.forName(bd.getBeanClassName()));
            }
        }
        return result;
    }

    @Test
    void 저장소는_여덟_개다() throws Exception {
        assertThat(repositories()).hasSize(8);
    }

    @Test
    void Repository만_상속하고_쓰기_계열을_받지_않는다() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories()) {
            assertThat(repo.getInterfaces()).as(repo.getSimpleName()).containsExactly(Repository.class);
            for (Class<?> banned : List.of(CrudRepository.class, ListCrudRepository.class, JpaRepository.class,
                    PagingAndSortingRepository.class, ListPagingAndSortingRepository.class)) {
                if (banned.isAssignableFrom(repo)) {
                    violations.add(repo.getSimpleName() + " 이 " + banned.getSimpleName() + " 를 상속");
                }
            }
            for (Method m : repo.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.startsWith("find") || n.startsWith("count") || n.startsWith("exists"))) {
                    violations.add(repo.getSimpleName() + "." + n);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 저장소마다_findById가_있다() throws Exception {
        for (Class<?> repo : repositories()) {
            assertThat(repo.getMethod("findById", Long.class)).as(repo.getSimpleName()).isNotNull();
        }
    }
}

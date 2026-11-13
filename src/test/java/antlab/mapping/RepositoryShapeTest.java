package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.persistence.AppendOnlyRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

/**
 * 저장소의 모양. 컴파일 수준 보장(삭제를 받지 않음)을 리플렉션으로 확인한다. 스프링 문맥 없이 돈다.
 * 패키지를 고정하지 않고 {@code antlab} 전체를 훑으므로 저장소를 더하면 자동으로 들어온다.
 * 도메인 형으로 분류한다: 공개 생성자가 없는 {@code @Immutable} = 읽기 전용, 공개 생성자가 있는 {@code @Immutable} = 추가 전용,
 * 그 밖 = 가변.
 */
class RepositoryShapeTest {

    private static final Set<String> NO_DELETE_PREFIXES = Set.of("delete", "remove", "saveAll", "flush");

    private static List<Class<?>> repositories() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                return bd.getMetadata().isInterface()
                        && !bd.getMetadata().hasAnnotation(org.springframework.data.repository.NoRepositoryBean.class.getName());
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("antlab")) {
            result.add(Class.forName(bd.getBeanClassName()));
        }
        return result;
    }

    private static Class<?> domainType(Class<?> repo) {
        return GenericTypeResolver.resolveTypeArguments(repo, Repository.class)[0];
    }

    private static boolean hasPublicConstructor(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors()).anyMatch(k -> Modifier.isPublic(k.getModifiers()));
    }

    private static boolean isReadOnly(Class<?> type) {
        return type.isAnnotationPresent(Immutable.class) && !hasPublicConstructor(type);
    }

    private static boolean isAppendOnly(Class<?> type) {
        return type.isAnnotationPresent(Immutable.class) && hasPublicConstructor(type);
    }

    @Test
    void 저장소는_스물한_개다() throws Exception {
        // read-only 8 + append-only 8 (turn_feed is MVP) + mutable 5 (RefreshToken has no repository until SEC-03)
        assertThat(repositories()).hasSize(21);
        assertThat(repositories().stream().map(RepositoryShapeTest::domainType).filter(RepositoryShapeTest::isReadOnly))
                .hasSize(8);
        assertThat(repositories().stream().map(RepositoryShapeTest::domainType).filter(RepositoryShapeTest::isAppendOnly))
                .hasSize(8);
    }

    @Test
    void 삭제_계열과_넓은_저장소_상속이_없다() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories()) {
            for (Class<?> banned : List.of(CrudRepository.class, ListCrudRepository.class, JpaRepository.class,
                    PagingAndSortingRepository.class, ListPagingAndSortingRepository.class)) {
                if (banned.isAssignableFrom(repo)) {
                    violations.add(repo.getSimpleName() + " 이 " + banned.getSimpleName() + " 를 상속");
                }
            }
            for (Method m : repo.getMethods()) {
                if (NO_DELETE_PREFIXES.stream().anyMatch(p -> m.getName().startsWith(p))) {
                    violations.add(repo.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 읽기_전용_엔티티의_저장소는_Repository만_상속하고_조회_메서드만_갖는다() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories()) {
            if (!isReadOnly(domainType(repo))) {
                continue;
            }
            assertThat(repo.getInterfaces()).as(repo.getSimpleName()).containsExactly(Repository.class);
            for (Method m : repo.getMethods()) {
                String n = m.getName();
                if (!(n.startsWith("find") || n.startsWith("count") || n.startsWith("exists"))) {
                    violations.add(repo.getSimpleName() + "." + n);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 추가_전용_엔티티의_저장소는_AppendOnlyRepository_만_상속하고_조회_메서드만_더한다() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories()) {
            if (!isAppendOnly(domainType(repo))) {
                continue;
            }
            assertThat(repo.getInterfaces()).as(repo.getSimpleName()).containsExactly(AppendOnlyRepository.class);
            for (Method m : repo.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.startsWith("find") || n.startsWith("count") || n.startsWith("exists"))
                        || m.isAnnotationPresent(Modifying.class)) {
                    violations.add(repo.getSimpleName() + "." + n);
                }
            }
        }
        assertThat(violations).as("조회가 아닌 메서드나 @Modifying").isEmpty();
    }

    @Test
    void 가변_엔티티의_저장소는_Repository만_상속하고_삭제_없이_save와_조회를_갖는다() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories()) {
            Class<?> type = domainType(repo);
            if (isReadOnly(type) || isAppendOnly(type)) {
                continue;
            }
            assertThat(repo.getInterfaces()).as(repo.getSimpleName()).containsExactly(Repository.class);
            for (Method m : repo.getMethods()) {
                String n = m.getName();
                if (!(n.equals("save") || n.startsWith("find") || n.startsWith("count") || n.startsWith("exists"))) {
                    violations.add(repo.getSimpleName() + "." + n);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 저장소마다_findById가_있다() throws Exception {
        for (Class<?> repo : repositories()) {
            assertThat(Arrays.stream(repo.getMethods()).filter(m -> m.getName().equals("findById")))
                    .as(repo.getSimpleName()).hasSize(1);
        }
    }
}

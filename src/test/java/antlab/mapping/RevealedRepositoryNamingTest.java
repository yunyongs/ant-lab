package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.ExposureCatalog;
import antlab.support.IntegrationTest;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A repository method that selects or returns a {@code [복기 전용]} value column must be named {@code findRevealed…}
 * (SPRING_JPA §4.6). Value columns are read from the DB catalog, so a newly tagged column is covered without edits here.
 * Join keys ({@code *_id}) are not value columns, except that a projection must never carry {@code monthBlockId}.
 * Entity getters for these columns are removed on MonthBlock, MonthEvent, Asset and SeasonPath (TurnFeed has no repository until MVP), so projections are the only way out; this test guards their names and callers.
 */
@IntegrationTest
class RevealedRepositoryNamingTest {

    /** Engine input for path generation; never reaches a response. Frozen at one method. */
    static final Set<String> ENGINE_EXEMPT = Set.of("MonthBlockRepository.findReplayMonths");

    static final String PREFIX = "findRevealed";

    /** Only these sources may mention the reveal queries. */
    static final Set<String> CALLERS = Set.of("antlab/season/SeasonPathRepository.java", "antlab/season/SeasonReviewService.java");

    /** The engine exemption is declared in its repository and called only by the engine path source. */
    static final Set<String> ENGINE_CALLERS = Set.of("antlab/history/MonthBlockRepository.java", "antlab/engine/BootstrapPathSource.java");

    @Autowired
    JdbcTemplate jdbc;

    private record ValueColumn(String table, String column) {
        String property() {
            return camel(column);
        }

        String entity() {
            String camel = camel(table);
            return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        }
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return out.toString();
    }

    private static boolean identifier(String column) {
        return column.equals("id") || column.endsWith("_id");
    }

    private static List<ValueColumn> valueColumns(ExposureCatalog catalog) {
        List<ValueColumn> out = new ArrayList<>();
        catalog.tags().forEach((key, tag) -> {
            String table = key.substring(0, key.indexOf('.'));
            String column = key.substring(key.indexOf('.') + 1);
            if (ExposureCatalog.REVEAL_ONLY.equals(tag) && !identifier(column)) {
                out.add(new ValueColumn(table, column));
            }
        });
        return out;
    }

    private static boolean hasWord(String text, String word) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

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

    /** Element type of a return type: unwraps Optional, collections, streams and arrays. */
    private static Class<?> element(Type type) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? element(c.getComponentType()) : c;
        }
        if (type instanceof ParameterizedType p) {
            Type[] args = p.getActualTypeArguments();
            return args.length == 0 ? (Class<?>) p.getRawType() : element(args[0]);
        }
        return Object.class;
    }

    /** Property names a projection exposes: record components or interface getters. Entities and JDK types expose none here. */
    private static Set<String> projectionProperties(Class<?> type) {
        if (type.isAnnotationPresent(Entity.class) || type.getName().startsWith("java.") || type.isPrimitive()
                || type.isEnum()) {
            return Set.of();
        }
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
        }
        if (type.isInterface()) {
            return Arrays.stream(type.getMethods()).map(Method::getName).filter(n -> n.startsWith("get") && n.length() > 3)
                    .map(n -> Character.toLowerCase(n.charAt(3)) + n.substring(4)).collect(Collectors.toSet());
        }
        return Set.of();
    }

    /** Violations as {@code Repository.method — reason}. Methods are checked on the declared interfaces and their parents. */
    static List<String> violations(List<Class<?>> repos, List<ValueColumn> values) {
        List<String> out = new ArrayList<>();
        for (Class<?> repo : repos) {
            for (Method m : repo.getMethods()) {
                String id = repo.getSimpleName() + "." + m.getName();
                if (m.getName().startsWith(PREFIX) || ENGINE_EXEMPT.contains(id)) {
                    continue;
                }
                Query query = m.getAnnotation(Query.class);
                if (query != null) {
                    for (ValueColumn v : values) {
                        boolean named = hasWord(query.value(), v.column()) || hasWord(query.value(), v.property());
                        boolean fromTable = hasWord(query.value(), v.table()) || hasWord(query.value(), v.entity());
                        if (named && fromTable) {
                            out.add(id + " — query reads " + v.table() + "." + v.column());
                        }
                    }
                }
                Set<String> props = projectionProperties(element(m.getGenericReturnType()));
                for (ValueColumn v : values) {
                    if (props.contains(v.property())) {
                        out.add(id + " — projection carries " + v.property());
                    }
                }
                if (props.contains("monthBlockId")) {
                    out.add(id + " — projection carries monthBlockId");
                }
            }
        }
        return out;
    }

    @Test
    void 복기_전용_값_열을_고르거나_돌려주는_저장소_메서드는_findRevealed로_시작한다() throws Exception {
        List<ValueColumn> values = valueColumns(ExposureCatalog.load(jdbc));
        assertThat(values).as("value columns from the catalog").extracting(ValueColumn::column)
                .contains("month_start", "event_summary", "title", "source", "hindsight", "listed_on");
        assertThat(violations(repositories(), values)).isEmpty();
    }

    @Test
    void 엔진_예외는_메서드_하나로_고정되어_있고_실제로_있다() throws Exception {
        assertThat(ENGINE_EXEMPT).containsExactly("MonthBlockRepository.findReplayMonths");
        List<String> declared = repositories().stream()
                .flatMap(r -> Arrays.stream(r.getMethods()).map(m -> r.getSimpleName() + "." + m.getName())).toList();
        assertThat(declared).containsAll(ENGINE_EXEMPT);
    }

    /** Test-only interfaces (not {@code Repository} subtypes, so the real scan never sees them). */
    interface PlantedViolation {
        @Query("select m.monthStart from MonthBlock m")
        List<java.time.LocalDate> findMonths();
    }

    interface PlantedAllowed {
        @Query("select m.monthStart from MonthBlock m")
        List<java.time.LocalDate> findRevealedMonths();
    }

    interface PlantedJoinKeyOnly {
        @Query("select sp.monthBlockId from SeasonPath sp")
        List<Long> findTurnFactors();
    }

    interface PlantedNativeText {
        @Query(nativeQuery = true, value = "select title from month_event")
        List<String> findTitles();
    }

    interface PlantedUpperCaseNative {
        @Query(nativeQuery = true, value = "SELECT MONTH_START FROM MONTH_BLOCK")
        List<Object[]> findMonthsLoud();
    }

    interface PlantedOtherTable {
        @Query(nativeQuery = true, value = "select source from orders")
        List<String> findSources();
    }

    record BlockRow(Long monthBlockId, int turnNo) {
    }

    interface PlantedProjection {
        List<BlockRow> findBlocks();
    }

    @Test
    void 이름_검사기는_심어_둔_위반을_잡고_허용_사례는_통과시킨다() {
        List<ValueColumn> values = valueColumns(ExposureCatalog.load(jdbc));
        assertThat(violations(List.of(PlantedViolation.class), values)).singleElement().asString()
                .contains("findMonths").contains("month_block.month_start");
        assertThat(violations(List.of(PlantedNativeText.class), values)).singleElement().asString()
                .contains("findTitles").contains("month_event.title");
        assertThat(violations(List.of(PlantedUpperCaseNative.class), values)).singleElement().asString()
                .contains("findMonthsLoud").contains("month_block.month_start");
        assertThat(violations(List.of(PlantedProjection.class), values)).singleElement().asString()
                .contains("findBlocks").contains("monthBlockId");
        assertThat(violations(List.of(PlantedAllowed.class), values)).isEmpty();
        assertThat(violations(List.of(PlantedJoinKeyOnly.class), values)).isEmpty();
        // "source" also exists on orders: the table name must match too
        assertThat(violations(List.of(PlantedOtherTable.class), values)).isEmpty();
    }

    /** Main sources (paths relative to {@code src/main/java}) whose text contains the token. */
    static Set<String> mainSourcesContaining(String token) throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains(token);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> root.relativize(p).toString().replace('\\', '/')).collect(Collectors.toSet());
        }
    }

    @Test
    void findRevealed는_허용된_소스_파일에서만_나온다() throws IOException {
        assertThat(mainSourcesContaining(PREFIX)).containsExactlyInAnyOrderElementsOf(CALLERS);
    }

    @Test
    void 엔진_예외_메서드는_엔진에서만_부른다() throws IOException {
        assertThat(mainSourcesContaining("findReplayMonths")).containsExactlyInAnyOrderElementsOf(ENGINE_CALLERS);
    }
}

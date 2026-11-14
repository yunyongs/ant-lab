package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.Order;
import antlab.history.MonthBlock;
import antlab.history.MonthEvent;
import antlab.support.ExposureCatalog;
import antlab.support.IntegrationTest;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Tuple;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
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
 * The same holds for any method of a repository whose domain table carries such columns when its return type hides the selection
 * ({@code Object}, {@code Object[]}, {@code Tuple}, {@code Map}, a type variable) or when a query selects {@code *}.
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

    /**
     * True when the declared return type hides what is selected: {@code Object}, {@code Object[]}, {@code Tuple}, a {@code Map}
     * or a type variable / wildcard (dynamic projection), also inside {@code Optional}, collections, streams and pages.
     */
    static boolean untypedShape(Type type) {
        if (type instanceof Class<?> c) {
            return c == Object.class || Tuple.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c)
                    || (c.isArray() && untypedShape(c.getComponentType()));
        }
        if (type instanceof java.lang.reflect.GenericArrayType g) {
            return untypedShape(g.getGenericComponentType());
        }
        if (type instanceof TypeVariable<?> || type instanceof WildcardType) {
            return true;
        }
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() instanceof Class<?> raw && (Map.class.isAssignableFrom(raw) || Tuple.class.isAssignableFrom(raw))) {
                return true;
            }
            Type[] args = p.getActualTypeArguments();
            return args.length > 0 && untypedShape(args[0]);
        }
        return false;
    }

    /** The entity a repository serves, read from its {@code Repository<T, ID>} supertype; null when not found. */
    static Class<?> domainEntity(Class<?> repo) {
        for (Type t : repo.getGenericInterfaces()) {
            if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw && Repository.class.isAssignableFrom(raw)
                    && p.getActualTypeArguments().length > 0 && p.getActualTypeArguments()[0] instanceof Class<?> c
                    && c.isAnnotationPresent(Entity.class)) {
                return c;
            }
        }
        for (Class<?> parent : repo.getInterfaces()) {
            Class<?> found = domainEntity(parent);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String tableOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        StringBuilder out = new StringBuilder();
        for (char c : entity.getSimpleName().toCharArray()) {
            if (Character.isUpperCase(c) && out.length() > 0) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /** {@code select *}, {@code select x.*} or {@code , x.*}: a projection that names no column. */
    private static final Pattern STAR = Pattern.compile("(?i)(\\bselect|,)\\s*(distinct\\s+)?([a-z_][a-z0-9_]*\\.)?\\*");

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
        return violations(repos, values, Map.of());
    }

    /** {@code domains} names the entity of a repository that does not declare one through {@code Repository<T, ID>} (planted cases). */
    static List<String> violations(List<Class<?>> repos, List<ValueColumn> values, Map<Class<?>, Class<?>> domains) {
        List<String> out = new ArrayList<>();
        for (Class<?> repo : repos) {
            Class<?> domain = domains.containsKey(repo) ? domains.get(repo) : domainEntity(repo);
            String domainTable = domain == null ? null : tableOf(domain);
            boolean domainHasValues = domainTable != null && values.stream().anyMatch(v -> v.table().equals(domainTable));
            for (Method m : repo.getMethods()) {
                String id = repo.getSimpleName() + "." + m.getName();
                if (m.getName().startsWith(PREFIX) || ENGINE_EXEMPT.contains(id)) {
                    continue;
                }
                Query query = m.getAnnotation(Query.class);
                if (domainHasValues && untypedShape(m.getGenericReturnType())) {
                    out.add(id + " — untyped return type on " + domainTable + ", which has revealed value columns");
                }
                if (query != null) {
                    Matcher star = STAR.matcher(query.value());
                    if (star.find()) {
                        values.stream().map(ValueColumn::table).distinct()
                                .filter(t -> hasWord(query.value(), t))
                                .forEach(t -> out.add(id + " — query selects * from " + t));
                    }
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

    /** Guards the shape rule against going vacuous: the real repositories must resolve to the tables that carry value columns. */
    @Test
    void 값_열이_있는_표의_저장소는_도메인_표로_찾아진다() throws Exception {
        List<ValueColumn> values = valueColumns(ExposureCatalog.load(jdbc));
        Set<String> valueTables = values.stream().map(ValueColumn::table).collect(Collectors.toSet());
        Set<String> served = new java.util.HashSet<>();
        for (Class<?> repo : repositories()) {
            Class<?> entity = domainEntity(repo);
            if (entity != null && valueTables.contains(tableOf(entity))) {
                served.add(tableOf(entity));
            }
        }
        assertThat(served).contains("month_block", "month_event", "asset");
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

    /**
     * Shapes that hide the selected columns from the text and projection checks. Each is a fake repository served by a
     * domain table with {@code [복기 전용]} value columns; none exists in production code.
     */
    interface PlantedObjectArray {
        Object[] findBlock();
    }

    interface PlantedObjectList {
        List<Object[]> findBlocks();
    }

    interface PlantedTuple {
        @Query("select m.id as id, m.monthStart as start from MonthBlock m")
        List<Tuple> findTuples();
    }

    interface PlantedMap {
        Optional<Map<String, Object>> findAsMap();
    }

    interface PlantedGeneric {
        <T> List<T> findBy(Class<T> projection);
    }

    interface PlantedWildcard {
        List<?> findAnything();
    }

    interface PlantedStarNative {
        @Query(nativeQuery = true, value = "SELECT mb.* FROM month_block mb")
        List<MonthBlock> findAllColumns();
    }

    interface PlantedBareStar {
        @Query(nativeQuery = true, value = "select * from month_event")
        List<MonthEvent> findEverything();
    }

    interface PlantedJoinedStar {
        @Query(nativeQuery = true, value = "select a.id, me.* from asset a join month_event me on me.id = a.id")
        List<MonthEvent> findJoined();
    }

    interface PlantedAllowedShapes {
        Optional<MonthBlock> findById(Long id);

        long count();

        List<Long> findIds();

        @Query(nativeQuery = true, value = "select count(*) from month_block")
        long countBlocks();

        Object[] findRevealedBlock();
    }

    interface PlantedUntypedOtherTable {
        Object[] findRow();

        @Query(nativeQuery = true, value = "select * from orders")
        List<Object[]> findAll();
    }

    @Test
    void 이름_검사기는_반환_형이_가려진_심어_둔_위반도_잡는다() {
        List<ValueColumn> values = valueColumns(ExposureCatalog.load(jdbc));
        Map<Class<?>, Class<?>> blocks = new java.util.HashMap<>();
        for (Class<?> c : List.of(PlantedObjectArray.class, PlantedObjectList.class, PlantedTuple.class, PlantedMap.class,
                PlantedGeneric.class, PlantedWildcard.class, PlantedStarNative.class, PlantedAllowedShapes.class)) {
            blocks.put(c, MonthBlock.class);
        }
        for (Class<?> c : List.of(PlantedBareStar.class, PlantedJoinedStar.class)) {
            blocks.put(c, MonthEvent.class);
        }
        for (Class<?> c : List.of(PlantedUntypedOtherTable.class)) {
            blocks.put(c, Order.class);
        }
        assertThat(violations(List.of(PlantedObjectArray.class), values, blocks)).singleElement().asString()
                .contains("findBlock").contains("untyped return type");
        assertThat(violations(List.of(PlantedObjectList.class), values, blocks)).singleElement().asString()
                .contains("findBlocks").contains("untyped return type");
        assertThat(violations(List.of(PlantedTuple.class), values, blocks)).anyMatch(v -> v.contains("findTuples")
                && v.contains("untyped return type"));
        assertThat(violations(List.of(PlantedMap.class), values, blocks)).singleElement().asString()
                .contains("findAsMap").contains("untyped return type");
        assertThat(violations(List.of(PlantedGeneric.class), values, blocks)).singleElement().asString()
                .contains("findBy").contains("untyped return type");
        assertThat(violations(List.of(PlantedWildcard.class), values, blocks)).singleElement().asString()
                .contains("findAnything").contains("untyped return type");
        assertThat(violations(List.of(PlantedStarNative.class), values, blocks)).singleElement().asString()
                .contains("findAllColumns").contains("selects * from month_block");
        assertThat(violations(List.of(PlantedBareStar.class), values, blocks)).singleElement().asString()
                .contains("findEverything").contains("selects * from month_event");
        // the join names two tables with value columns, so each is reported
        assertThat(violations(List.of(PlantedJoinedStar.class), values, blocks)).hasSize(2)
                .allMatch(v -> v.contains("findJoined")).anyMatch(v -> v.contains("selects * from month_event"))
                .anyMatch(v -> v.contains("selects * from asset"));
        // ordinary reads stay allowed: entity and scalar returns, count(*), and the named reveal method itself
        assertThat(violations(List.of(PlantedAllowedShapes.class), values, blocks)).isEmpty();
        // a table with no value columns may use any shape
        assertThat(violations(List.of(PlantedUntypedOtherTable.class), values, blocks)).isEmpty();
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

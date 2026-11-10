package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import antlab.support.MappingModel;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** 불변 엔티티: setter·공개 생성자가 없고, 필드를 바꿔 flush 해도 DB 값이 그대로다. */
@JpaMappingTest
@Sql("/fixtures/history_pool.sql")
class ImmutableEntityTest {

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private List<Class<?>> immutableEntities() {
        return MappingModel.entities(em).stream().filter(c -> c.isAnnotationPresent(Immutable.class)).toList();
    }

    @Test
    void 읽기_전용_엔티티는_여덟_개다() {
        assertThat(immutableEntities().stream().map(Class::getSimpleName))
                .containsExactlyInAnyOrder("AssetGroup", "Asset", "AssetPosition", "MonthBlock",
                        "AssetMonthReturn", "FxMonth", "MonthEvent", "AssetMonthAttention");
    }

    @Test
    void 엔티티에는_setter와_인자_있는_공개_생성자가_없다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> c : immutableEntities()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().startsWith("set") && !Modifier.isPrivate(m.getModifiers())) {
                    violations.add(c.getSimpleName() + "." + m.getName());
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (Modifier.isPublic(k.getModifiers())) {
                    violations.add(c.getSimpleName() + " 공개 생성자");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 필드를_바꾸고_flush_해도_DB_값은_그대로다() throws Exception {
        for (Class<?> entity : immutableEntities()) {
            String table = MappingModel.table(entity);
            Long id = jdbc.queryForObject("SELECT min(id) FROM " + table, Long.class);
            assertThat(id).as(table + " 고정본 행").isNotNull();
            String before = jdbc.queryForObject("SELECT to_jsonb(t)::text FROM " + table + " t WHERE id = ?",
                    String.class, id);

            Object loaded = em.find(entity, id);
            assertThat(loaded).as(table).isNotNull();
            int changed = 0;
            for (Field f : MappingModel.fields(entity)) {
                if (MappingModel.isId(f)) {
                    continue;
                }
                f.setAccessible(true);
                Object next = changeOf(f.get(loaded), f.getType());
                if (next != null) {
                    f.set(loaded, next);
                    changed++;
                }
            }
            assertThat(changed).as(table + " 에서 바꾼 필드 수").isPositive();
            em.flush();
            em.clear();

            String after = jdbc.queryForObject("SELECT to_jsonb(t)::text FROM " + table + " t WHERE id = ?",
                    String.class, id);
            assertThat(after).as(table + " 의 DB 행").isEqualTo(before);
        }
    }

    /** 값을 다른 값으로 바꾼다. 값이 NULL 이면 바꾸지 않는다. 바뀐 값이 DB 에 가면 길이·제약 위반이 날 수도 있다 — 그것도 실패다. */
    private static Object changeOf(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isEmpty() ? "X" : s.substring(1);
        }
        if (value instanceof BigDecimal b) {
            return b.add(BigDecimal.ONE);
        }
        if (value instanceof Long l) {
            return l + 1;
        }
        if (value instanceof Integer i) {
            return i + 1;
        }
        if (value instanceof Boolean b) {
            return !b;
        }
        if (value instanceof LocalDate d) {
            return d.plusDays(1);
        }
        if (value instanceof Instant t) {
            return t.plusSeconds(1);
        }
        if (type.isEnum()) {
            Object[] all = type.getEnumConstants();
            return all[(((Enum<?>) value).ordinal() + 1) % all.length];
        }
        throw new IllegalStateException("바꾸는 방법이 없는 형 " + type);
    }

    /** 가변 엔티티 허용 목록. 지금은 비어 있고, P0-28 이 가변 5개(member·season·cash_account·holding·lineup)를 더한다. */
    private static final Set<String> MUTABLE_ALLOWED = Set.of();

    @Test
    void 모든_엔티티는_불변이거나_가변_허용_목록에_있다() {
        assertThat(MappingModel.entities(em))
                .filteredOn(c -> !c.isAnnotationPresent(Immutable.class))
                .extracting(Class::getSimpleName)
                .allMatch(MUTABLE_ALLOWED::contains);
    }
}

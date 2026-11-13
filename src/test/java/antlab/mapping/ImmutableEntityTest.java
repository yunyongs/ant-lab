package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FieldMutator;
import antlab.support.JpaMappingTest;
import antlab.support.MappingModel;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

    /** 읽기 전용 8개: 적재 SQL 이 만든 행을 읽기만 한다. 공개 생성자가 없다. */
    static final Set<String> READ_ONLY = Set.of("AssetGroup", "Asset", "AssetPosition", "MonthBlock",
            "AssetMonthReturn", "FxMonth", "MonthEvent", "AssetMonthAttention");

    /** 추가 전용 9개: 넣을 수는 있고 넣은 뒤 바뀌지 않는다. 공개 생성자가 정확히 하나다. */
    static final Set<String> APPEND_ONLY = Set.of("SeasonPath", "Turn", "TurnPrice", "FormationChange", "TurnFeed",
            "ReconciliationLog", "Order", "Execution", "CashLedger");

    /** 가변 6개: 상태 변경은 이름 있는 메서드만 한다(setter 없음). 분류를 바꾸려면 이 목록을 고쳐 리뷰에 드러낸다. */
    static final Set<String> MUTABLE = Set.of("Member", "Season", "Lineup", "CashAccount", "Holding",
            "RefreshToken");

    private List<Class<?>> immutableEntities() {
        return MappingModel.entities(em).stream().filter(c -> c.isAnnotationPresent(Immutable.class)).toList();
    }

    private List<Class<?>> readOnlyEntities() {
        return immutableEntities().stream().filter(c -> READ_ONLY.contains(c.getSimpleName())).toList();
    }

    @Test
    void 엔티티_분류는_읽기_전용_8_추가_전용_9_가변_6이다() {
        assertThat(immutableEntities().stream().map(Class::getSimpleName))
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(READ_ONLY.stream(), APPEND_ONLY.stream()).toList());
        assertThat(MappingModel.entities(em).stream().filter(c -> !c.isAnnotationPresent(Immutable.class))
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrderElementsOf(MUTABLE);
    }

    @Test
    void 어떤_엔티티에도_setter가_없다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> c : MappingModel.entities(em)) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().startsWith("set") && !Modifier.isPrivate(m.getModifiers())) {
                    violations.add(c.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 읽기_전용은_공개_생성자가_없고_나머지는_하나다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> c : MappingModel.entities(em)) {
            long publicCount = java.util.Arrays.stream(c.getDeclaredConstructors())
                    .filter(k -> Modifier.isPublic(k.getModifiers())).count();
            String name = c.getSimpleName();
            if (READ_ONLY.contains(name) && publicCount != 0) {
                violations.add(name + " 공개 생성자 " + publicCount + "개");
            }
            if (!READ_ONLY.contains(name) && publicCount != 1) {
                violations.add(name + " 공개 생성자 " + publicCount + "개(하나여야 함)");
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (k.getParameterCount() == 0 && !Modifier.isProtected(k.getModifiers())) {
                    violations.add(name + " 기본 생성자가 protected 가 아님");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 필드를_바꾸고_flush_해도_DB_값은_그대로다() throws Exception {
        for (Class<?> entity : readOnlyEntities()) {
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
                Object next = FieldMutator.changeOf(f.get(loaded), f.getType());
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
}

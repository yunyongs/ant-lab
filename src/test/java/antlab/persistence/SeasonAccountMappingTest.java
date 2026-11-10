package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.persistence.AuditedEntity;
import antlab.common.persistence.CreatedEntity;
import antlab.support.JpaMappingTest;
import antlab.support.MappingModel;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 시즌·계좌 쪽 14개 엔티티의 매핑 모양: 이름·표·키·버전·연관·감사 부모. 열 단위 대조는 mapping 패키지의 공용 시험이 맡는다. */
@JpaMappingTest
class SeasonAccountMappingTest {

    /** 엔티티 이름 → 표 이름. {@code Order} 는 JPQL 예약어를 피해 {@code Orders} 로 부른다. */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("Member", "member"), Map.entry("Season", "season"), Map.entry("SeasonPath", "season_path"),
            Map.entry("Turn", "turn"), Map.entry("TurnPrice", "turn_price"), Map.entry("Lineup", "lineup"),
            Map.entry("FormationChange", "formation_change"), Map.entry("TurnFeed", "turn_feed"),
            Map.entry("ReconciliationLog", "reconciliation_log"), Map.entry("CashAccount", "cash_account"),
            Map.entry("Orders", "orders"), Map.entry("Execution", "execution"), Map.entry("Holding", "holding"),
            Map.entry("CashLedger", "cash_ledger"));

    @Autowired
    EntityManager em;

    private List<Class<?>> mine() {
        return MappingModel.entities(em).stream()
                .filter(c -> EXPECTED.containsKey(c.getAnnotation(Entity.class).name().isEmpty()
                        ? c.getSimpleName() : c.getAnnotation(Entity.class).name()))
                .toList();
    }

    @Test
    void 메타모델에_열네_개_엔티티_이름이_있다() {
        Set<String> names = em.getMetamodel().getEntities().stream()
                .map(e -> e.getName()).collect(Collectors.toSet());
        assertThat(names).containsAll(EXPECTED.keySet());
        assertThat(mine()).hasSize(14);
    }

    @Test
    void 표_이름이_정의서와_같다() {
        for (Class<?> c : mine()) {
            String entityName = c.getAnnotation(Entity.class).name().isEmpty()
                    ? c.getSimpleName() : c.getAnnotation(Entity.class).name();
            assertThat(MappingModel.table(c)).as(entityName).isEqualTo(EXPECTED.get(entityName));
        }
    }

    @Test
    void 키는_cash_account만_할당_키이고_나머지는_IDENTITY다() {
        for (Class<?> c : mine()) {
            Field id = MappingModel.fields(c).stream().filter(MappingModel::isId).findFirst().orElseThrow();
            GeneratedValue gv = id.getAnnotation(GeneratedValue.class);
            if (c.getSimpleName().equals("CashAccount")) {
                assertThat(gv).as("CashAccount 키는 시즌 번호를 그대로 쓴다").isNull();
                assertThat(id.getName()).isEqualTo("seasonId");
            } else {
                assertThat(gv).as(c.getSimpleName()).isNotNull();
                assertThat(gv.strategy()).as(c.getSimpleName()).isEqualTo(GenerationType.IDENTITY);
            }
        }
    }

    @Test
    void 낙관적_락_버전은_season과_cash_account에만_있고_래퍼_Long이다() {
        Map<String, Class<?>> versions = new java.util.TreeMap<>();
        for (Class<?> c : MappingModel.entities(em)) {
            for (Field f : MappingModel.fields(c)) {
                if (f.isAnnotationPresent(Version.class)) {
                    versions.put(c.getSimpleName(), f.getType());
                }
            }
        }
        assertThat(versions).containsOnlyKeys("CashAccount", "Season");
        assertThat(versions.values()).containsOnly(Long.class);
    }

    @Test
    void 연관은_ManyToOne_LAZY_필수뿐이다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> c : MappingModel.entities(em)) {
            for (Field f : MappingModel.fields(c)) {
                if (f.isAnnotationPresent(OneToMany.class) || f.isAnnotationPresent(OneToOne.class)
                        || f.isAnnotationPresent(ManyToMany.class)) {
                    violations.add(c.getSimpleName() + "." + f.getName() + " 컬렉션·일대일 연관");
                }
                ManyToOne m = f.getAnnotation(ManyToOne.class);
                if (m != null && (m.fetch() != FetchType.LAZY || m.optional())) {
                    violations.add(c.getSimpleName() + "." + f.getName() + " 은 LAZY·optional=false 여야 한다");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 연관을_가진_엔티티는_정해진_일곱_개뿐이다() {
        // 같은 패키지의 가변 부모(Season, CashAccount)를 가리키는 경우만 연관이고 나머지는 Long 번호다(SPRING_JPA §4.3)
        List<String> withAssociation = new ArrayList<>();
        for (Class<?> c : MappingModel.entities(em)) {
            if (MappingModel.fields(c).stream().anyMatch(f -> f.isAnnotationPresent(ManyToOne.class))) {
                withAssociation.add(c.getSimpleName());
            }
        }
        assertThat(withAssociation).containsExactlyInAnyOrder("SeasonPath", "Turn", "TurnPrice", "Lineup",
                "FormationChange", "TurnFeed", "CashLedger");
    }

    @Test
    void 감사_부모는_가변이면_AuditedEntity_추가_전용이면_CreatedEntity다() {
        Set<String> mutable = Set.of("Member", "Season", "Lineup", "CashAccount", "Holding");
        for (Class<?> c : mine()) {
            String name = c.getSimpleName();
            if (mutable.contains(name)) {
                assertThat(AuditedEntity.class.isAssignableFrom(c)).as(name).isTrue();
            } else {
                assertThat(CreatedEntity.class.isAssignableFrom(c)).as(name).isTrue();
                assertThat(AuditedEntity.class.isAssignableFrom(c)).as(name + " 은 updated_at 이 없다").isFalse();
            }
        }
    }

    @Test
    void 모든_엔티티의_키_필드가_정확히_하나다() {
        for (Class<?> c : MappingModel.entities(em)) {
            assertThat(MappingModel.fields(c).stream().filter(f -> f.isAnnotationPresent(Id.class)))
                    .as(c.getSimpleName()).hasSize(1);
        }
    }
}

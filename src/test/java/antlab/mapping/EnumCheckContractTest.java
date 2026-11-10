package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.CheckConstraintCatalog;
import antlab.support.JpaMappingTest;
import antlab.support.MappingModel;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * enum 상수 ↔ DB CHECK 값 목록을 글자 단위로 대조한다. 모든 {@code @Entity} 를 훑으므로
 * 엔티티를 더하면 이 시험에 자동으로 들어온다.
 */
@JpaMappingTest
class EnumCheckContractTest {

    /** 예외: fx_month.currency 는 원화 행이 없어 CHECK(USD) ⊆ enum(KRW, USD). 이유는 erd 5.3 fx_month. */
    private static final Set<String> SUBSET_ALLOWED = Set.of("fx_month.currency");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void enum_필드는_모두_문자열_저장이다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> entity : MappingModel.entities(em)) {
            for (Field f : MappingModel.fields(entity)) {
                if (f.getType().isEnum() && !MappingModel.isStringEnum(f)) {
                    violations.add(entity.getSimpleName() + "." + f.getName());
                }
            }
        }
        assertThat(violations).as("@Enumerated(STRING) 이 아닌 enum 필드").isEmpty();
    }

    @Test
    void enum_상수와_CHECK_값_목록이_글자_단위로_같다() {
        Map<String, Set<String>> checks = CheckConstraintCatalog.valueLists(jdbc);
        List<String> diffs = new ArrayList<>();
        int compared = 0;
        for (Class<?> entity : MappingModel.entities(em)) {
            for (Field f : MappingModel.fields(entity)) {
                if (!MappingModel.isStringEnum(f)) {
                    continue;
                }
                String key = MappingModel.table(entity) + "." + MappingModel.column(f);
                Set<String> constants = new LinkedHashSet<>();
                Arrays.stream(f.getType().getEnumConstants()).forEach(c -> constants.add(((Enum<?>) c).name()));
                Set<String> check = checks.get(key);
                if (check == null) {
                    diffs.add(key + " — CHECK 값 목록 없음(ck_표_열 이름과 IN 목록이 필요)");
                    continue;
                }
                compared++;
                boolean same = SUBSET_ALLOWED.contains(key) ? constants.containsAll(check) : constants.equals(check);
                if (!same) {
                    diffs.add(key + " — enum " + constants + " / CHECK " + check);
                }
            }
        }
        assertThat(diffs).isEmpty();
        assertThat(compared).as("대조한 enum 열 수").isGreaterThanOrEqualTo(14);
    }

    @Test
    void 매핑된_표의_값_목록_CHECK는_모두_enum_필드다() {
        Map<String, Set<String>> checks = CheckConstraintCatalog.valueLists(jdbc);
        Set<String> enumColumns = new LinkedHashSet<>();
        Set<String> mappedTables = new LinkedHashSet<>();
        for (Class<?> entity : MappingModel.entities(em)) {
            mappedTables.add(MappingModel.table(entity));
            for (Field f : MappingModel.fields(entity)) {
                if (MappingModel.isStringEnum(f)) {
                    enumColumns.add(MappingModel.table(entity) + "." + MappingModel.column(f));
                }
            }
        }
        List<String> leaked = checks.keySet().stream()
                .filter(k -> mappedTables.contains(k.substring(0, k.indexOf('.'))))
                .filter(k -> !enumColumns.contains(k))
                .toList();
        assertThat(leaked).as("값 목록 CHECK 가 있는데 enum 이 아닌 열").isEmpty();
    }
}

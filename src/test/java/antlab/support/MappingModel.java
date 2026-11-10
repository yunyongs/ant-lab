package antlab.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jakarta.persistence.EntityManager;

/** 매핑 계약 시험이 함께 쓰는 읽기 도구: 매핑된 엔티티 목록, 표·열 이름, 필드 목록. */
public final class MappingModel {

    private MappingModel() {
    }

    /** JPA 메타모델에 올라온 모든 {@code @Entity} 클래스(이름 순). */
    public static List<Class<?>> entities(EntityManager em) {
        return em.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .filter(c -> c.isAnnotationPresent(Entity.class))
                .<Class<?>>map(c -> c)
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    public static String table(Class<?> entity) {
        return entity.getAnnotation(Table.class).name();
    }

    /** 영속 필드(정적·{@code @Transient} 제외). */
    public static List<Field> fields(Class<?> entity) {
        List<Field> result = new ArrayList<>();
        for (Field f : entity.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()
                    && !f.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                result.add(f);
            }
        }
        return result;
    }

    public static String column(Field f) {
        return f.getAnnotation(Column.class).name();
    }

    public static boolean isId(Field f) {
        return f.isAnnotationPresent(Id.class);
    }

    /** 문자열로 저장하는 enum 필드인가. */
    public static boolean isStringEnum(Field f) {
        Enumerated e = f.getAnnotation(Enumerated.class);
        return f.getType().isEnum() && e != null && e.value() == EnumType.STRING;
    }
}

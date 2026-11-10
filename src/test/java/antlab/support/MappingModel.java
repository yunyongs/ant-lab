package antlab.support;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 매핑 계약 시험이 함께 쓰는 읽기 도구: 매핑된 엔티티 목록, 표 이름, 열 목록.
 * 열 목록은 {@code @MappedSuperclass} 부모의 필드, {@code @Embedded}(+{@code @AttributeOverride}) 로 펼쳐지는 열,
 * {@code @ManyToOne} 의 {@code @JoinColumn} 열까지 포함한다.
 */
public final class MappingModel {

    private MappingModel() {
    }

    /**
     * 열 하나의 매핑 선언.
     *
     * @param source 사람이 읽는 출처(오류 메시지용), 예: {@code Season.startFormation.goalkeeper}
     */
    public record Col(String source, String name, Class<?> type, int length, int precision, int scale,
            boolean nullable, boolean id, boolean version, boolean stringEnum, boolean json, boolean smallint) {

        /** 원시형이면 래퍼로. */
        public Class<?> boxed() {
            if (type == int.class) {
                return Integer.class;
            }
            if (type == long.class) {
                return Long.class;
            }
            if (type == boolean.class) {
                return Boolean.class;
            }
            return type;
        }

        public boolean primitive() {
            return type.isPrimitive();
        }

        /** {@code @Column} 하나만 붙은 단순 필드 → 열 선언. */
        public static Col of(Field f) {
            Column c = f.getAnnotation(Column.class);
            if (c == null) {
                throw new IllegalStateException("필드 " + describe(f) + " — @Column/@AttributeOverride 없음");
            }
            return of(f, describe(f), c);
        }

        static Col of(Field f, String source, Column c) {
            JdbcTypeCode jtc = f.getAnnotation(JdbcTypeCode.class);
            return new Col(source, c.name(), f.getType(), c.length(), c.precision(), c.scale(), c.nullable(),
                    f.isAnnotationPresent(Id.class), f.isAnnotationPresent(Version.class), isStringEnum(f),
                    jtc != null && jtc.value() == SqlTypes.JSON, jtc != null && jtc.value() == SqlTypes.SMALLINT);
        }
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

    /** 영속 필드(정적·{@code @Transient} 제외). {@code @MappedSuperclass} 부모의 필드가 앞에 온다. */
    public static List<Field> fields(Class<?> entity) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c == entity || c.isAnnotationPresent(MappedSuperclass.class)) {
                chain.add(0, c);
            }
        }
        List<Field> result = new ArrayList<>();
        for (Class<?> c : chain) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()
                        && !f.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                    result.add(f);
                }
            }
        }
        return result;
    }

    /**
     * 엔티티의 모든 열 선언. 열 이름을 알 수 없는 필드(@Column·@JoinColumn·@AttributeOverride 모두 없음)는
     * "필드 X — @Column/@AttributeOverride 없음" 으로 즉시 실패한다.
     */
    public static List<Col> columns(Class<?> entity) {
        List<Col> result = new ArrayList<>();
        for (Field f : fields(entity)) {
            if (f.isAnnotationPresent(Embedded.class)) {
                result.addAll(embeddedColumns(f));
            } else if (f.isAnnotationPresent(ManyToOne.class)) {
                JoinColumn j = f.getAnnotation(JoinColumn.class);
                if (j == null) {
                    throw new IllegalStateException("필드 " + describe(f) + " — @JoinColumn 없음");
                }
                result.add(new Col(describe(f), j.name(), Long.class, 0, 0, 0, j.nullable(), false, false, false,
                        false, false));
            } else {
                result.add(Col.of(f));
            }
        }
        return result;
    }

    private static List<Col> embeddedColumns(Field owner) {
        List<AttributeOverride> overrides = new ArrayList<>();
        AttributeOverrides many = owner.getAnnotation(AttributeOverrides.class);
        if (many != null) {
            overrides.addAll(List.of(many.value()));
        }
        AttributeOverride one = owner.getAnnotation(AttributeOverride.class);
        if (one != null) {
            overrides.add(one);
        }
        List<Col> result = new ArrayList<>();
        for (Field component : owner.getType().getDeclaredFields()) {
            if (Modifier.isStatic(component.getModifiers())) {
                continue;
            }
            String source = describe(owner) + "." + component.getName();
            Column column = overrides.stream()
                    .filter(o -> o.name().equals(component.getName()))
                    .map(AttributeOverride::column)
                    .findFirst()
                    .orElse(component.getAnnotation(Column.class));
            if (column == null) {
                throw new IllegalStateException("필드 " + source + " — @Column/@AttributeOverride 없음");
            }
            result.add(Col.of(component, source, column));
        }
        return result;
    }

    private static String describe(Field f) {
        return f.getDeclaringClass().getSimpleName() + "." + f.getName();
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

package antlab.support;

import antlab.season.Formation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 불변 엔티티 시험용: 필드 값을 "다른 값"으로 바꾼다. 값이 NULL 이면 바꾸지 않는다(null 반환). */
public final class FieldMutator {

    private FieldMutator() {
    }

    public static Object changeOf(Object value, Class<?> type) {
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
        if (value instanceof Formation) {
            return new Formation(10, 20, 30, 40);
        }
        if (type.isEnum()) {
            Object[] all = type.getEnumConstants();
            return all[(((Enum<?>) value).ordinal() + 1) % all.length];
        }
        throw new IllegalStateException("바꾸는 방법이 없는 형 " + type);
    }
}

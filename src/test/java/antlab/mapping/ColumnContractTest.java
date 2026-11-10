package antlab.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import antlab.support.MappingModel;
import antlab.support.MappingModel.Col;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 열 계약. Hibernate {@code validate} 는 형 계열만 보고 길이·정밀도·NULL 허용은 보지 않으므로
 * 그 빈칸을 {@code information_schema.columns} 와의 대조로 메운다.
 */
@JpaMappingTest
class ColumnContractTest {

    record DbColumn(String dataType, Integer length, Integer precision, Integer scale, boolean nullable) {
    }

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, DbColumn> dbColumns() {
        Map<String, DbColumn> map = new HashMap<>();
        jdbc.query("""
                SELECT table_name, column_name, data_type, character_maximum_length,
                       numeric_precision, numeric_scale, is_nullable
                FROM information_schema.columns WHERE table_schema = 'public'
                """, rs -> {
            map.put(rs.getString(1) + "." + rs.getString(2), new DbColumn(
                    rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                    (Integer) rs.getObject(6), "YES".equals(rs.getString(7))));
        });
        return map;
    }

    /** 열 선언 하나가 DB 열과 어긋나는 점들. 형 대응표에 없는 Java 형은 그것도 위반으로 보고한다. */
    static List<String> mismatches(Col col, DbColumn db) {
        List<String> out = new ArrayList<>();
        Class<?> t = col.boxed();
        if (!col.id() && col.nullable() != db.nullable()) {
            out.add("nullable 애너테이션 " + col.nullable() + " / DB " + db.nullable());
        }
        if (col.id() && db.nullable()) {
            out.add("키 열이 NULL 허용");
        }
        if (col.primitive() && db.nullable()) {
            out.add("원시형 필드인데 DB 열이 NULL 허용");
        }
        if (col.version() && col.primitive()) {
            out.add("버전 필드는 래퍼 형이어야 한다(null = 새 엔티티)");
        }
        if (t == String.class && col.json()) {
            expect(out, "jsonb", db.dataType());
        } else if (t == String.class || col.stringEnum()) {
            expect(out, "character varying", db.dataType());
            if (db.length() == null || col.length() != db.length()) {
                out.add("length " + col.length() + " / DB " + db.length());
            }
        } else if (t == BigDecimal.class) {
            expect(out, "numeric", db.dataType());
            if (db.precision() == null || col.precision() != db.precision()) {
                out.add("precision " + col.precision() + " / DB " + db.precision());
            }
            if (db.scale() == null || col.scale() != db.scale()) {
                out.add("scale " + col.scale() + " / DB " + db.scale());
            }
        } else if (t == Long.class) {
            expect(out, "bigint", db.dataType());
        } else if (t == Integer.class) {
            expect(out, col.smallint() ? "smallint" : "integer", db.dataType());
        } else if (t == Boolean.class) {
            expect(out, "boolean", db.dataType());
        } else if (t == LocalDate.class) {
            expect(out, "date", db.dataType());
        } else if (t == Instant.class) {
            expect(out, "timestamp with time zone", db.dataType());
        } else {
            out.add("형 대응표에 없는 Java 형 " + t.getSimpleName());
        }
        return out;
    }

    private static void expect(List<String> out, String expected, String actual) {
        if (!expected.equals(actual)) {
            out.add("DB 형 " + actual + " / 기대 " + expected);
        }
    }

    @Test
    void 매핑한_열은_DB_열의_길이_정밀도_NULL_형과_같다() {
        Map<String, DbColumn> db = dbColumns();
        List<String> problems = new ArrayList<>();
        for (Class<?> entity : MappingModel.entities(em)) {
            for (Col col : MappingModel.columns(entity)) {
                String key = MappingModel.table(entity) + "." + col.name();
                DbColumn dbCol = db.get(key);
                if (dbCol == null) {
                    problems.add(key + " — DB 에 열 없음");
                    continue;
                }
                mismatches(col, dbCol).forEach(m -> problems.add(key + " (" + col.source() + ") — " + m));
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void 매핑된_표의_모든_열이_엔티티에_있다() {
        Map<String, DbColumn> db = dbColumns();
        Set<String> mapped = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        for (Class<?> entity : MappingModel.entities(em)) {
            tables.add(MappingModel.table(entity));
            MappingModel.columns(entity).forEach(c -> mapped.add(MappingModel.table(entity) + "." + c.name()));
        }
        List<String> missing = db.keySet().stream()
                .filter(k -> tables.contains(k.substring(0, k.indexOf('.'))))
                .filter(k -> !mapped.contains(k))
                .sorted()
                .toList();
        assertThat(missing).as("엔티티에 빠진 열").isEmpty();
        assertThat(tables).as("매핑한 표").hasSizeGreaterThanOrEqualTo(22);
    }

    @Test
    void 검사기는_어긋난_길이와_정밀도를_잡는다() throws Exception {
        class Probe {
            @Column(name = "ticker", length = 13, nullable = false)
            String ticker;
            @Column(name = "v", precision = 12, scale = 4, nullable = true)
            BigDecimal v;
        }
        List<String> a = mismatches(Col.of(Probe.class.getDeclaredField("ticker")),
                new DbColumn("character varying", 12, null, null, false));
        List<String> b = mismatches(Col.of(Probe.class.getDeclaredField("v")),
                new DbColumn("numeric", null, 12, 8, true));
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
        assertThat(b.get(0)).contains("scale");
    }

    @Test
    void 검사기는_JSON_SMALLINT_원시형_버전_위반을_잡는다() throws Exception {
        class Probe {
            @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
            @Column(name = "j", nullable = true)
            String json;
            @Column(name = "n", nullable = false)
            int plain;
            @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.SMALLINT)
            @Column(name = "s", nullable = false)
            int small;
            @jakarta.persistence.Version
            @Column(name = "v", nullable = false)
            long version;
        }
        assertThat(mismatches(Col.of(Probe.class.getDeclaredField("json")),
                new DbColumn("jsonb", null, null, null, true))).isEmpty();
        assertThat(mismatches(Col.of(Probe.class.getDeclaredField("json")),
                new DbColumn("character varying", 10, null, null, true))).hasSize(1);
        assertThat(mismatches(Col.of(Probe.class.getDeclaredField("plain")),
                new DbColumn("smallint", null, 16, 0, false))).hasSize(1);
        assertThat(mismatches(Col.of(Probe.class.getDeclaredField("small")),
                new DbColumn("smallint", null, 16, 0, false))).isEmpty();
        assertThat(mismatches(Col.of(Probe.class.getDeclaredField("version")),
                new DbColumn("bigint", null, 64, 0, false))).hasSize(1);
    }

    @Test
    void 열_이름을_알_수_없는_필드는_위치를_밝히며_실패한다() {
        class Bare {
            @SuppressWarnings("unused")
            String naked;
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MappingModel.columns(Bare.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bare.naked")
                .hasMessageContaining("@Column/@AttributeOverride 없음");
    }
}

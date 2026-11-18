package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.AuthSchemaCatalogTest.Obj;
import antlab.support.ExposureCatalog;
import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Compares the constraints and indexes of V12 one to one with the erd 10.6.3 table, the columns with the 10.6.2 definitions,
 * and checks that the V11 to V12 catalog difference is exactly the two new tables plus the widened job_run check.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MacroSchemaCatalogTest {

    private static final Path ERD = Path.of("docs/erd.md");
    private static final Set<String> TABLES = Set.of("series", "series_observation");
    private static final List<String> KINDS = List.of("PK", "UQ", "UQI", "FK", "CK", "IDX");

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PostgreSQLContainer postgres;

    private static String section(String from, String to) throws IOException {
        String text = Files.readString(ERD, StandardCharsets.UTF_8);
        int start = text.indexOf("### 10.6 ");
        String macro = text.substring(start, text.indexOf("## 11. ", start));
        return macro.substring(macro.indexOf(from), macro.indexOf(to));
    }

    static List<Obj> erdObjects() throws IOException {
        List<Obj> out = new ArrayList<>();
        for (String line : section("**10.6.3 ", "**Counts").split("\n")) {
            String[] cells = AuthSchemaCatalogTest.cells(line);
            if (cells != null && cells.length == 4 && TABLES.contains(cells[2])) {
                out.add(new Obj(cells[0], cells[1], cells[2], cells[3]));
            }
        }
        return out;
    }

    private List<Obj> actualObjects() {
        List<Obj> out = new ArrayList<>();
        jdbc.query("""
                SELECT conname, contype, conrelid::regclass::text, pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND conrelid IN ('series'::regclass, 'series_observation'::regclass)
                """, rs -> {
            String kind = switch (rs.getString(2)) {
                case "p" -> "PK";
                case "u" -> "UQ";
                case "f" -> "FK";
                case "c" -> "CK";
                default -> throw new IllegalStateException("unexpected contype " + rs.getString(2));
            };
            out.add(new Obj(rs.getString(1), kind, rs.getString(3), rs.getString(4)));
        });
        jdbc.query("""
                SELECT i.relname, ix.indisunique, ix.indrelid::regclass::text, pg_get_indexdef(ix.indexrelid)
                FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid
                WHERE ix.indrelid IN ('series'::regclass, 'series_observation'::regclass)
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = ix.indexrelid)
                """, rs -> {
            out.add(new Obj(rs.getString(1), rs.getBoolean(2) ? "UQI" : "IDX", rs.getString(3), rs.getString(4)));
        });
        return out;
    }

    @Test
    void erd와_카탈로그의_이름_종류_테이블이_일대일이다() throws IOException {
        List<Obj> expected = erdObjects();
        List<Obj> actual = actualObjects();

        assertThat(expected).as("erd 10.6.3 의 V12 행").hasSize(20);
        assertThat(actual.stream().map(o -> o.name() + "/" + o.kind() + "/" + o.table()).sorted())
                .containsExactlyElementsOf(expected.stream().map(o -> o.name() + "/" + o.kind() + "/" + o.table()).sorted().toList());
    }

    @Test
    void erd의_정의를_다시_파싱한_결과가_카탈로그_정의와_같다() throws Exception {
        Map<String, Obj> actual = actualObjects().stream().collect(Collectors.toMap(Obj::name, o -> o));
        List<String> problems = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE TEMP TABLE chk_series (LIKE series)");
                s.execute("CREATE TEMP TABLE chk_series_observation (LIKE series_observation)");
                for (Obj e : erdObjects()) {
                    Obj a = actual.get(e.name());
                    String temp = "chk_" + e.table();
                    switch (e.kind()) {
                        case "PK" -> AuthSchemaCatalogTest.reparsedConstraint(s, problems, e, a, temp, "PRIMARY KEY " + e.definition());
                        case "UQ" -> AuthSchemaCatalogTest.reparsedConstraint(s, problems, e, a, temp, "UNIQUE " + e.definition());
                        case "CK" -> AuthSchemaCatalogTest.reparsedConstraint(s, problems, e, a, temp, "CHECK (" + e.definition() + ")");
                        case "UQI" -> AuthSchemaCatalogTest.reparsedIndex(s, problems, e, a, temp, "CREATE UNIQUE INDEX ");
                        case "IDX" -> AuthSchemaCatalogTest.reparsedIndex(s, problems, e, a, temp, "CREATE INDEX ");
                        case "FK" -> problems.addAll(AuthSchemaCatalogTest.foreignKeyProblems(c, e));
                        default -> problems.add(e.name() + ": 알 수 없는 종류 " + e.kind());
                    }
                }
            } finally {
                c.rollback();
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void V11에서_V12로의_카탈로그_차이는_정확히_두_표와_넓어진_잡_이름_검사뿐이다() throws Exception {
        Set<String> v12;
        Set<String> v11;
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v12_macro")) {
            scratch.migrateTo("11");
            v11 = AuthSchemaCatalogTest.snapshot(new JdbcTemplate(scratch.dataSource()));
            scratch.migrateTo("12");
            v12 = AuthSchemaCatalogTest.snapshot(new JdbcTemplate(scratch.dataSource()));
        }
        Set<String> added = new TreeSet<>(v12);
        added.removeAll(v11);
        Set<String> removed = new TreeSet<>(v11);
        removed.removeAll(v12);

        assertThat(removed).as("V12 가 지우거나 바꾼 것").hasSize(1).allMatch(r -> r.startsWith("constraint job_run.ck_job_run_job_name "));
        assertThat(added.stream().filter(a -> a.contains("ck_job_run_job_name")).toList())
                .singleElement().asString().contains("'KIS_DAILY_PRICE'").contains("'FRED_SERIES'").contains("'ECOS_SERIES'");
        List<String> names = added.stream().filter(a -> a.startsWith("constraint ") || a.startsWith("index "))
                .map(a -> a.split(" ")[1].split("\\.")[1]).distinct().sorted().toList();
        List<String> expectedNames = new ArrayList<>(erdObjects().stream().map(Obj::name).toList());
        expectedNames.add("ck_job_run_job_name");
        assertThat(names).containsExactlyElementsOf(expectedNames.stream().sorted().toList());
        assertThat(added.stream().filter(a -> a.startsWith("constraint ")).count()).as("새 제약(넓힌 검사 포함)").isEqualTo(19);
        assertThat(added.stream().filter(a -> a.startsWith("index ")).count()).as("새 인덱스(제약 인덱스 포함)").isEqualTo(7);
        assertThat(added.stream().filter(a -> a.startsWith("column ")).map(a -> a.split(" ")[1])
                .map(c -> c.substring(0, c.indexOf('.'))).distinct().sorted()).containsExactly("series", "series_observation");
        assertThat(added.stream().filter(a -> a.startsWith("table ")).map(a -> a.split(" ")[1]).sorted())
                .containsExactly("series", "series_observation");
    }

    @Test
    void 테이블별_개수가_erd_개수_표와_같다() throws IOException {
        Map<String, int[]> erdCounts = new LinkedHashMap<>();
        for (String line : section("**Counts", "**10.6.4 ").split("\n")) {
            String[] cells = AuthSchemaCatalogTest.cells(line);
            if (cells != null && cells.length == 7 && TABLES.contains(cells[0])) {
                int[] n = new int[6];
                for (int i = 0; i < 6; i++) {
                    n[i] = Integer.parseInt(cells[i + 1]);
                }
                erdCounts.put(cells[0], n);
            }
        }
        Map<String, int[]> actualCounts = new LinkedHashMap<>();
        TABLES.forEach(t -> actualCounts.put(t, new int[6]));
        for (Obj o : actualObjects()) {
            actualCounts.get(o.table())[KINDS.indexOf(o.kind())]++;
        }
        assertThat(erdCounts.keySet()).containsExactlyInAnyOrderElementsOf(TABLES);
        erdCounts.forEach((table, counts) -> assertThat(counts).as(table).containsExactly(actualCounts.get(table)));
    }

    /** Column rows of erd 10.6.2 as {@code table.column} to {@code type NULL-ness [tag]}; the table comes from the #### heading. */
    private static Map<String, String> erdColumns() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        String table = null;
        for (String line : section("**10.6.2 ", "**10.6.3 ").split("\n")) {
            if (line.startsWith("#### ")) {
                table = line.substring(line.indexOf('(') + 1, line.indexOf(')'));
                continue;
            }
            String[] cells = AuthSchemaCatalogTest.cells(line);
            if (table != null && cells != null && cells.length == 7 && cells[2].matches("[a-z_]+")) {
                String tag = cells[6].substring(2, cells[6].indexOf(']'));
                out.put(table + "." + cells[2], CollectedSchemaCatalogTest.catalogType(cells[3]) + " "
                        + (cells[4].equals("NN") ? "NOT NULL" : "NULL") + " [" + tag + "]");
            }
        }
        return out;
    }

    @Test
    void erd_정의서의_열_타입_NULL_태그가_카탈로그와_같다() throws IOException {
        Map<String, String> actual = new LinkedHashMap<>();
        ExposureCatalog tags = ExposureCatalog.load(jdbc);
        jdbc.query("""
                SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull
                FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relnamespace = 'public'::regnamespace AND c.relname IN ('series', 'series_observation')
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY (c.relname <> 'series'), a.attnum
                """, rs -> {
            String key = rs.getString(1) + "." + rs.getString(2);
            actual.put(key, rs.getString(3) + " " + (rs.getBoolean(4) ? "NOT NULL" : "NULL") + " [" + tags.tag(key) + "]");
        });
        assertThat(erdColumns()).as("erd 10.6.2 열(정의서 순서 = 테이블 열 순서)").containsExactlyEntriesOf(actual);
    }

    @Test
    void 새_인덱스는_모두_유효하고_새_이름은_63바이트_이하다() throws IOException {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index
                WHERE indrelid IN ('series'::regclass, 'series_observation'::regclass) AND NOT (indisvalid AND indisready)
                """, Integer.class)).isZero();
        assertThat(erdObjects().stream().map(Obj::name))
                .allSatisfy(n -> assertThat(n.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(63));
    }
}

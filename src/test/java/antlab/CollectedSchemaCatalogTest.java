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
 * Compares the 23 named constraints and indexes added by V5 one to one with the erd 10.4 table, the columns with the
 * erd 10.3 definitions, and checks that the V4 to V5 catalog difference is exactly those two tables.
 * The db/ check SQL only knows V1-V3 and AuthSchemaCatalogTest stops at V4, so this test owns V5.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CollectedSchemaCatalogTest {

    private static final Path ERD = Path.of("docs/erd.md");
    private static final Set<String> TABLES = Set.of("job_run", "asset_daily_price");
    private static final List<String> KINDS = List.of("PK", "UQ", "UQI", "FK", "CK", "IDX");

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PostgreSQLContainer postgres;

    private static String section(String from, String to) throws IOException {
        String text = Files.readString(ERD, StandardCharsets.UTF_8);
        return text.substring(text.indexOf(from), text.indexOf(to));
    }

    /** The rows of erd 10.4 as written. */
    static List<Obj> erdObjects() throws IOException {
        List<Obj> out = new ArrayList<>();
        for (String line : section("### 10.4 ", "### 10.5 ").split("\n")) {
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
                  AND conrelid IN ('job_run'::regclass, 'asset_daily_price'::regclass)
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
                WHERE ix.indrelid IN ('job_run'::regclass, 'asset_daily_price'::regclass)
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

        assertThat(expected).as("erd 10.4 의 V5 행").hasSize(23);
        assertThat(actual.stream().map(o -> o.name() + "/" + o.kind() + "/" + o.table()).sorted())
                .containsExactlyElementsOf(expected.stream().map(o -> o.name() + "/" + o.kind() + "/" + o.table())
                        .sorted().toList());
    }

    @Test
    void erd의_정의를_다시_파싱한_결과가_카탈로그_정의와_같다() throws Exception {
        Map<String, Obj> actual = actualObjects().stream().collect(Collectors.toMap(Obj::name, o -> o));
        List<String> problems = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE TEMP TABLE chk_job_run (LIKE job_run)");
                s.execute("CREATE TEMP TABLE chk_asset_daily_price (LIKE asset_daily_price)");
                for (Obj e : erdObjects()) {
                    Obj a = actual.get(e.name());
                    String temp = "chk_" + e.table();
                    switch (e.kind()) {
                        case "PK" -> AuthSchemaCatalogTest.reparsedConstraint(s, problems, e, a, temp,
                                "PRIMARY KEY " + e.definition());
                        case "UQ" -> AuthSchemaCatalogTest.reparsedConstraint(s, problems, e, a, temp,
                                "UNIQUE " + e.definition());
                        case "CK" -> AuthSchemaCatalogTest.reparsedConstraint(s, problems, e, a, temp,
                                "CHECK (" + e.definition() + ")");
                        case "UQI" -> AuthSchemaCatalogTest.reparsedIndex(s, problems, e, a, temp,
                                "CREATE UNIQUE INDEX ");
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
    void V4에서_V5로의_카탈로그_차이는_정확히_새_제약_열_표_둘뿐이다() throws Exception {
        // One scratch database pinned to each version, so later migrations (V6 on) cannot leak into the diff.
        Set<String> v5;
        Set<String> v4;
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v5_collected")) {
            scratch.migrateTo("4");
            v4 = AuthSchemaCatalogTest.snapshot(new JdbcTemplate(scratch.dataSource()));
            scratch.migrateTo("5");
            v5 = AuthSchemaCatalogTest.snapshot(new JdbcTemplate(scratch.dataSource()));
        }
        Set<String> added = new TreeSet<>(v5);
        added.removeAll(v4);
        Set<String> removed = new TreeSet<>(v4);
        removed.removeAll(v5);

        assertThat(removed).as("V5 가 지우거나 바꾼 것").isEmpty();
        List<String> names = added.stream()
                .filter(a -> a.startsWith("constraint ") || a.startsWith("index "))
                .map(a -> a.split(" ")[1].split("\\.")[1]).distinct().sorted().toList();
        assertThat(names).containsExactlyElementsOf(erdObjects().stream().map(Obj::name).sorted().toList());
        assertThat(added.stream().filter(a -> a.startsWith("constraint ")).count()).as("새 제약").isEqualTo(20);
        assertThat(added.stream().filter(a -> a.startsWith("index ")).count()).as("새 인덱스(제약 인덱스 포함)")
                .isEqualTo(6);
        assertThat(added.stream().filter(a -> a.startsWith("column ")).map(a -> a.split(" ")[1])
                .map(c -> c.substring(0, c.indexOf('.'))).distinct().sorted())
                .containsExactly("asset_daily_price", "job_run");
        assertThat(added.stream().filter(a -> a.startsWith("table ")).map(a -> a.split(" ")[1]).sorted())
                .containsExactly("asset_daily_price", "job_run");
    }

    @Test
    void 테이블별_개수가_erd_개수_표와_같다() throws IOException {
        Map<String, int[]> erdCounts = new LinkedHashMap<>();
        for (String line : section("### 10.4 ", "### 10.5 ").split("\n")) {
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

    /** Maps an erd type cell to the text PostgreSQL's format_type prints. */
    static String catalogType(String erdType) {
        if (erdType.equals("TIMESTAMPTZ")) {
            return "timestamp with time zone";
        }
        return erdType.toLowerCase().replaceFirst("^varchar", "character varying");
    }

    /** Column rows of erd 10.3 as {@code table.column} to {@code type NULL-ness [tag]}; the table comes from the #### heading. */
    private static Map<String, String> erdColumns() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        String table = null;
        for (String line : section("### 10.3 ", "### 10.4 ").split("\n")) {
            if (line.startsWith("#### ")) {
                table = line.substring(line.indexOf('(') + 1, line.indexOf(')'));
                continue;
            }
            String[] cells = AuthSchemaCatalogTest.cells(line);
            if (table != null && cells != null && cells.length == 7 && cells[2].matches("[a-z_]+")) {
                String tag = cells[6].substring(2, cells[6].indexOf(']'));
                out.put(table + "." + cells[2], catalogType(cells[3]) + " "
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
                WHERE c.relnamespace = 'public'::regnamespace AND c.relname IN ('job_run', 'asset_daily_price')
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY (c.relname <> 'job_run'), a.attnum
                """, rs -> {
            String key = rs.getString(1) + "." + rs.getString(2);
            actual.put(key, rs.getString(3) + " " + (rs.getBoolean(4) ? "NOT NULL" : "NULL") + " [" + tags.tag(key) + "]");
        });
        assertThat(erdColumns()).as("erd 10.3 열(정의서 순서 = 테이블 열 순서)").containsExactlyEntriesOf(actual);
    }

    @Test
    void 새_인덱스는_모두_유효하고_새_이름은_63바이트_이하다() throws IOException {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index
                WHERE indrelid IN ('job_run'::regclass, 'asset_daily_price'::regclass) AND NOT (indisvalid AND indisready)
                """, Integer.class)).isZero();
        assertThat(erdObjects().stream().map(Obj::name))
                .allSatisfy(n -> assertThat(n.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(63));
    }
}

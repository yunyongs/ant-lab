package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Compares the 16 named constraints and indexes added by V4 one to one with the erd 6.4 table, and checks that the
 * V3 to V4 catalog difference is exactly those objects. The 01-03 check SQL only knows the V3 state, so this test
 * owns everything from V4 on.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthSchemaCatalogTest {

    private static final Path ERD = Path.of("docs/erd.md");

    record Obj(String name, String kind, String table, String definition) {
    }

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PostgreSQLContainer postgres;

    /** Reads the ck_member_role and refresh_token rows of erd 6.4 as written. */
    static List<Obj> erdObjects() throws IOException {
        String text = Files.readString(ERD, StandardCharsets.UTF_8);
        String section = text.substring(text.indexOf("### 6.4 "), text.indexOf("### 6.5 "));
        List<Obj> out = new ArrayList<>();
        for (String line : section.split("\n")) {
            String[] cells = cells(line);
            if (cells != null && cells.length == 4
                    && (cells[2].equals("refresh_token") || cells[0].equals("ck_member_role"))) {
                out.add(new Obj(cells[0], cells[1], cells[2], cells[3]));
            }
        }
        return out;
    }

    private static String[] cells(String line) {
        if (!line.startsWith("| ") || !line.endsWith(" |")) {
            return null;
        }
        String[] raw = line.substring(1, line.length() - 1).split("\\|", -1);
        String[] cells = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            cells[i] = raw[i].trim();
        }
        return cells;
    }

    private List<Obj> actualObjects(JdbcTemplate j) {
        List<Obj> out = new ArrayList<>();
        j.query("""
                SELECT conname, contype, conrelid::regclass::text, pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND (conrelid = 'refresh_token'::regclass OR conname = 'ck_member_role')
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
        j.query("""
                SELECT i.relname, ix.indisunique, pg_get_indexdef(ix.indexrelid)
                FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid
                WHERE ix.indrelid = 'refresh_token'::regclass
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = ix.indexrelid)
                """, rs -> {
            out.add(new Obj(rs.getString(1), rs.getBoolean(2) ? "UQI" : "IDX", "refresh_token", rs.getString(3)));
        });
        return out;
    }

    @Test
    void erd와_카탈로그의_이름_종류_테이블이_일대일이다() throws IOException {
        List<Obj> expected = erdObjects();
        List<Obj> actual = actualObjects(jdbc);

        assertThat(expected).as("erd 6.4 의 V4 행").hasSize(16);
        assertThat(actual.stream().map(o -> o.name() + "/" + o.kind() + "/" + o.table()).sorted())
                .containsExactlyElementsOf(expected.stream().map(o -> o.name() + "/" + o.kind() + "/" + o.table())
                        .sorted().toList());
    }

    @Test
    void erd의_정의를_다시_파싱한_결과가_카탈로그_정의와_같다() throws Exception {
        Map<String, Obj> actual = actualObjects(jdbc).stream().collect(Collectors.toMap(Obj::name, o -> o));
        List<String> problems = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE TEMP TABLE chk_refresh_token (LIKE refresh_token)");
                s.execute("CREATE TEMP TABLE chk_member (LIKE member)");
                for (Obj e : erdObjects()) {
                    Obj a = actual.get(e.name());
                    String temp = e.table().equals("member") ? "chk_member" : "chk_refresh_token";
                    switch (e.kind()) {
                        case "PK" -> reparsedConstraint(s, problems, e, a, temp, "PRIMARY KEY " + e.definition());
                        case "UQ" -> reparsedConstraint(s, problems, e, a, temp, "UNIQUE " + e.definition());
                        case "CK" -> reparsedConstraint(s, problems, e, a, temp, "CHECK (" + e.definition() + ")");
                        case "UQI" -> reparsedIndex(s, problems, e, a, temp, "CREATE UNIQUE INDEX ");
                        case "IDX" -> reparsedIndex(s, problems, e, a, temp, "CREATE INDEX ");
                        case "FK" -> problems.addAll(foreignKeyProblems(c, e));
                        default -> problems.add(e.name() + ": 알 수 없는 종류 " + e.kind());
                    }
                }
            } finally {
                c.rollback();
            }
        }
        assertThat(problems).isEmpty();
    }

    private static void reparsedConstraint(Statement s, List<String> problems, Obj e, Obj a, String temp, String body)
            throws SQLException {
        s.execute("ALTER TABLE " + temp + " ADD CONSTRAINT " + e.name() + " " + body);
        try (var rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '"
                + e.name() + "' AND conrelid = '" + temp + "'::regclass")) {
            rs.next();
            if (!rs.getString(1).equals(a.definition())) {
                problems.add(e.name() + ": erd 정의 → " + rs.getString(1) + " / 카탈로그 " + a.definition());
            }
        }
    }

    private static void reparsedIndex(Statement s, List<String> problems, Obj e, Obj a, String temp, String prefix)
            throws SQLException {
        s.execute(prefix + e.name() + " ON " + temp + " " + e.definition());
        try (var rs = s.executeQuery("SELECT pg_get_indexdef('" + e.name() + "'::regclass)")) {
            rs.next();
            String reparsed = rs.getString(1);
            String tail = reparsed.substring(reparsed.indexOf(" USING "));
            if (!tail.equals(a.definition().substring(a.definition().indexOf(" USING ")))) {
                problems.add(e.name() + ": erd 정의 → " + tail + " / 카탈로그 " + a.definition());
            }
        }
    }

    private static final Pattern FK_DEFINITION = Pattern.compile(
            "^\\(([^)]*)\\) → (\\w+)(?:\\.(\\w+)| \\(([^)]*)\\)) \\(R(\\d+)\\)$");

    private List<String> foreignKeyProblems(Connection c, Obj e) throws SQLException {
        List<String> problems = new ArrayList<>();
        Matcher m = FK_DEFINITION.matcher(e.definition());
        if (!m.matches()) {
            return List.of(e.name() + ": FK 정의 형식을 읽지 못함 " + e.definition());
        }
        String childColumns = m.group(1);
        String parentTable = m.group(2);
        String parentColumns = m.group(3) != null ? m.group(3) : m.group(4);
        try (var st = c.createStatement(); var rs = st.executeQuery("""
                SELECT (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                        FROM unnest(conkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = conrelid AND a.attnum = k.attnum),
                       confrelid::regclass::text,
                       (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                        FROM unnest(confkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = confrelid AND a.attnum = k.attnum),
                       confdeltype, confupdtype, confmatchtype
                FROM pg_constraint WHERE conname = '%s'""".formatted(e.name()))) {
            rs.next();
            if (!childColumns.equals(rs.getString(1))) {
                problems.add(e.name() + ": 자식 열 " + rs.getString(1) + " / erd " + childColumns);
            }
            if (!parentTable.equals(rs.getString(2))) {
                problems.add(e.name() + ": 부모 테이블 " + rs.getString(2) + " / erd " + parentTable);
            }
            if (!parentColumns.equals(rs.getString(3))) {
                problems.add(e.name() + ": 부모 열 " + rs.getString(3) + " / erd " + parentColumns);
            }
            if (!"r".equals(rs.getString(4)) || !"r".equals(rs.getString(5))) {
                problems.add(e.name() + ": ON DELETE/UPDATE RESTRICT 가 아니다");
            }
            if (!"s".equals(rs.getString(6))) {
                problems.add(e.name() + ": MATCH SIMPLE 이 아니다");
            }
        }
        return problems;
    }

    private static Set<String> snapshot(JdbcTemplate j) {
        Set<String> out = new TreeSet<>();
        j.query("""
                SELECT 'constraint ' || conrelid::regclass::text || '.' || conname || ' ' || pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text <> 'flyway_schema_history'
                UNION ALL
                SELECT 'index ' || tablename || '.' || indexname || ' ' || indexdef
                FROM pg_indexes WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                UNION ALL
                SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' '
                       || coalesce(character_maximum_length::text, '') || ' ' || is_nullable || ' '
                       || coalesce(column_default, '') || ' pos' || ordinal_position || ' identity='
                       || is_identity || '/' || coalesce(identity_generation, '') || ' num='
                       || coalesce(numeric_precision::text, '') || ',' || coalesce(numeric_scale::text, '') || ' dt='
                       || coalesce(datetime_precision::text, '') || ' '
                       || coalesce(col_description(('public.' || table_name)::regclass, ordinal_position), '')
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                UNION ALL
                SELECT 'table ' || relname || ' ' || coalesce(obj_description(oid), '')
                FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r'
                  AND relname <> 'flyway_schema_history'
                """, rs -> {
            out.add(rs.getString(1));
        });
        return out;
    }

    @Test
    void V3에서_V4로의_카탈로그_차이는_정확히_새_제약_열_표_하나뿐이다() throws Exception {
        Set<String> v4 = snapshot(jdbc);
        Set<String> v3;
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v3_catalog")) {
            scratch.migrateTo("3");
            v3 = snapshot(new JdbcTemplate(scratch.dataSource()));
        }
        Set<String> added = new TreeSet<>(v4);
        added.removeAll(v3);
        Set<String> removed = new TreeSet<>(v3);
        removed.removeAll(v4);

        assertThat(removed).as("V4 가 지우거나 바꾼 것").isEmpty();
        List<String> names = added.stream()
                .filter(a -> a.startsWith("constraint ") || a.startsWith("index "))
                .map(a -> a.split(" ")[1].split("\\.")[1]).distinct().sorted().toList();
        assertThat(names).containsExactlyElementsOf(erdObjects().stream().map(Obj::name).sorted().toList());
        assertThat(added.stream().filter(a -> a.startsWith("constraint ")).count()).as("새 제약").isEqualTo(13);
        assertThat(added.stream().filter(a -> a.startsWith("index ")).count()).as("새 인덱스(제약 인덱스 포함)")
                .isEqualTo(7);
        assertThat(added.stream().filter(a -> a.startsWith("column ")).map(a -> a.split(" ")[1]).sorted())
                .containsExactly("member.role", "refresh_token.created_at", "refresh_token.expires_at",
                        "refresh_token.family_id", "refresh_token.id", "refresh_token.member_id",
                        "refresh_token.parent_id", "refresh_token.revoked_at", "refresh_token.revoked_reason",
                        "refresh_token.token_hash", "refresh_token.updated_at");
        assertThat(added.stream().filter(a -> a.startsWith("table ")).map(a -> a.split(" ")[1]))
                .containsExactly("refresh_token");
    }

    @Test
    void 테이블별_개수가_erd_개수_표와_같다() throws IOException {
        Map<String, int[]> erdCounts = new LinkedHashMap<>();
        String text = Files.readString(ERD, StandardCharsets.UTF_8);
        String section = text.substring(text.indexOf("### 6.4 "), text.indexOf("### 6.5 "));
        for (String line : section.split("\n")) {
            String[] cells = cells(line);
            if (cells != null && cells.length == 7 && cells[0].matches("member|refresh_token")) {
                int[] n = new int[6];
                for (int i = 0; i < 6; i++) {
                    n[i] = Integer.parseInt(cells[i + 1]);
                }
                erdCounts.put(cells[0], n);
            }
        }
        List<String> kinds = List.of("PK", "UQ", "UQI", "FK", "CK", "IDX");
        List<Obj> actual = actualObjects(jdbc);
        Map<String, Integer> member = new LinkedHashMap<>();
        // The erd table also counts member's V1-V3 pk_member and uq_member_email, so read those from the catalog separately.
        jdbc.query("""
                SELECT contype, count(*) FROM pg_constraint
                WHERE conrelid = 'member'::regclass AND connamespace = 'public'::regnamespace GROUP BY contype
                """, rs -> {
            member.put(rs.getString(1), rs.getInt(2));
        });
        int memberUnique = jdbc.queryForObject("""
                SELECT count(*) FROM pg_index ix
                WHERE ix.indrelid = 'member'::regclass AND ix.indisunique
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = ix.indexrelid)
                """, Integer.class);
        assertThat(erdCounts.get("member")).containsExactly(
                member.getOrDefault("p", 0), member.getOrDefault("u", 0), memberUnique,
                member.getOrDefault("f", 0), member.getOrDefault("c", 0), 0);
        int[] tokenActual = new int[6];
        for (Obj o : actual) {
            if (o.table().equals("refresh_token")) {
                tokenActual[kinds.indexOf(o.kind())]++;
            }
        }
        assertThat(erdCounts.get("refresh_token")).containsExactly(tokenActual);
    }

    @Test
    void 새_인덱스는_모두_유효하고_새_이름은_63바이트_이하다() throws IOException {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index
                WHERE indrelid = 'refresh_token'::regclass AND NOT (indisvalid AND indisready)
                """, Integer.class)).isZero();
        assertThat(erdObjects().stream().map(Obj::name))
                .allSatisfy(n -> assertThat(n.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(63));
    }
}

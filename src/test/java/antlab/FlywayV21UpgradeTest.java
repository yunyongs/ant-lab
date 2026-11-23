package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.leaderboard.ManagerPartsExclusion;
import antlab.support.CheckConstraintCatalog;
import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V21 adds the score formula and the season manager parts and nothing else; the seed formula is s69-v1, and every named
 * rule is broken once by name, NaN included. The erd section 17 lists the same names.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV21UpgradeTest {

    static final Set<String> EXPECTED = Set.of(
            "manager_score_formula.pk_manager_score_formula", "manager_score_formula.uq_manager_score_formula_adopted",
            "manager_score_formula.ck_manager_score_formula_version", "manager_score_formula.ck_manager_score_formula_weights",
            "manager_score_formula.ck_manager_score_formula_sum", "manager_score_formula.ck_manager_score_formula_status",
            "manager_score_formula.ck_manager_score_formula_basis",
            "season_manager_parts.pk_season_manager_parts", "season_manager_parts.fk_season_manager_parts_score",
            "season_manager_parts.fk_season_manager_parts_season",
            "season_manager_parts.ck_season_manager_parts_reference_version",
            "season_manager_parts.ck_season_manager_parts_exclusion", "season_manager_parts.ck_season_manager_parts_counted",
            "season_manager_parts.ck_season_manager_parts_excluded",
            "season_manager_parts.ck_season_manager_parts_reference_key",
            "season_manager_parts.ck_season_manager_parts_reference_size",
            "season_manager_parts.ck_season_manager_parts_bot_version",
            "season_manager_parts.ck_season_manager_parts_engine_version",
            "season_manager_parts.ck_season_manager_parts_components", "season_manager_parts.ck_season_manager_parts_mdd",
            "season_manager_parts.ck_season_manager_parts_percentiles",
            "season_manager_parts.ck_season_manager_parts_risk_item", "season_manager_parts.ck_season_manager_parts_parts",
            "season_manager_parts.ck_season_manager_parts_process",
            "season_manager_parts.ck_season_manager_parts_process_version",
            "season_manager_parts.ck_season_manager_parts_final_multiple",
            "season_manager_parts.idx_season_manager_parts_member");

    private static final String FORMULA = "INSERT INTO manager_score_formula (version, w_return, w_risk_adjusted, w_risk, w_process, "
            + "status, basis, adopted_at) VALUES (?, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?, ?, ?::timestamptz)";

    private static final String SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version, data_snapshot_date,
                base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            VALUES (?, 'FINISHED', 12, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20) RETURNING id
            """;

    private static final String SCORE = """
            INSERT INTO season_score (season_id, member_id, score_version, score, result_score, process_score, sharpe,
                bench_sharpe, sharpe_diff, sharpe_diff_percentile, luck_percentile, risk_score, process_items, inputs)
            VALUES (?, ?, 's63-v3', 70, 70, 60, 1, 0.5, 0.5, 0.5, 0.5, 60, 1, '{}')
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static Set<String> objects(JdbcTemplate j) {
        Set<String> out = new TreeSet<>();
        j.query("""
                SELECT conrelid::regclass::text || '.' || conname FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text <> 'flyway_schema_history'
                UNION
                SELECT tablename || '.' || indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, rs -> {
            out.add(rs.getString(1));
        });
        return out;
    }

    private static void refused(JdbcTemplate jdbc, String constraint, Consumer<JdbcTemplate> statement) {
        assertThatThrownBy(() -> statement.accept(jdbc)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    /** A counted row: every engine column set, the risk item included. */
    private static Map<String, Object> counted(long season, long member) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("season_id", season);
        v.put("member_id", member);
        v.put("reference_version", "ref69-v1");
        v.put("exclusion", null);
        v.put("reference_key", "3f".repeat(32));
        v.put("reference_size", 200);
        v.put("bot_version", "random/1");
        v.put("engine_version", "0.1.0");
        for (String c : new String[] { "excess_cagr", "sharpe_diff", "sortino_diff", "calmar_diff" }) {
            v.put(c, "0.060000000000");
        }
        v.put("mdd", "-0.052500000000");
        v.put("bench_mdd", "0");
        for (String c : new String[] { "pct_excess_cagr", "pct_sharpe_diff", "pct_sortino_diff", "pct_calmar_diff", "pct_risk_item",
                "pct_mdd", "part_return", "part_risk_adjusted", "part_risk" }) {
            v.put(c, "50.00000000");
        }
        v.put("part_process", "60.00");
        v.put("process_score_version", "s63-v3");
        v.put("final_multiple", "1.08000000");
        return v;
    }

    /** An excluded row: no engine column. */
    private static Map<String, Object> excluded(long season, long member, String reason) {
        Map<String, Object> v = counted(season, member);
        v.replaceAll((k, old) -> k.equals("season_id") || k.equals("member_id") || k.equals("reference_version")
                || k.equals("part_process") || k.equals("process_score_version") || k.equals("final_multiple") ? old : null);
        v.put("exclusion", reason);
        return v;
    }

    private static Map<String, Object> with(Map<String, Object> row, String column, Object value) {
        Map<String, Object> v = new LinkedHashMap<>(row);
        v.put(column, value);
        return v;
    }

    private static void insert(JdbcTemplate jdbc, Map<String, Object> row) {
        String casts = row.keySet().stream().map(k -> switch (k) {
            case "season_id", "member_id" -> "?::bigint";
            case "reference_size" -> "?::smallint";
            case "reference_version", "exclusion", "reference_key", "bot_version", "engine_version", "process_score_version" -> "?::text";
            default -> "?::numeric";
        }).collect(Collectors.joining(", "));
        jdbc.update("INSERT INTO season_manager_parts (" + String.join(", ", row.keySet()) + ") VALUES (" + casts + ")",
                row.values().stream().map(o -> o == null ? null : o.toString()).toArray());
    }

    @Test
    void v21AddsExactlyTheNamedConstraintsAndIndexes() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v21_catalog")) {
            scratch.migrateTo("20");
            Set<String> v20 = objects(new JdbcTemplate(scratch.dataSource()));
            assertThat(scratch.migrateTo("21").migrationsExecuted).isEqualTo(1);
            Set<String> v21 = objects(new JdbcTemplate(scratch.dataSource()));
            Set<String> added = new TreeSet<>(v21);
            added.removeAll(v20);

            assertThat(v20).isSubsetOf(v21);
            assertThat(added).containsExactlyInAnyOrderElementsOf(EXPECTED);
            assertThat(EXPECTED).allSatisfy(n -> assertThat(n.substring(n.indexOf('.') + 1).length()).isLessThanOrEqualTo(63));
            assertThat(scratch.migrateTo("21").migrationsExecuted).isZero();
        }
    }

    @Test
    void erdSection17ListsTheSameNamesAndKinds() throws IOException {
        String erd = Files.readString(Path.of("docs/erd.md"), StandardCharsets.UTF_8);
        int start = erd.indexOf("### 17.4 ");
        String section = erd.substring(start, erd.indexOf("**Counts", start));
        Set<String> listed = new TreeSet<>();
        for (String line : section.split("\n")) {
            String[] cells = AuthSchemaCatalogTest.cells(line);
            if (cells != null && cells.length == 4
                    && (cells[2].equals("manager_score_formula") || cells[2].equals("season_manager_parts"))) {
                listed.add(cells[2] + "." + cells[0]);
            }
        }

        assertThat(listed).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void theSeedFormulaIsS69V1AndProvisional() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v21_seed")) {
            scratch.migrateTo("21");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());

            assertThat(jdbc.queryForList("""
                    SELECT version || ' ' || w_return || ' ' || w_risk_adjusted || ' ' || w_risk || ' ' || w_process || ' ' || status
                    FROM manager_score_formula""", String.class)).containsExactly("s69-v1 0.30 0.30 0.15 0.25 PROVISIONAL");
        }
    }

    @Test
    void enumConstantsMatchTheCheckListsLetterForLetter() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v21_enum")) {
            scratch.migrateTo("21");
            Map<String, Set<String>> checks = CheckConstraintCatalog.valueLists(new JdbcTemplate(scratch.dataSource()));

            assertThat(checks.get("season_manager_parts.exclusion")).isEqualTo(
                    Arrays.stream(ManagerPartsExclusion.values()).map(Enum::name).collect(Collectors.toSet()));
            assertThat(checks.get("manager_score_formula.status")).containsExactlyInAnyOrder("PROVISIONAL", "FINAL");
        }
    }

    @Test
    void everyRuleIsEnforcedByName() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v21_rules")) {
            scratch.migrateTo("21");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());

            String t = "2027-01-01T00:00:00Z";
            jdbc.update(FORMULA, "s70-v1", "0.25", "0.25", "0.25", "0.25", "FINAL", "test row", t);
            refused(jdbc, "pk_manager_score_formula",
                    j -> j.update(FORMULA, "s70-v1", "0.25", "0.25", "0.25", "0.25", "FINAL", "x", "2027-01-02T00:00:00Z"));
            refused(jdbc, "uq_manager_score_formula_adopted",
                    j -> j.update(FORMULA, "s70-v2", "0.25", "0.25", "0.25", "0.25", "FINAL", "x", t));
            refused(jdbc, "ck_manager_score_formula_version",
                    j -> j.update(FORMULA, "S70-v1", "0.25", "0.25", "0.25", "0.25", "FINAL", "x", "2027-01-03T00:00:00Z"));
            refused(jdbc, "ck_manager_score_formula_weights",
                    j -> j.update(FORMULA, "s70-v3", "-0.10", "0.40", "0.35", "0.35", "FINAL", "x", "2027-01-04T00:00:00Z"));
            // NaN makes the sum NaN too, and the checks run in name order, so the sum rule names the refusal first
            assertThatThrownBy(() -> jdbc.update(FORMULA, "s70-v3", "NaN", "0.25", "0.25", "0.25", "FINAL", "x", "2027-01-04T00:00:00Z"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageMatching("(?s).*ck_manager_score_formula_(sum|weights).*");
            refused(jdbc, "ck_manager_score_formula_sum",
                    j -> j.update(FORMULA, "s70-v3", "0.30", "0.30", "0.15", "0.26", "FINAL", "x", "2027-01-04T00:00:00Z"));
            refused(jdbc, "ck_manager_score_formula_status",
                    j -> j.update(FORMULA, "s70-v3", "0.25", "0.25", "0.25", "0.25", "DRAFT", "x", "2027-01-04T00:00:00Z"));
            refused(jdbc, "ck_manager_score_formula_basis",
                    j -> j.update(FORMULA, "s70-v3", "0.25", "0.25", "0.25", "0.25", "FINAL", " x", "2027-01-04T00:00:00Z"));

            jdbc.update("INSERT INTO member (email, name, password) VALUES ('player@example.invalid', 'p1', '!')");
            long member = jdbc.queryForObject("SELECT id FROM member", Long.class);
            long scored = jdbc.queryForObject(SEASON, Long.class, member);
            jdbc.update(SCORE, scored, member);
            long other = jdbc.queryForObject(SEASON, Long.class, member);
            jdbc.update(SCORE, other, member);
            long unscored = jdbc.queryForObject(SEASON, Long.class, member);

            insert(jdbc, counted(scored, member));
            insert(jdbc, excluded(other, member, "NO_REFERENCE"));
            insert(jdbc, with(excluded(other, member, "NOT_STARTED"), "reference_version", "ref70-v1"));
            Map<String, Object> ok = counted(other, member);
            ok.put("reference_version", "ref71-v1");

            refused(jdbc, "pk_season_manager_parts", j -> insert(j, counted(scored, member)));
            refused(jdbc, "fk_season_manager_parts_score", j -> insert(j, counted(unscored, member)));
            refused(jdbc, "fk_season_manager_parts_season", j -> insert(j, with(ok, "member_id", member + 1000)));
            refused(jdbc, "ck_season_manager_parts_reference_version", j -> insert(j, with(ok, "reference_version", "REF71-v1")));
            refused(jdbc, "ck_season_manager_parts_exclusion",
                    j -> insert(j, with(excluded(other, member, "VOID"), "reference_version", "ref71-v1")));
            refused(jdbc, "ck_season_manager_parts_counted", j -> insert(j, with(ok, "part_risk", null)));
            refused(jdbc, "ck_season_manager_parts_counted", j -> insert(j, with(ok, "reference_key", null)));
            refused(jdbc, "ck_season_manager_parts_excluded",
                    j -> insert(j, with(with(excluded(other, member, "REFERENCE_MISMATCH"), "reference_version", "ref71-v1"),
                            "part_return", "50")));
            refused(jdbc, "ck_season_manager_parts_excluded",
                    j -> insert(j, with(with(excluded(other, member, "REFERENCE_MISMATCH"), "reference_version", "ref71-v1"),
                            "pct_risk_item", "50")));
            refused(jdbc, "ck_season_manager_parts_reference_key", j -> insert(j, with(ok, "reference_key", "3F".repeat(32))));
            refused(jdbc, "ck_season_manager_parts_reference_size", j -> insert(j, with(ok, "reference_size", 0)));
            refused(jdbc, "ck_season_manager_parts_bot_version", j -> insert(j, with(ok, "bot_version", "Random/1")));
            refused(jdbc, "ck_season_manager_parts_engine_version", j -> insert(j, with(ok, "engine_version", " 0.1.0")));
            refused(jdbc, "ck_season_manager_parts_components", j -> insert(j, with(ok, "sortino_diff", "NaN")));
            refused(jdbc, "ck_season_manager_parts_mdd", j -> insert(j, with(ok, "mdd", "0.01")));
            refused(jdbc, "ck_season_manager_parts_mdd", j -> insert(j, with(ok, "bench_mdd", "-1.000000000001")));
            refused(jdbc, "ck_season_manager_parts_mdd", j -> insert(j, with(ok, "mdd", "NaN")));
            refused(jdbc, "ck_season_manager_parts_percentiles", j -> insert(j, with(ok, "pct_mdd", "100.00000001")));
            refused(jdbc, "ck_season_manager_parts_percentiles", j -> insert(j, with(ok, "pct_sharpe_diff", "NaN")));
            refused(jdbc, "ck_season_manager_parts_risk_item", j -> insert(j, with(ok, "pct_risk_item", "-0.00000001")));
            refused(jdbc, "ck_season_manager_parts_parts", j -> insert(j, with(ok, "part_risk_adjusted", "NaN")));
            refused(jdbc, "ck_season_manager_parts_parts", j -> insert(j, with(ok, "part_return", "100.00000001")));
            refused(jdbc, "ck_season_manager_parts_process", j -> insert(j, with(ok, "part_process", "100.01")));
            refused(jdbc, "ck_season_manager_parts_process", j -> insert(j, with(ok, "part_process", "NaN")));
            refused(jdbc, "ck_season_manager_parts_process_version", j -> insert(j, with(ok, "process_score_version", "")));
            refused(jdbc, "ck_season_manager_parts_final_multiple", j -> insert(j, with(ok, "final_multiple", "-0.00000001")));
            refused(jdbc, "ck_season_manager_parts_final_multiple", j -> insert(j, with(ok, "final_multiple", "NaN")));

            // a counted row may lack the risk item (the season had no risk score); NO_REFERENCE needs no engine column
            insert(jdbc, with(ok, "pct_risk_item", null));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM season_manager_parts", Long.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT pct_mdd::text FROM season_manager_parts WHERE season_id = ? AND "
                    + "reference_version = 'ref69-v1'", String.class, scored)).isEqualTo("50.00000000");
        }
    }
}

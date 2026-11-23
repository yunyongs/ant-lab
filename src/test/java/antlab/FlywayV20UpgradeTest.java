package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V20 adds the two detection tables and nothing else; every named rule is broken once by name, NaN included. The erd
 * section 16 lists the same names.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV20UpgradeTest {

    static final Set<String> EXPECTED = Set.of(
            "detection_test.pk_detection_test", "detection_test.uq_detection_test_source",
            "detection_test.ck_detection_test_model_id", "detection_test.ck_detection_test_test_version",
            "detection_test.ck_detection_test_sample_layer", "detection_test.ck_detection_test_window_months",
            "detection_test.ck_detection_test_units", "detection_test.ck_detection_test_source_sha256",
            "detection_test.idx_detection_test_source",
            "detection_metric.pk_detection_metric", "detection_metric.fk_detection_metric_test",
            "detection_metric.ck_detection_metric_metric", "detection_metric.ck_detection_metric_units",
            "detection_metric.ck_detection_metric_hits", "detection_metric.ck_detection_metric_base_rate");

    private static final String SHA = "a".repeat(64);
    private static final String TEST = """
            INSERT INTO detection_test (model_id, test_version, sample_layer, window_months, units, source_sha256, run_at)
            VALUES (?, ?, ?, ?, ?, ?, '2026-11-23T09:00:00Z') RETURNING id""";
    private static final String METRIC = "INSERT INTO detection_metric (test_id, metric, hits, units, base_rate) "
            + "VALUES (?, ?, ?, ?, ?::numeric)";

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

    private static long test(JdbcTemplate jdbc, String model, String version, String layer, int months, int units,
            String sha) {
        return jdbc.queryForObject(TEST, Long.class, model, version, layer, months, units, sha);
    }

    private static void refused(JdbcTemplate jdbc, String constraint, Consumer<JdbcTemplate> statement) {
        assertThatThrownBy(() -> statement.accept(jdbc)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @Test
    void v20AddsExactlyTheNamedConstraintsAndIndexes() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v20_catalog")) {
            scratch.migrateTo("19");
            Set<String> v19 = objects(new JdbcTemplate(scratch.dataSource()));
            assertThat(scratch.migrateTo("20").migrationsExecuted).isEqualTo(1);
            Set<String> v20 = objects(new JdbcTemplate(scratch.dataSource()));
            Set<String> added = new TreeSet<>(v20);
            added.removeAll(v19);

            assertThat(v19).isSubsetOf(v20);
            assertThat(added).containsExactlyInAnyOrderElementsOf(EXPECTED);
            assertThat(EXPECTED).allSatisfy(n -> assertThat(n.substring(n.indexOf('.') + 1).length()).isLessThanOrEqualTo(63));
            assertThat(scratch.migrateTo("20").migrationsExecuted).isZero();
        }
    }

    @Test
    void erdSection16ListsTheSameNamesAndKinds() throws IOException {
        String erd = Files.readString(Path.of("docs/erd.md"), StandardCharsets.UTF_8);
        int start = erd.indexOf("### 16.4 ");
        String section = erd.substring(start, erd.indexOf("**Counts", start));
        Set<String> listed = new TreeSet<>();
        for (String line : section.split("\n")) {
            String[] cells = AuthSchemaCatalogTest.cells(line);
            if (cells != null && cells.length == 4 && cells[2].startsWith("detection_")) {
                listed.add(cells[2] + "." + cells[0]);
            }
        }

        assertThat(listed).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void everyRuleIsEnforcedByName() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v20_rules")) {
            scratch.migrateTo("20");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long id = test(jdbc, "fake/model-1", "detection-month/1", "P0-08", 1, 98, SHA);
            jdbc.update(METRIC, id, "YM_EXACT", 0, 98, "0.01000000");
            jdbc.update(METRIC, id, "KR_NEXT", 98, 98, "1");

            refused(jdbc, "uq_detection_test_source", j -> test(j, "fake/model-1", "detection-month/1", "P0-08", 1, 98, SHA));
            refused(jdbc, "ck_detection_test_model_id", j -> test(j, " fake", "detection-month/1", "P0-08", 1, 98, SHA));
            refused(jdbc, "ck_detection_test_model_id", j -> test(j, "", "detection-month/1", "P0-08", 1, 98, SHA));
            refused(jdbc, "ck_detection_test_test_version", j -> test(j, "m", "Detection/1", "P0-08", 1, 98, SHA));
            refused(jdbc, "ck_detection_test_test_version", j -> test(j, "m", "detection-month/0", "P0-08", 1, 98, SHA));
            refused(jdbc, "ck_detection_test_sample_layer", j -> test(j, "m", "detection-month/1", "p0-08", 1, 98, SHA));
            refused(jdbc, "ck_detection_test_window_months", j -> test(j, "m", "detection-month/1", "P0-08", 2, 98, SHA));
            refused(jdbc, "ck_detection_test_units", j -> test(j, "m", "detection-month/1", "P0-08", 3, 0, SHA));
            refused(jdbc, "ck_detection_test_source_sha256", j -> test(j, "m", "detection-month/1", "P0-08", 3, 98,
                    "A".repeat(64)));

            refused(jdbc, "pk_detection_metric", j -> j.update(METRIC, id, "YM_EXACT", 1, 98, "0.01000000"));
            refused(jdbc, "fk_detection_metric_test", j -> j.update(METRIC, id + 1000, "YEAR", 1, 98, "0.01000000"));
            refused(jdbc, "ck_detection_metric_metric", j -> j.update(METRIC, id, "MONTH", 1, 98, "0.01000000"));
            refused(jdbc, "ck_detection_metric_units", j -> j.update(METRIC, id, "YEAR", 0, 0, "0.01000000"));
            refused(jdbc, "ck_detection_metric_hits", j -> j.update(METRIC, id, "YEAR", 99, 98, "0.01000000"));
            refused(jdbc, "ck_detection_metric_hits", j -> j.update(METRIC, id, "YEAR", -1, 98, "0.01000000"));
            refused(jdbc, "ck_detection_metric_base_rate", j -> j.update(METRIC, id, "YEAR", 1, 98, "1.00000001"));
            refused(jdbc, "ck_detection_metric_base_rate", j -> j.update(METRIC, id, "YEAR", 1, 98, "-0.00000001"));
            refused(jdbc, "ck_detection_metric_base_rate", j -> j.update(METRIC, id, "YEAR", 1, 98, "NaN"));

            // another window or another source hash of the same model is a new row
            test(jdbc, "fake/model-1", "detection-month/1", "P0-08", 3, 94, SHA);
            test(jdbc, "fake/model-1", "detection-month/1", "P0-08", 1, 98, "b".repeat(64));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_test", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT base_rate::text FROM detection_metric WHERE metric = 'KR_NEXT'",
                    String.class)).isEqualTo("1.00000000");
        }
    }
}

package antlab.regime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Applying V11 to a V10 database with month rows (some already carrying axes) keeps every row and adds two empty columns. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV11UpgradeTest {

    @Autowired
    PostgreSQLContainer postgres;

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO month_block (month_start) VALUES ('2001-01-01')");
        jdbc.update("INSERT INTO month_block (month_start, rate_dir, rate_strength) VALUES ('2001-02-01', 'UP', 0.5)");
    }

    @Test
    void upgradeFromV10KeepsEveryRowAndAddsTwoNullColumns() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v11_upgrade")) {
            scratch.migrateTo("10");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            seed(jdbc);
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM month_block ORDER BY id");

            assertThat(scratch.migrateTo("11").migrationsExecuted).isEqualTo(1);

            List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM month_block ORDER BY id");
            assertThat(after).hasSameSizeAs(before);
            for (int i = 0; i < before.size(); i++) {
                Map<String, Object> row = new java.util.LinkedHashMap<>(after.get(i));
                assertThat(row.remove("regime_label")).isNull();
                assertThat(row.remove("indicator_z")).isNull();
                assertThat(row).isEqualTo(before.get(i));
            }
            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE success AND checksum IS NOT NULL ORDER BY installed_rank
                    """, String.class)).last().isEqualTo("11");
        }
    }

    @Test
    void aDatabaseWithProbabilitiesAndNoLabelIsRefusedAndStaysAtV10() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v11_legacy")) {
            scratch.migrateTo("10");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('2001-03-01', 0.25, 0.25, 0.25, 0.25)");

            assertThatThrownBy(() -> scratch.migrateTo("11")).hasMessageContaining("ck_month_block_label_with_prob");

            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'month_block' "
                    + "AND column_name IN ('regime_label', 'indicator_z')", Integer.class)).as("the migration is one transaction").isZero();
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                    .last().isEqualTo("10");
        }
    }

    @Test
    void theThreeNewChecksRefuseWhatTheyShouldAndAcceptTheRightRows() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v11_checks")) {
            scratch.migrateTo("11");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            jdbc.update("INSERT INTO month_block (month_start) VALUES ('2001-01-01')");

            assertThat(jdbc.update("UPDATE month_block SET regime_label = 'P4', prob_p1 = 0.1, prob_p2 = 0.2, prob_p3 = 0.3, prob_p4 = 0.4,"
                    + " indicator_z = '{\"1\": 0.52000000, \"2\": null}'::jsonb")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT indicator_z::text FROM month_block", String.class)).contains("0.52000000");

            jdbc.update("INSERT INTO month_block (month_start) VALUES ('2001-02-01')");
            for (String badLabel : List.of("P0", "P5", "p1", "")) {
                assertThatThrownBy(() -> jdbc.update("UPDATE month_block SET regime_label = ?, prob_p1 = 0.25, prob_p2 = 0.25, prob_p3 = 0.25,"
                        + " prob_p4 = 0.25 WHERE month_start = '2001-02-01'", badLabel))
                        .as(badLabel).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_month_block_regime_label");
            }
            assertThatThrownBy(() -> jdbc.update("UPDATE month_block SET regime_label = 'P1' WHERE month_start = '2001-02-01'"))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_month_block_label_with_prob");
            assertThatThrownBy(() -> jdbc.update("UPDATE month_block SET prob_p1 = 0.25, prob_p2 = 0.25, prob_p3 = 0.25, prob_p4 = 0.25"
                    + " WHERE month_start = '2001-02-01'"))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_month_block_label_with_prob");
            for (String bad : List.of("[1, 2]", "\"text\"", "1", "true")) {
                assertThatThrownBy(() -> jdbc.update("UPDATE month_block SET indicator_z = ?::jsonb WHERE month_start = '2001-02-01'", bad))
                        .as(bad).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_month_block_indicator_z");
            }
            assertThat(jdbc.update("UPDATE month_block SET indicator_z = '{}'::jsonb WHERE month_start = '2001-02-01'")).isEqualTo(1);
        }
    }

    @Test
    void migratingV11AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v11_again")) {
            scratch.migrateTo("11");

            assertThat(scratch.migrateTo("11").migrationsExecuted).isZero();
        }
    }
}

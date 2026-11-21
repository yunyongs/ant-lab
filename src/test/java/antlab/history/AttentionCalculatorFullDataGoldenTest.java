package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FullDataDb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Full-data golden tier (MVP-06): every {@code asset_month_attention} row of a database loaded with the full history and
 * calculated by {@code db/13} must equal {@link AttentionCalculator}, compared exactly. Skipped unless
 * {@value FullDataDb#ENV_URL} is set; CI never sets it. Prints counts only.
 */
@Tag("sql-golden")
class AttentionCalculatorFullDataGoldenTest {

    @Test
    void every_stored_attention_row_of_the_full_history_equals_the_java_calculation() throws Exception {
        FullDataDb db = FullDataDb.fromEnvironment();
        Assumptions.assumeTrue(db != null, FullDataDb.SKIP_REASON);

        AttentionParity.Coverage coverage = AttentionParity.compareDatabase(db);

        System.out.println("[full-data-golden] attention " + coverage);
        assertThat(coverage.rows()).as("compared rows").isPositive();
        assertThat(coverage.rows() - coverage.nullByWindow() - coverage.nullByFloor()).as("computed z rows").isPositive();
    }
}

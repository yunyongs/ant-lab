package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FullDataDb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Full-data golden tier (MVP-06): the base-date position calculator is rebuilt from the raw tables of a database loaded with the
 * full history and calculated by {@code db/12}; it must reproduce every stored judgement and reason exactly. Skipped unless
 * {@value FullDataDb#ENV_URL} is set; CI never sets it. Prints counts only.
 */
@Tag("sql-golden")
class BaseDatePositionFullDataGoldenTest {

    @Test
    void every_stored_judgement_of_the_full_history_equals_the_java_calculation() throws Exception {
        FullDataDb db = FullDataDb.fromEnvironment();
        Assumptions.assumeTrue(db != null, FullDataDb.SKIP_REASON);

        PositionParity.Coverage coverage = PositionParity.compareDatabase(db);

        System.out.println("[full-data-golden] position " + coverage);
        assertThat(coverage.judged()).as("judged rows").isPositive();
    }
}

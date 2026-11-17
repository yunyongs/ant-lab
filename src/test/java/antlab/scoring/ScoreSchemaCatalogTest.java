package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Every constraint and index V8 adds, by name, as the exact V7 to V8 difference. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScoreSchemaCatalogTest {

    private static final Set<String> EXPECTED = Set.of(
            "season_score.pk_season_score", "season_score.fk_season_score_season",
            "season_score.idx_season_score_member",
            "season_score.ck_season_score_version", "season_score.ck_season_score_score",
            "season_score.ck_season_score_result", "season_score.ck_season_score_process",
            "season_score.ck_season_score_score_pair", "season_score.ck_season_score_sharpe",
            "season_score.ck_season_score_diff_percentile", "season_score.ck_season_score_luck_percentile",
            "season_score.ck_season_score_percentile_pair",
            "season_score.ck_season_score_risk", "season_score.ck_season_score_discipline",
            "season_score.ck_season_score_diversification", "season_score.ck_season_score_turnover",
            "season_score.ck_season_score_consistency", "season_score.ck_season_score_formation",
            "season_score.ck_season_score_role", "season_score.ck_season_score_noise",
            "season_score.ck_season_score_nudge", "season_score.ck_season_score_process_items",
            "season_score.ck_season_score_rating_exclusion", "season_score.ck_season_score_no_score",
            "season_score.ck_season_score_no_score_null",
            "season_score.ck_season_score_inputs",
            "rating_history.pk_rating_history", "rating_history.uq_rating_history_member_pillar_seq",
            "rating_history.uq_rating_history_season", "rating_history.fk_rating_history_member",
            "rating_history.fk_rating_history_score", "rating_history.ck_rating_history_pillar",
            "rating_history.ck_rating_history_seq_no", "rating_history.ck_rating_history_rating",
            "rating_history.ck_rating_history_used");

    @Autowired
    JdbcTemplate jdbc;
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

    @Test
    void v8AddsExactlyTheNamedConstraintsAndIndexes() {
        Set<String> v7;
        Set<String> v8;
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v8_catalog")) {
            scratch.migrateTo("7");
            v7 = objects(new JdbcTemplate(scratch.dataSource()));
            scratch.migrateTo("8");
            v8 = objects(new JdbcTemplate(scratch.dataSource()));
        }
        Set<String> added = new TreeSet<>(v8);
        added.removeAll(v7);

        assertThat(v7).isSubsetOf(v8);
        assertThat(added).containsExactlyInAnyOrderElementsOf(EXPECTED);
        assertThat(objects(jdbc)).containsAll(EXPECTED);
    }

    @Test
    void everyNewNameFitsIn63Bytes() {
        assertThat(EXPECTED).allSatisfy(n -> assertThat(n.substring(n.indexOf('.') + 1).length()).isLessThanOrEqualTo(63));
    }

    @Test
    void newIndexesAreValid() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index
                WHERE indrelid IN ('season_score'::regclass, 'rating_history'::regclass)
                  AND NOT (indisvalid AND indisready)
                """, Integer.class)).isZero();
    }

    @Test
    void everyColumnHasAnExposureTagInItsComment() {
        assertThat(jdbc.queryForList("""
                SELECT c.relname || '.' || a.attname FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname IN ('season_score', 'rating_history') AND a.attnum > 0 AND NOT a.attisdropped
                  AND coalesce(col_description(c.oid, a.attnum), '') !~ '^\\[(노출|내부)\\] '
                """, String.class)).isEmpty();
    }
}

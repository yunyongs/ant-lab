package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The two regime columns of month_block through the read-only entity: the label has no getter, the JSON text arrives as written. */
@JpaMappingTest
class MonthBlockRegimeMappingTest {

    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;

    private long insert(String month, String label, String indicators) {
        return jdbc.queryForObject("""
                INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4, regime_label, indicator_z)
                VALUES (?::date, CASE WHEN ?::text IS NULL THEN NULL ELSE 0.25 END, CASE WHEN ?::text IS NULL THEN NULL ELSE 0.25 END,
                        CASE WHEN ?::text IS NULL THEN NULL ELSE 0.25 END, CASE WHEN ?::text IS NULL THEN NULL ELSE 0.25 END,
                        ?, ?::jsonb) RETURNING id""", Long.class, month, label, label, label, label, label, indicators);
    }

    @Test
    void theLabelAndTheJsonTextAreReadAndNothingIsWrittenBack() throws Exception {
        long id = insert("2001-05-01", "P3", "{\"1\": 0.52000000, \"2\": null, \"14US\": -0.30000000}");
        em.flush();
        em.clear();

        MonthBlock block = em.find(MonthBlock.class, id);

        Field label = MonthBlock.class.getDeclaredField("regimeLabel");
        label.setAccessible(true);
        assertThat(label.get(block)).isEqualTo(RegimePool.P3);
        assertThat(block.getIndicatorZ()).contains("\"14US\"").contains("0.52000000").contains("null");
        assertThat(Arrays.stream(MonthBlock.class.getMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("getRegimeLabel");

        label.set(block, RegimePool.P1);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT regime_label FROM month_block WHERE id = ?", String.class, id)).isEqualTo("P3");
    }

    @Test
    void nullColumnsReadAsNull() throws Exception {
        long id = insert("2001-06-01", null, null);
        em.flush();
        em.clear();

        MonthBlock block = em.find(MonthBlock.class, id);

        Field label = MonthBlock.class.getDeclaredField("regimeLabel");
        label.setAccessible(true);
        assertThat(label.get(block)).isNull();
        assertThat(block.getIndicatorZ()).isNull();
    }
}

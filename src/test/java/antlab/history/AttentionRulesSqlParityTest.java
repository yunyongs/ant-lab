package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The five {@link AttentionRules} constants equal the literals of CTE {@code k} in {@code db/13_calc_attention_def.sql}. A text pin,
 * because the constants sit inside a temporary view that cannot be queried alone. Changing one side without the other fails here.
 */
class AttentionRulesSqlParityTest {

    private static final Path DEFINITION = Path.of("db/13_calc_attention_def.sql");

    private static String literal(String sql, String column) {
        Matcher m = Pattern.compile("([0-9][0-9.]*)(?:::numeric)?\\s+AS\\s+" + column + "\\b").matcher(sql);
        assertThat(m.find()).as("literal for %s in CTE k", column).isTrue();
        String value = m.group(1);
        assertThat(m.find()).as("one literal for %s", column).isFalse();
        return value;
    }

    @Test
    void 상수_다섯_개가_SQL_CTE_k와_같다() throws IOException {
        String sql = Files.readString(DEFINITION);
        assertThat(Integer.parseInt(literal(sql, "win_months"))).isEqualTo(AttentionRules.WINDOW_MONTHS);
        assertThat(new BigDecimal(literal(sql, "sd_floor"))).isEqualByComparingTo(AttentionRules.SD_FLOOR);
        assertThat(new BigDecimal(literal(sql, "z_cap"))).isEqualByComparingTo(AttentionRules.Z_CAP);
        assertThat(new BigDecimal(literal(sql, "bonus_step"))).isEqualByComparingTo(AttentionRules.BONUS_STEP);
        assertThat(Integer.parseInt(literal(sql, "streak_cap"))).isEqualTo(AttentionRules.STREAK_CAP);
    }

    @Test
    void 창_프레임의_12가_상수와_같다() throws IOException {
        Matcher m = Pattern.compile("ROWS BETWEEN (\\d+) PRECEDING AND 1 PRECEDING").matcher(Files.readString(DEFINITION));
        assertThat(m.find()).as("window frame").isTrue();
        assertThat(Integer.parseInt(m.group(1))).isEqualTo(AttentionRules.WINDOW_MONTHS);
        assertThat(m.find()).as("one frame").isFalse();
    }
}

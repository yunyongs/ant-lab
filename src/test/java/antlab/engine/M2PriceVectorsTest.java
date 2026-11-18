package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Python-owned half of the price parity: {@code m2-prices-v1.csv} is written by the engine's
 * {@code make_m2_price_vectors.py} (months from {@code m2-sb6-v1}, prices from an exact decimal product). Java only reads
 * it: every won must equal {@code MoneyMath.priceAt} over the listed months, and the months must satisfy the path rules
 * the Java side checks on an engine answer (count, range, reuse; the m2 calendar tiers are the engine's own rules).
 */
class M2PriceVectorsTest {

    private static final String HEADER = "seed,length,turn,asset,month,price";
    private static final List<Integer> LENGTHS = List.of(12, 24, 60);

    private final ParityInputs inputs = ParityInputs.load();

    private record Row(long seed, int length, int turn, String asset, YearMonth month, BigDecimal price) {
    }

    private record Case(long seed, int length) {
    }

    private List<Row> rows() {
        List<String> lines = ParityInputs.readLines("m2-prices-v1.csv");
        assertThat(lines.get(0)).isEqualTo(HEADER);
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", -1);
            rows.add(new Row(Long.parseLong(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), c[3],
                    YearMonth.parse(c[4]), new BigDecimal(c[5])));
        }
        return rows;
    }

    private Map<Case, List<YearMonth>> paths(List<Row> rows) {
        Map<Case, List<YearMonth>> paths = new LinkedHashMap<>();
        for (Row row : rows) {
            List<YearMonth> path = paths.computeIfAbsent(new Case(row.seed(), row.length()), k -> new ArrayList<>());
            if (path.size() < row.turn()) {
                path.add(row.month());
            }
            assertThat(path.get(row.turn() - 1)).as("the months of one path differ between assets").isEqualTo(row.month());
        }
        return paths;
    }

    @Test
    void 파일의_모양이_설계와_같다() {
        List<Row> rows = rows();
        Map<Case, List<YearMonth>> paths = paths(rows);
        assertThat(paths).hasSize(PriceParityVectorsTest.SEEDS.size() * LENGTHS.size());
        assertThat(rows).hasSize(PriceParityVectorsTest.SEEDS.size() * (12 + 24 + 60) * inputs.assets().size());
        for (Map.Entry<Case, List<YearMonth>> e : paths.entrySet()) {
            assertThat(e.getValue()).as(e.getKey().toString()).hasSize(e.getKey().length());
            assertThat(LENGTHS).contains(e.getKey().length());
            assertThat(inputs.months()).containsAll(e.getValue());
        }
        assertThat(paths.keySet().stream().map(Case::seed).distinct().toList())
                .containsExactlyElementsOf(PriceParityVectorsTest.SEEDS);
    }

    @Test
    void 모든_가격이_MoneyMath_priceAt_과_같다() {
        List<Row> rows = rows();
        Map<Case, List<YearMonth>> paths = paths(rows);
        for (Row row : rows) {
            List<YearMonth> path = paths.get(new Case(row.seed(), row.length()));
            assertThat(inputs.price(row.asset(), path, row.turn()))
                    .as("seed=" + row.seed() + " length=" + row.length() + " turn=" + row.turn() + " " + row.asset())
                    .isEqualByComparingTo(row.price());
        }
    }

    @Test
    void 달이_경로_규칙의_개수_범위_재사용을_지킨다() {
        for (Map.Entry<Case, List<YearMonth>> e : paths(rows()).entrySet()) {
            List<LocalDate> months = e.getValue().stream().map(m -> m.atDay(1)).toList();
            List<String> broken = PathRules.violations(months, ParityInputs.BASE_DATE, e.getKey().length()).stream()
                    .filter(rule -> !"S4".equals(rule)).toList();
            assertThat(broken).as(e.getKey().toString()).isEmpty();
        }
    }
}

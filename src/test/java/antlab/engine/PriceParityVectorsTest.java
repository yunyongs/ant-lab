package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.ParityInputs.Cell;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Java-owned half of the price parity: for fixed seeds the in-process bootstrap picks months, {@code MoneyMath.priceAt}
 * turns them into won prices, and the result is pinned in {@code p0-prices-v1.csv}. The Python tests read the same file
 * and the same {@code inputs-v1.csv}, pick the months with their own port and multiply with an exact decimal, so the
 * two languages agree on months and on every won. To rewrite the file after an intended change, run this class once
 * with the environment variable ANTLAB_PARITY_WRITE=1 and review the diff as data.
 */
class PriceParityVectorsTest {

    static final String HEADER = "seed,length,turn,asset,month,price";
    static final List<Long> SEEDS = List.of(0L, 1L, 2L, 3L, 5L, 7L, 11L, 13L, 14L, 15L, 19L, 42L, 99L, 1234567L, -1L,
            -2L, Long.MIN_VALUE, Long.MAX_VALUE, 987654321L, 31337L);
    static final List<Integer> LENGTHS = List.of(12, 24);
    private static final Path FILE = Path.of("src/test/resources/engine/parity/p0-prices-v1.csv");

    private final ParityInputs inputs = ParityInputs.load();

    @Test
    void 입력_표가_설계한_사례를_모두_담고_있다() {
        assertThat(inputs.assets()).containsExactly("KR:A001", "KR:A002", "KR:C001", "KR:C002", "US:B001", "US:B002");
        long inRange = inputs.months().stream().filter(m -> m.getYear() >= 2007 && m.getYear() <= 2014).count();
        assertThat(inRange).isEqualTo(inputs.months().size()).isGreaterThanOrEqualTo(30);
        assertThat(inputs.months()).doesNotContain(YearMonth.of(2010, 4)).contains(YearMonth.of(2010, 3),
                YearMonth.of(2010, 5));
        for (String asset : inputs.assets()) {
            assertThat(inputs.cells().stream().filter(c -> c.asset().equals(asset)).count())
                    .as(asset).isEqualTo(inputs.months().size());
        }
        for (Cell cell : inputs.cells()) {
            assertThat(cell.localReturn().scale()).isEqualTo(8);
            assertThat(cell.fxChange().scale()).isEqualTo(8);
            boolean krw = cell.currency().equals("KRW");
            assertThat(cell.asset().startsWith("KR:")).isEqualTo(krw);
            if (krw) {
                assertThat(cell.fxChange().signum()).isZero();
            }
        }
        assertThat(inputs.cells()).anyMatch(c -> c.localReturn().signum() == 0);
        assertThat(inputs.cells()).anyMatch(c -> c.currency().equals("USD") && c.fxChange().signum() == 0);
        assertThat(inputs.cells()).anyMatch(c -> c.localReturn().compareTo(new BigDecimal("-0.99")) == 0);
        assertThat(inputs.cells()).anyMatch(c -> c.localReturn().stripTrailingZeros().scale() == 8);
        assertThat(inputs.cells()).anyMatch(c -> c.currency().equals("USD") && c.fxChange().signum() != 0);
    }

    @Test
    void 만든_두_종목은_첫_턴의_정확한_곱이_반_원으로_끝난다() {
        for (String asset : List.of("KR:C001", "KR:C002")) {
            YearMonth first = inputs.months().first();
            if (asset.equals("KR:C002")) {
                first = inputs.months().stream().filter(m -> m.getMonthValue() % 2 == 0).findFirst().orElseThrow();
            }
            BigDecimal exact = inputs.exactPrice(asset, List.of(first), 1);
            assertThat(exact.remainder(BigDecimal.ONE)).as(asset).isEqualByComparingTo("0.5");
            assertThat(exact.setScale(0, RoundingMode.HALF_UP)).isNotEqualByComparingTo(
                    exact.setScale(0, RoundingMode.HALF_EVEN));
        }
    }

    @Test
    void 고정_벡터와_글자_단위로_같다() throws IOException {
        String rendered = render();
        if ("1".equals(System.getenv("ANTLAB_PARITY_WRITE"))) {
            Files.writeString(FILE, rendered, StandardCharsets.UTF_8);
            return;
        }
        assertThat(String.join("\n", ParityInputs.readLines("p0-prices-v1.csv")) + "\n").isEqualTo(rendered);
    }

    @Test
    void 벡터가_반_원_경계와_특수_칸을_실제로_지난다() {
        List<String[]> rows = rows();
        int half = 0;
        int halfEvenDiffers = 0;
        Set<String> tags = new java.util.TreeSet<>();
        for (String[] row : rows) {
            List<YearMonth> path = pathOf(Long.parseLong(row[0]), Integer.parseInt(row[1]));
            int turn = Integer.parseInt(row[2]);
            BigDecimal exact = inputs.exactPrice(row[3], path, turn);
            if (exact.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.5")) == 0) {
                half++;
                if (exact.setScale(0, RoundingMode.HALF_UP).compareTo(exact.setScale(0, RoundingMode.HALF_EVEN)) != 0) {
                    halfEvenDiffers++;
                }
            }
            Cell cell = inputs.cell(row[3], YearMonth.parse(row[4]));
            if (cell.localReturn().signum() == 0) {
                tags.add("zero-local");
            }
            if (cell.currency().equals("USD") && cell.fxChange().signum() == 0) {
                tags.add("zero-fx");
            }
            if (cell.localReturn().compareTo(new BigDecimal("-0.99")) == 0) {
                tags.add("minus-99");
            }
            if (cell.currency().equals("USD") && cell.fxChange().signum() != 0) {
                tags.add("fx");
            }
        }
        assertThat(half).isGreaterThanOrEqualTo(20);
        assertThat(halfEvenDiffers).isGreaterThanOrEqualTo(20);
        assertThat(tags).containsExactlyInAnyOrder("zero-local", "zero-fx", "minus-99", "fx");
    }

    /** The forbidden way (rounding every turn, starting from the previous rounded price) must not reproduce the file. */
    @Test
    void 매_턴_반올림한_가격에서_이어_곱하면_벡터와_달라진다() {
        int different = 0;
        for (String[] row : rows()) {
            if (Integer.parseInt(row[2]) < 2) {
                continue;
            }
            List<YearMonth> path = pathOf(Long.parseLong(row[0]), Integer.parseInt(row[1]));
            BigDecimal rounded = inputs.initialPrice(row[3]);
            for (int turn = 1; turn <= Integer.parseInt(row[2]); turn++) {
                Cell cell = inputs.cell(row[3], path.get(turn - 1));
                rounded = rounded.multiply(BigDecimal.ONE.add(cell.localReturn()))
                        .multiply(BigDecimal.ONE.add(cell.fxChange())).setScale(0, RoundingMode.HALF_UP);
            }
            if (rounded.compareTo(new BigDecimal(row[5])) != 0) {
                different++;
            }
        }
        assertThat(different).isGreaterThan(100);
    }

    @Test
    void 파일의_행_수와_순서가_설계와_같다() {
        List<String[]> rows = rows();
        assertThat(rows).hasSize(SEEDS.size() * (12 + 24) * inputs.assets().size());
        assertThat(ParityInputs.readLines("p0-prices-v1.csv").get(0)).isEqualTo(HEADER);
    }

    private List<String[]> rows() {
        List<String> lines = ParityInputs.readLines("p0-prices-v1.csv");
        return lines.subList(1, lines.size()).stream().map(l -> l.split(",", -1)).toList();
    }

    private List<YearMonth> pathOf(long seed, int length) {
        List<LocalDate> days = SimpleBootstrap.generateMonths(inputs.replayMonths(),
                new PathRequest(ParityInputs.BASE_DATE, length, seed));
        return days.stream().map(YearMonth::from).toList();
    }

    private String render() {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (long seed : SEEDS) {
            for (int length : LENGTHS) {
                List<YearMonth> path = pathOf(seed, length);
                for (int turn = 1; turn <= length; turn++) {
                    for (String asset : inputs.assets()) {
                        lines.add(seed + "," + length + "," + turn + "," + asset + "," + path.get(turn - 1) + ","
                                + inputs.price(asset, path, turn).toPlainString());
                    }
                }
            }
        }
        return String.join("\n", lines) + "\n";
    }
}

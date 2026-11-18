package antlab.engine;

import antlab.common.money.MoneyMath;
import antlab.common.money.MoneyMath.ReturnFactors;
import antlab.history.ReplayMonth;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The synthetic return table of the parity vectors ({@code engine/parity/inputs-v1.csv}), shared by the Java and the
 * Python tests. Hand-designed data, not real history; the Python tests read the same file in place.
 */
final class ParityInputs {

    static final String RESOURCE_DIR = "/engine/parity/";
    static final LocalDate BASE_DATE = SyntheticMonths.BASE_DATE;
    static final String HEADER = "month,asset,currency,local_return,fx_change,initial_price";

    record Cell(YearMonth month, String asset, String currency, BigDecimal localReturn, BigDecimal fxChange,
            BigDecimal initialPrice) {
    }

    private final List<Cell> cells;
    private final Map<String, Map<YearMonth, Cell>> byAsset = new TreeMap<>();
    private final TreeSet<YearMonth> months = new TreeSet<>();

    private ParityInputs(List<Cell> cells) {
        this.cells = cells;
        for (Cell cell : cells) {
            months.add(cell.month());
            if (byAsset.computeIfAbsent(cell.asset(), k -> new TreeMap<>()).put(cell.month(), cell) != null) {
                throw new IllegalStateException("the same (month, asset) appears twice");
            }
        }
    }

    static ParityInputs load() {
        List<String> lines = readLines("inputs-v1.csv");
        if (!lines.get(0).equals(HEADER)) {
            throw new IllegalStateException("unexpected inputs header");
        }
        List<Cell> cells = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", -1);
            cells.add(new Cell(YearMonth.parse(c[0]), c[1], c[2], new BigDecimal(c[3]), new BigDecimal(c[4]),
                    new BigDecimal(c[5])));
        }
        return new ParityInputs(cells);
    }

    static List<String> readLines(String file) {
        try (InputStream in = ParityInputs.class.getResourceAsStream(RESOURCE_DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    List<Cell> cells() {
        return cells;
    }

    List<String> assets() {
        return List.copyOf(byAsset.keySet());
    }

    TreeSet<YearMonth> months() {
        return months;
    }

    Cell cell(String asset, YearMonth month) {
        Cell cell = byAsset.get(asset).get(month);
        if (cell == null) {
            throw new IllegalStateException("no return for " + asset + " in the requested month");
        }
        return cell;
    }

    BigDecimal initialPrice(String asset) {
        return byAsset.get(asset).values().iterator().next().initialPrice();
    }

    /** Month blocks as the history view hands them out; the numbers carry no meaning. */
    List<ReplayMonth> replayMonths() {
        List<LocalDate> starts = months.stream().map(m -> m.atDay(1)).toList();
        return SyntheticMonths.withIds(starts);
    }

    /** The one money rounding of the turn price: all returns of turns 1..turn multiplied, rounded once. */
    BigDecimal price(String asset, List<YearMonth> path, int turn) {
        List<ReturnFactors> factors = new ArrayList<>(turn);
        for (YearMonth month : path.subList(0, turn)) {
            Cell cell = cell(asset, month);
            factors.add(new ReturnFactors(cell.localReturn(), cell.fxChange()));
        }
        return MoneyMath.priceAt(initialPrice(asset), factors);
    }

    /** The exact product before the rounding (BigDecimal multiply is exact). */
    BigDecimal exactPrice(String asset, List<YearMonth> path, int turn) {
        BigDecimal value = initialPrice(asset);
        for (YearMonth month : path.subList(0, turn)) {
            Cell cell = cell(asset, month);
            value = value.multiply(BigDecimal.ONE.add(cell.localReturn())).multiply(BigDecimal.ONE.add(cell.fxChange()));
        }
        return value;
    }
}

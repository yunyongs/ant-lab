package antlab.collect.price;

import antlab.collect.PriceBasis;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/**
 * The tracked target list (collect/kis-targets.csv: market, ticker, exchange_code, price_basis; no price values). It only
 * parses; {@link KisDailyPriceJob} checks every row against the asset table before the first request.
 */
public record KisTargetCatalog(List<KisTarget> targets) {

    static final String RESOURCE = "collect/kis-targets.csv";
    private static final String HEADER = "market,ticker,exchange_code,price_basis";

    public static KisTargetCatalog fromClasspath() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return fromCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    public static KisTargetCatalog fromCsv(String text) {
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IllegalArgumentException("target list must start with the header " + HEADER);
        }
        List<KisTarget> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != 4) {
                throw new IllegalArgumentException("target row needs 4 cells: " + line);
            }
            out.add(new KisTarget(cells[0], cells[1], cells[2], PriceBasis.valueOf(cells[3])));
        }
        return new KisTargetCatalog(List.copyOf(out));
    }
}

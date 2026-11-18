package antlab.collect.series;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/**
 * The tracked series list (collect/series-catalog.csv; no values). Collectors insert the rows of their source into the
 * {@code series} table when missing and refuse to run when a stored row differs from the file (stored rows are never changed).
 */
public record SeriesCatalog(List<SeriesDef> series) {

    static final String RESOURCE = "collect/series-catalog.csv";
    private static final String HEADER = "code,source,source_series_id,unit,frequency,release_lag_days,revisable";

    public static SeriesCatalog fromClasspath() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return fromCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    public static SeriesCatalog fromCsv(String text) {
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IllegalArgumentException("series catalog must start with the header " + HEADER);
        }
        List<SeriesDef> out = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != 7) {
                throw new IllegalArgumentException("series row needs 7 cells: " + line);
            }
            SeriesDef def = new SeriesDef(cells[0], SeriesSource.valueOf(cells[1]), cells[2], cells[3],
                    SeriesFrequency.valueOf(cells[4]), Integer.parseInt(cells[5]), parseFlag(cells[6]));
            if (!codes.add(def.code())) {
                throw new IllegalArgumentException("series code listed twice: " + def.code());
            }
            out.add(def);
        }
        return new SeriesCatalog(List.copyOf(out));
    }

    public List<SeriesDef> of(SeriesSource source) {
        return series.stream().filter(def -> def.source() == source).toList();
    }

    private static boolean parseFlag(String text) {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("revisable must be true or false: " + text);
        };
    }
}

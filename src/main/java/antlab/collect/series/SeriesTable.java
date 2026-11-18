package antlab.collect.series;

import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code series} table as the collectors use it: every selected catalog row gets its table row, or must match the one
 * stored (stored rows are never changed). Shared by the FRED and the ECOS job.
 */
public final class SeriesTable {

    /** A catalog row with its table id. */
    public record Resolved(SeriesDef def, long id) {
    }

    private final JdbcTemplate jdbc;

    public SeriesTable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Problems are added to {@code problems}, one text per series that cannot be used; the others are returned. */
    public List<Resolved> resolve(List<SeriesDef> selected, List<String> problems) {
        List<Resolved> out = new ArrayList<>();
        for (SeriesDef def : selected) {
            Long id = findMatching(def, problems);
            if (id != null) {
                out.add(new Resolved(def, id));
            }
        }
        return out;
    }

    private Long findMatching(SeriesDef def, List<String> problems) {
        SeriesDef stored = load(def.code());
        if (stored == null) {
            try {
                jdbc.update("""
                        INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, def.code(), def.source().name(), def.sourceSeriesId(), def.unit(), def.frequency().name(),
                        def.releaseLagDays(), def.revisable());
            } catch (DuplicateKeyException e) {
                // another process added it first; compare with what it stored
            } catch (DataAccessException e) {
                problems.add(def.code() + " could not be added to the series table: " + e.getClass().getSimpleName());
                return null;
            }
            stored = load(def.code());
        }
        if (stored == null || !sameIdentity(stored, def)) {
            problems.add(def.code() + " differs from the catalog row stored in the series table");
            return null;
        }
        return jdbc.queryForObject("SELECT id FROM series WHERE code = ?", Long.class, def.code());
    }

    /** Identity columns only: unit and release lag are informational and may be corrected in the file without stopping every run. */
    private static boolean sameIdentity(SeriesDef a, SeriesDef b) {
        return a.source() == b.source() && a.sourceSeriesId().equals(b.sourceSeriesId()) && a.frequency() == b.frequency()
                && a.revisable() == b.revisable();
    }

    private SeriesDef load(String code) {
        try {
            return jdbc.queryForObject("""
                    SELECT code, source, source_series_id, unit, frequency, release_lag_days, revisable
                    FROM series WHERE code = ?
                    """, (rs, n) -> new SeriesDef(rs.getString("code"), SeriesSource.valueOf(rs.getString("source")),
                            rs.getString("source_series_id"), rs.getString("unit"),
                            SeriesFrequency.valueOf(rs.getString("frequency")),
                            rs.getInt("release_lag_days"), rs.getBoolean("revisable")), code);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}

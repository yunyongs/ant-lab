package antlab.collect.fred;

import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunLog;
import antlab.collect.job.JobRunSummary;
import antlab.collect.job.RunLostException;
import antlab.collect.series.ClockSkewException;
import antlab.collect.series.SeriesCatalog;
import antlab.collect.series.SeriesDef;
import antlab.collect.series.SeriesObservationWriter;
import antlab.collect.series.SeriesObservationWriter.Incoming;
import antlab.collect.series.SeriesObservationWriter.Mode;
import antlab.collect.series.SeriesTable;
import antlab.collect.series.SeriesTable.Resolved;
import antlab.collect.series.SeriesSource;
import antlab.collect.series.VintageMismatchException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Collects the FRED series of the catalog and appends their observations as vintages. One call is one run: it opens a
 * job_run row first (a second fresh run is refused before any request), makes sure every selected series exists in the
 * series table and matches the catalog, then fetches and writes series by series. A failing series does not stop the
 * others; the run ends SUCCEEDED, PARTIAL or FAILED. Revisable series are read with every vintage (ALFRED) and stored
 * by the pre-vintage rule (erd 10.6); the others are read from a window behind the latest stored date.
 * Nothing here schedules the run (M1-10) and nothing logs a value, a URL or a secret.
 */
public final class FredSeriesJob implements FredJob {

    static final String TARGETS_INVALID = "TARGETS_INVALID";
    static final String DB_CONFLICT = "DB_CONFLICT";
    static final String RUN_ABANDONED = "RUN_ABANDONED";
    static final String CLOCK_SKEW = "CLOCK_SKEW";
    static final String VINTAGE_MISMATCH = "VINTAGE_MISMATCH";
    static final String DB_ERROR = "DB_ERROR";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final Set<String> RUN_STOPPING = Set.of("REQUEST_CAP", "FRED_INTERRUPTED", RUN_ABANDONED);

    private static final Logger LOG = LoggerFactory.getLogger(FredSeriesJob.class);

    private static final class Totals {
        int inserted;
        int revised;
        int unchanged;
        int failed;
        int targets;
        boolean runLevel;
        String firstCode;
        String firstDetail;

        void fail(SeriesDef def, String code, String detail) {
            failed++;
            if (firstCode == null) {
                firstCode = code;
                firstDetail = def.code() + ": " + detail;
            }
        }
    }

    private final Supplier<FredSeriesClient> clients;
    private final SeriesCatalog catalog;
    private final SeriesTable seriesTable;
    private final SeriesObservationWriter writer;
    private final JobRunLog runLog;
    private final Clock clock;
    private final FredProperties properties;

    private FredSeriesJob(Supplier<FredSeriesClient> clients, SeriesCatalog catalog, SeriesTable seriesTable,
            SeriesObservationWriter writer, JobRunLog runLog, Clock clock, FredProperties properties) {
        this.clients = clients;
        this.catalog = catalog;
        this.seriesTable = seriesTable;
        this.writer = writer;
        this.runLog = runLog;
        this.clock = clock;
        this.properties = properties;
    }

    /** {@code clients} hands out one client per run (its request counter and cap are per run). */
    public static FredSeriesJob create(Supplier<FredSeriesClient> clients, SeriesCatalog catalog, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, FredProperties properties) {
        return new FredSeriesJob(clients, catalog, new SeriesTable(jdbc), new SeriesObservationWriter(jdbc, transactions),
                new JobRunLog(jdbc, transactions, properties.staleAfter()), clock, properties);
    }

    @Override
    public JobRunSummary run(RunTrigger trigger, Set<String> codes) {
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long runId = runLog.start(JobName.FRED_SERIES, trigger, startedAt);
        FredSeriesClient client = null;
        Totals totals = new Totals();
        String detail = null;
        try {
            List<SeriesDef> selected = select(codes);
            totals.targets = selected.size();
            List<String> problems = new ArrayList<>();
            List<Resolved> resolved = seriesTable.resolve(selected, problems);
            if (!problems.isEmpty()) {
                totals.failed = selected.size();
                totals.runLevel = true;
                totals.firstCode = TARGETS_INVALID;
                detail = String.join("; ", problems.subList(0, Math.min(problems.size(), 5)));
            } else {
                client = clients.get();
                collect(client, resolved, startedAt, runId, totals);
                detail = totals.firstDetail;
            }
        } catch (TargetsInvalid e) {
            totals.failed = Math.max(totals.targets, 1);
            totals.runLevel = true;
            totals.firstCode = TARGETS_INVALID;
            detail = e.getMessage();
        } catch (RuntimeException e) {
            LOG.warn("run {} failed outside any series: {}", runId, e.getClass().getSimpleName());
            totals.failed = Math.max(totals.failed, 1);
            totals.runLevel = true;
            totals.firstCode = INTERNAL_ERROR;
            detail = e.getClass().getSimpleName();
        }
        int requests = client == null ? 0 : client.requestsMade();
        JobStatus status = totals.firstCode == null ? JobStatus.SUCCEEDED
                : totals.runLevel || totals.failed >= totals.targets ? JobStatus.FAILED : JobStatus.PARTIAL;
        JobRunSummary summary = new JobRunSummary(runId, status, requests, totals.inserted, totals.revised, totals.unchanged,
                totals.failed, totals.firstCode);
        if (!runLog.finish(summary, detail, clock.instant())) {
            status = JobStatus.FAILED;
            summary = new JobRunSummary(runId, status, requests, totals.inserted, totals.revised, totals.unchanged,
                    Math.max(totals.failed, 1), RUN_ABANDONED);
        }
        LOG.info("{} run {} {}: requests={}, inserted={}, revised={}, unchanged={}, failedSeries={}", JobName.FRED_SERIES,
                runId, status, requests, totals.inserted, totals.revised, totals.unchanged, totals.failed);
        return summary;
    }

    private List<SeriesDef> select(Set<String> codes) {
        List<SeriesDef> all = catalog.of(SeriesSource.FRED);
        if (codes.isEmpty()) {
            return all;
        }
        List<SeriesDef> picked = all.stream().filter(def -> codes.contains(def.code())).toList();
        Set<String> known = new HashSet<>();
        picked.forEach(def -> known.add(def.code()));
        for (String code : codes) {
            if (!known.contains(code)) {
                throw new TargetsInvalid(code + " is not a FRED series of the catalog");
            }
        }
        return picked;
    }

    /** Unknown selection is a targets problem, not an internal error. */
    private static final class TargetsInvalid extends RuntimeException {
        TargetsInvalid(String message) {
            super(message, null, false, false);
        }
    }

    private void collect(FredSeriesClient client, List<Resolved> resolved, Instant startedAt, long runId, Totals totals) {
        for (int i = 0; i < resolved.size(); i++) {
            SeriesDef def = resolved.get(i).def();
            String stopCode = null;
            try {
                fetchAndWrite(client, resolved.get(i), startedAt, runId, totals);
            } catch (FredException e) {
                totals.fail(def, e.code(), e.getMessage());
                LOG.warn("series {} failed: {}", def.code(), e.code());
                if (RUN_STOPPING.contains(e.code())) {
                    stopCode = e.code();
                }
            } catch (RunLostException e) {
                totals.fail(def, RUN_ABANDONED, "the run was closed as abandoned by a later run");
                LOG.warn("series {} stopped: {}", def.code(), RUN_ABANDONED);
                stopCode = RUN_ABANDONED;
            } catch (ClockSkewException e) {
                totals.fail(def, CLOCK_SKEW, "observation time is not after the stored vintage");
                LOG.warn("series {} failed: {}", def.code(), CLOCK_SKEW);
            } catch (VintageMismatchException e) {
                totals.fail(def, VINTAGE_MISMATCH, e.getMessage());
                LOG.warn("series {} failed: {}", def.code(), VINTAGE_MISMATCH);
            } catch (DuplicateKeyException e) {
                totals.fail(def, DB_CONFLICT, "another writer added the same vintage");
                LOG.warn("series {} failed: {}", def.code(), DB_CONFLICT);
            } catch (DataAccessException e) {
                totals.fail(def, DB_ERROR, e.getClass().getSimpleName());
                LOG.warn("series {} failed: {}", def.code(), DB_ERROR);
            }
            if (stopCode != null) {
                for (int rest = i + 1; rest < resolved.size(); rest++) {
                    totals.fail(resolved.get(rest).def(), stopCode, "not attempted, the run was stopped");
                }
                return;
            }
        }
    }

    private void fetchAndWrite(FredSeriesClient client, Resolved resolved, Instant startedAt, long runId, Totals totals) {
        SeriesDef def = resolved.def();
        boolean history = def.revisable();
        LocalDate start = history ? properties.historyStart() : latestWindowStart(resolved.id());
        List<FredObservation> fetched = client.observations(def.sourceSeriesId(), start, history);
        List<Incoming> rows = history ? historyRows(def, fetched) : latestRows(fetched);
        SeriesObservationWriter.Result result = writer.write(runId, startedAt, resolved.id(),
                history ? Mode.HISTORY : Mode.LATEST, start, rows);
        totals.inserted += result.inserted();
        totals.revised += result.revised();
        totals.unchanged += result.unchanged();
    }

    private LocalDate latestWindowStart(long seriesId) {
        LocalDate latest = writer.latestRefDate(seriesId);
        if (latest == null) {
            return properties.historyStart();
        }
        LocalDate behind = latest.minusDays(properties.lookbackDays());
        return behind.isBefore(properties.historyStart()) ? properties.historyStart() : behind;
    }

    /** Today's values only; a date FRED has no value for is not stored. */
    static List<Incoming> latestRows(List<FredObservation> fetched) {
        List<Incoming> out = new ArrayList<>();
        for (FredObservation row : fetched) {
            if (row.value() != null) {
                out.add(new Incoming(row.refDate(), row.value(), null, null));
            }
        }
        return out;
    }

    /**
     * Every vintage of every value. The pre-vintage rule (erd 10.6): a row that belongs to the series' first vintage and
     * whose date is more than two release periods older carries no information about when it became known, so its
     * realtime_start is stored as NULL and the engine dates it by the publication rule.
     */
    static List<Incoming> historyRows(SeriesDef def, List<FredObservation> fetched) {
        LocalDate first = fetched.stream().map(FredObservation::realtimeStart).min(Comparator.naturalOrder()).orElse(null);
        Map<LocalDate, FredObservation> newest = new HashMap<>();
        for (FredObservation row : fetched) {
            newest.merge(row.refDate(), row, (a, b) -> b.realtimeStart().isAfter(a.realtimeStart()) ? b : a);
        }
        for (FredObservation last : newest.values()) {
            if (last.value() == null || !last.realtimeEnd().equals(LocalDate.of(9999, 12, 31))) {
                throw new FredException("FRED_PARSE", "the newest vintage of a date is withdrawn or ended: " + def.code());
            }
        }
        List<Incoming> out = new ArrayList<>();
        for (FredObservation row : fetched) {
            if (row.value() == null) {
                continue;
            }
            boolean preVintage = row.realtimeStart().equals(first) && def.frequency().olderThanTwoPeriods(row.refDate(), first);
            out.add(new Incoming(row.refDate(), row.value(), preVintage ? null : row.realtimeStart(), null));
        }
        return out;
    }
}

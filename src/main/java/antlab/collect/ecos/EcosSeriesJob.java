package antlab.collect.ecos;

import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunLog;
import antlab.collect.job.JobRunSummary;
import antlab.collect.job.RunLostException;
import antlab.collect.series.ClockSkewException;
import antlab.collect.series.SeriesCatalog;
import antlab.collect.series.SeriesDef;
import antlab.collect.series.SeriesFrequency;
import antlab.collect.series.SeriesObservationWriter;
import antlab.collect.series.SeriesObservationWriter.Incoming;
import antlab.collect.series.SeriesObservationWriter.Mode;
import antlab.collect.series.SeriesSource;
import antlab.collect.series.SeriesTable;
import antlab.collect.series.SeriesTable.Resolved;
import antlab.collect.series.VintageMismatchException;
import antlab.observability.CollectMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Collects the ECOS series of the catalog and appends their values in latest-value mode (erd D48, D49). One call is one
 * run: it opens a job_run row first (a second fresh run is refused before any request), makes sure every selected series
 * exists in the series table and matches the catalog, then fetches and writes series by series. A failing series does not
 * stop the others (a rejected key or an exhausted call limit does: nothing else would work); the run ends SUCCEEDED,
 * PARTIAL or FAILED.
 * <p>
 * ECOS gives no vintages, so a revision shows up only by reading again: a revisable series (the Korean monthly ones) is
 * read from the history start every run, the others from a window behind the latest stored date. Every stored value gets
 * {@code available_on} = (end of its month for a monthly series, the day itself for a daily one) + the series' release lag;
 * an answer without a single value is a failure, never an empty success, because it means a wrong code or a silent change.
 * Nothing here schedules the run (M1-10) and nothing logs a value, a URL or a secret.
 */
public final class EcosSeriesJob implements EcosJob {

    static final String TARGETS_INVALID = "TARGETS_INVALID";
    static final String DB_CONFLICT = "DB_CONFLICT";
    static final String RUN_ABANDONED = "RUN_ABANDONED";
    static final String CLOCK_SKEW = "CLOCK_SKEW";
    static final String VINTAGE_MISMATCH = "VINTAGE_MISMATCH";
    static final String DB_ERROR = "DB_ERROR";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final Set<String> RUN_STOPPING = Set.of("REQUEST_CAP", "ECOS_INTERRUPTED", "ECOS_AUTH", "ECOS_RATE_LIMIT", RUN_ABANDONED);
    /** ECOS dates are Korean calendar dates; "today" is the end of every requested range. */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern SOURCE_ID = Pattern.compile("([0-9A-Z]{1,12})/([0-9A-Za-z]{1,20})");

    private static final Logger LOG = LoggerFactory.getLogger(EcosSeriesJob.class);

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

    private final Supplier<EcosSeriesClient> clients;
    private final SeriesCatalog catalog;
    private final SeriesTable seriesTable;
    private final SeriesObservationWriter writer;
    private final JobRunLog runLog;
    private final Clock clock;
    private final EcosProperties properties;
    private final CollectMetrics metrics;

    private EcosSeriesJob(Supplier<EcosSeriesClient> clients, SeriesCatalog catalog, SeriesTable seriesTable,
            SeriesObservationWriter writer, JobRunLog runLog, Clock clock, EcosProperties properties, CollectMetrics metrics) {
        this.clients = clients;
        this.catalog = catalog;
        this.seriesTable = seriesTable;
        this.writer = writer;
        this.runLog = runLog;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** {@code clients} hands out one client per run (its request counter and cap are per run). */
    public static EcosSeriesJob create(Supplier<EcosSeriesClient> clients, SeriesCatalog catalog, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, EcosProperties properties) {
        return create(clients, catalog, jdbc, transactions, clock, properties, CollectMetrics.none());
    }

    public static EcosSeriesJob create(Supplier<EcosSeriesClient> clients, SeriesCatalog catalog, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, EcosProperties properties,
            CollectMetrics metrics) {
        return new EcosSeriesJob(clients, catalog, new SeriesTable(jdbc), new SeriesObservationWriter(jdbc, transactions),
                new JobRunLog(jdbc, transactions, properties.staleAfter()), clock, properties, metrics);
    }

    @Override
    public JobRunSummary run(RunTrigger trigger, Set<String> codes) {
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long runId = runLog.start(JobName.ECOS_SERIES, trigger, startedAt);
        EcosSeriesClient client = null;
        Totals totals = new Totals();
        String detail = null;
        try {
            List<SeriesDef> selected = select(codes);
            totals.targets = selected.size();
            List<String> problems = new ArrayList<>();
            for (SeriesDef def : selected) {
                String problem = problemOf(def);
                if (problem != null) {
                    problems.add(problem);
                }
            }
            List<Resolved> resolved = problems.isEmpty() ? seriesTable.resolve(selected, problems) : List.of();
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
        metrics.run(JobName.ECOS_SERIES, status);
        LOG.info("{} run {} {}: requests={}, inserted={}, revised={}, unchanged={}, failedSeries={}", JobName.ECOS_SERIES,
                runId, status, requests, totals.inserted, totals.revised, totals.unchanged, totals.failed);
        return summary;
    }

    private List<SeriesDef> select(Set<String> codes) {
        List<SeriesDef> all = catalog.of(SeriesSource.ECOS);
        if (codes.isEmpty()) {
            return all;
        }
        List<SeriesDef> picked = all.stream().filter(def -> codes.contains(def.code())).toList();
        Set<String> known = new HashSet<>();
        picked.forEach(def -> known.add(def.code()));
        for (String code : codes) {
            if (!known.contains(code)) {
                throw new TargetsInvalid(code + " is not an ECOS series of the catalog");
            }
        }
        return picked;
    }

    /** An ECOS row is written as STATISTIC/ITEM and is daily or monthly; anything else cannot be requested. */
    private static String problemOf(SeriesDef def) {
        if (!SOURCE_ID.matcher(def.sourceSeriesId()).matches()) {
            return def.code() + " has no STATISTIC/ITEM source id";
        }
        if (def.frequency() == SeriesFrequency.WEEKLY) {
            return def.code() + " is weekly; ECOS series are daily or monthly";
        }
        return null;
    }

    /** Unknown selection is a targets problem, not an internal error. */
    private static final class TargetsInvalid extends RuntimeException {
        TargetsInvalid(String message) {
            super(message, null, false, false);
        }
    }

    private void collect(EcosSeriesClient client, List<Resolved> resolved, Instant startedAt, long runId, Totals totals) {
        for (int i = 0; i < resolved.size(); i++) {
            SeriesDef def = resolved.get(i).def();
            String stopCode = null;
            try {
                fetchAndWrite(client, resolved.get(i), startedAt, runId, totals);
            } catch (EcosException e) {
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

    private void fetchAndWrite(EcosSeriesClient client, Resolved resolved, Instant startedAt, long runId, Totals totals) {
        SeriesDef def = resolved.def();
        EcosCycle cycle = def.frequency() == SeriesFrequency.MONTHLY ? EcosCycle.M : EcosCycle.D;
        String[] id = def.sourceSeriesId().split("/");
        LocalDate start = def.revisable() ? properties.historyStart() : latestWindowStart(resolved.id());
        if (cycle == EcosCycle.M) {
            // the answer dates a month by its first day: ask and read back from the same day
            start = start.withDayOfMonth(1);
        }
        LocalDate end = LocalDate.now(clock.withZone(SEOUL));
        List<EcosObservation> fetched = client.observations(id[0], id[1], cycle, start, end);
        if (fetched.isEmpty()) {
            throw new EcosException("ECOS_EMPTY", "the answer holds no value for the requested range");
        }
        List<Incoming> rows = new ArrayList<>();
        for (EcosObservation row : fetched) {
            rows.add(new Incoming(row.refDate(), row.value(), null, availableOn(def, row.refDate())));
        }
        SeriesObservationWriter.Result result = writer.write(runId, startedAt, clock.instant(), resolved.id(), Mode.LATEST, start, rows);
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

    /** The first day the value may be used: end of the month (monthly) or the day itself (daily), plus the release lag. */
    static LocalDate availableOn(SeriesDef def, LocalDate refDate) {
        LocalDate base = def.frequency() == SeriesFrequency.MONTHLY ? YearMonth.from(refDate).atEndOfMonth() : refDate;
        return base.plusDays(def.releaseLagDays());
    }
}

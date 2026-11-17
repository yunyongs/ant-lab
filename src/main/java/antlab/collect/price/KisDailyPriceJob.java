package antlab.collect.price;

import antlab.collect.CollectJob;
import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.collect.PriceBasis;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunLog;
import antlab.collect.job.JobRunSummary;
import antlab.collect.job.RunLostException;
import antlab.collect.kis.DailyClose;
import antlab.collect.kis.KisException;
import antlab.collect.kis.KisExchange;
import antlab.collect.kis.KisQuoteClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Collects the daily closes of the target list from KIS and appends them as vintages. One call is one run: it opens a
 * job_run row first (a second fresh run is refused before any request), checks every selected target against the asset
 * table, then fetches and writes target by target. A failing target does not stop the others; the run ends SUCCEEDED,
 * PARTIAL or FAILED. Nothing here schedules the run (M1-10) and nothing logs a price or a secret.
 */
public final class KisDailyPriceJob implements CollectJob {

    static final String TARGETS_INVALID = "TARGETS_INVALID";
    static final String DB_CONFLICT = "DB_CONFLICT";
    static final String RUN_ABANDONED = "RUN_ABANDONED";
    static final String CLOCK_SKEW = "CLOCK_SKEW";
    static final String DB_ERROR = "DB_ERROR";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final Set<String> RUN_STOPPING = Set.of("REQUEST_CAP", "KIS_INTERRUPTED", RUN_ABANDONED);

    private static final Logger LOG = LoggerFactory.getLogger(KisDailyPriceJob.class);

    private record Asset(long id, String assetType) {
    }

    private record Resolved(KisTarget target, Asset asset) {
    }

    private static final class Totals {
        int inserted;
        int revised;
        int unchanged;
        int failed;
        int targets;
        boolean runLevel;
        String firstCode;
        String firstDetail;

        void fail(KisTarget target, String code, String detail) {
            failed++;
            if (firstCode == null) {
                firstCode = code;
                firstDetail = target.key() + ": " + detail;
            }
        }
    }

    private final Supplier<KisQuoteClient> clients;
    private final KisTargetCatalog catalog;
    private final JdbcTemplate jdbc;
    private final DailyPriceWriter writer;
    private final JobRunLog runLog;
    private final Clock clock;
    private final PriceJobProperties properties;

    private KisDailyPriceJob(Supplier<KisQuoteClient> clients, KisTargetCatalog catalog, JdbcTemplate jdbc,
            DailyPriceWriter writer, JobRunLog runLog, Clock clock, PriceJobProperties properties) {
        this.clients = clients;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.writer = writer;
        this.runLog = runLog;
        this.clock = clock;
        this.properties = properties;
    }

    /** {@code clients} hands out one client per run (its request counter and cap are per run). */
    public static KisDailyPriceJob create(Supplier<KisQuoteClient> clients, KisTargetCatalog catalog, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, PriceJobProperties properties) {
        return new KisDailyPriceJob(clients, catalog, jdbc, new DailyPriceWriter(jdbc, transactions),
                new JobRunLog(jdbc, transactions, properties.staleAfter()), clock, properties);
    }

    @Override
    public JobRunSummary run(RunTrigger trigger, TargetFilter filter) {
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long runId = runLog.start(JobName.KIS_DAILY_PRICE, trigger, startedAt);
        KisQuoteClient client = null;
        Totals totals = new Totals();
        String detail = null;
        try {
            List<KisTarget> selected = select(filter);
            totals.targets = selected.size();
            List<String> problems = new ArrayList<>();
            List<Resolved> resolved = resolve(selected, problems);
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
            LOG.warn("run {} failed outside any target: {}", runId, e.getClass().getSimpleName());
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
        LOG.info("{} run {} {}: requests={}, inserted={}, revised={}, unchanged={}, failedTargets={}", JobName.KIS_DAILY_PRICE,
                runId, status, requests, totals.inserted, totals.revised, totals.unchanged, totals.failed);
        return summary;
    }

    private List<KisTarget> select(TargetFilter filter) {
        if (filter.isAll()) {
            return catalog.targets();
        }
        List<KisTarget> picked = catalog.targets().stream().filter(t -> filter.keys().contains(t.key())).toList();
        Set<String> known = new HashSet<>();
        picked.forEach(t -> known.add(t.key()));
        for (String key : filter.keys()) {
            if (!known.contains(key)) {
                throw new TargetsInvalid(key + " is not in the target list");
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

    /** Every selected row must fit its asset: rule C4 (exchange only for US) and C5 (price basis by market and type). */
    private List<Resolved> resolve(List<KisTarget> selected, List<String> problems) {
        List<Resolved> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KisTarget target : selected) {
            if (!seen.add(target.key())) {
                problems.add(target.key() + " is listed twice");
                continue;
            }
            Asset asset;
            try {
                asset = jdbc.queryForObject("SELECT id, asset_type FROM asset WHERE market = ? AND ticker = ?",
                        (rs, n) -> new Asset(rs.getLong("id"), rs.getString("asset_type")), target.market(), target.ticker());
            } catch (EmptyResultDataAccessException e) {
                problems.add(target.key() + " is not in the asset table");
                continue;
            }
            boolean kr = "KR".equals(target.market());
            if (!kr && !"US".equals(target.market())) {
                problems.add(target.key() + " has an unknown market");
            } else if (kr && !target.exchangeCode().isEmpty()) {
                problems.add(target.key() + " is a KR row with an exchange code");
            } else if (!kr && !isExchange(target.exchangeCode())) {
                problems.add(target.key() + " is a US row without a valid exchange code");
            } else if (target.basis() != expectedBasis(kr, asset.assetType())) {
                problems.add(target.key() + " has price basis " + target.basis() + " but its source rule says "
                        + expectedBasis(kr, asset.assetType()));
            } else {
                out.add(new Resolved(target, asset));
            }
        }
        return out;
    }

    private static boolean isExchange(String code) {
        for (KisExchange exchange : KisExchange.values()) {
            if (exchange.name().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** KR ETF closes are stored raw, KR stocks and US securities adjusted (PLAN 7). */
    private static PriceBasis expectedBasis(boolean kr, String assetType) {
        return kr && "ETF".equals(assetType) ? PriceBasis.RAW : PriceBasis.ADJUSTED;
    }

    private void collect(KisQuoteClient client, List<Resolved> resolved, Instant startedAt, long runId, Totals totals) {
        for (int i = 0; i < resolved.size(); i++) {
            KisTarget target = resolved.get(i).target();
            String stopCode = null;
            try {
                fetchAndWrite(client, resolved.get(i), startedAt, runId, totals);
            } catch (KisException e) {
                totals.fail(target, e.code(), e.getMessage());
                LOG.warn("target {} failed: {}", target.key(), e.code());
                if (RUN_STOPPING.contains(e.code())) {
                    stopCode = e.code();
                }
            } catch (RunLostException e) {
                totals.fail(target, RUN_ABANDONED, "the run was closed as abandoned by a later run");
                LOG.warn("target {} stopped: {}", target.key(), RUN_ABANDONED);
                stopCode = RUN_ABANDONED;
            } catch (ClockSkewException e) {
                totals.fail(target, CLOCK_SKEW, "observation time is not after the stored vintage");
                LOG.warn("target {} failed: {}", target.key(), CLOCK_SKEW);
            } catch (DuplicateKeyException e) {
                totals.fail(target, DB_CONFLICT, "another writer added the same vintage");
                LOG.warn("target {} failed: {}", target.key(), DB_CONFLICT);
            } catch (DataAccessException e) {
                totals.fail(target, DB_ERROR, e.getClass().getSimpleName());
                LOG.warn("target {} failed: {}", target.key(), DB_ERROR);
            }
            if (stopCode != null) {
                for (int rest = i + 1; rest < resolved.size(); rest++) {
                    totals.fail(resolved.get(rest).target(), stopCode, "not attempted, the run was stopped");
                }
                return;
            }
        }
    }

    private void fetchAndWrite(KisQuoteClient client, Resolved resolved, Instant startedAt, long runId, Totals totals) {
        KisTarget target = resolved.target();
        long assetId = resolved.asset().id();
        Optional<LocalDate> latest = writer.latestTradeDate(assetId, target.basis());
        Optional<PriceWindow> window = PriceWindow.of(startedAt, target.market(), latest.orElse(null),
                properties.lookbackDays(), properties.initialDays());
        if (window.isEmpty()) {
            return;
        }
        PriceWindow w = window.get();
        List<DailyClose> closes = "KR".equals(target.market())
                ? client.domesticDaily(target.ticker(), w.from(), w.to(), target.basis())
                : client.overseasDaily(target.ticker(), KisExchange.valueOf(target.exchangeCode()), w.from(), w.to());
        String exchange = target.exchangeCode().isEmpty() ? null : target.exchangeCode();
        DailyPriceWriter.Result result = writer.write(runId, startedAt, assetId, target.basis(), exchange, w, w.to(), closes);
        totals.inserted += result.inserted();
        totals.revised += result.revised();
        totals.unchanged += result.unchanged();
        if (result.lateRevisions() > 0) {
            LOG.warn("target {}: {} close(s) before the last session were revised, check the month derivation",
                    target.key(), result.lateRevisions());
        }
    }
}

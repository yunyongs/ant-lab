package antlab.observability;

import antlab.collect.JobName;
import antlab.collect.JobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Collector counters. Same rule as {@link GameMetrics}: counters ignore transactions, so a method is called only where the
 * outcome is already final. {@code antlab.collect.run} moves once per finished run; {@code antlab.collect.request} moves once
 * per request attempt, when its fate is known. Tag values come from enums and fixed code lists, never from a request, a
 * URL or an error message.
 */
@Component
public class CollectMetrics {

    /** The upstream a request went to. */
    public enum Source {
        KIS, FRED, ECOS
    }

    private static final Logger LOG = LoggerFactory.getLogger(CollectMetrics.class);
    private static final String RUN = "antlab.collect.run";
    private static final String REQUEST = "antlab.collect.request";

    /** The request was answered and understood. */
    public static final String OK = "OK";
    /** The answer was a rate-limit refusal and the request is sent again after a wait. */
    public static final String RATE_RETRY = "RATE_RETRY";
    /** KIS refused the access token and a new one is issued before the request is sent again. */
    public static final String TOKEN_RETRY = "TOKEN_RETRY";
    /** Any code outside the fixed list of the source. */
    public static final String OTHER = "OTHER";

    // Outcomes a request can end with: OK, the two retry marks and the stable codes of each client's exception.
    // REQUEST_CAP counts an attempt the per-run cap refused; nothing was sent for it.
    static final Map<Source, List<String>> OUTCOMES = java.util.Collections.unmodifiableMap(new EnumMap<>(Map.of(
            Source.KIS, List.of(OK, RATE_RETRY, TOKEN_RETRY, "KIS_RATE_LIMIT", "KIS_HTTP", "KIS_REJECTED",
                    "KIS_FORBIDDEN_REQUEST", "KIS_PARSE", "KIS_INTERRUPTED", "REQUEST_CAP", OTHER),
            Source.FRED, List.of(OK, RATE_RETRY, "FRED_RATE_LIMIT", "FRED_HTTP", "FRED_REJECTED", "FRED_FORBIDDEN_REQUEST",
                    "FRED_PARSE", "FRED_INTERRUPTED", "REQUEST_CAP", OTHER),
            Source.ECOS, List.of(OK, RATE_RETRY, "ECOS_RATE_LIMIT", "ECOS_AUTH", "ECOS_HTTP", "ECOS_REJECTED",
                    "ECOS_FORBIDDEN_REQUEST", "ECOS_PARSE", "ECOS_INTERRUPTED", "REQUEST_CAP", OTHER))));
    // A run ends in one of these; RUNNING is never final.
    static final Set<JobStatus> FINAL_STATUSES = Set.of(JobStatus.SUCCEEDED, JobStatus.PARTIAL, JobStatus.FAILED);

    private final MeterRegistry registry;

    public CollectMetrics(MeterRegistry registry) {
        this.registry = registry;
        // A series that first appears with its first event is missed by rate() and increase(), so every pair starts at 0.
        for (JobName job : JobName.values()) {
            for (JobStatus status : FINAL_STATUSES) {
                runCounter(job, status);
            }
        }
        OUTCOMES.forEach((source, outcomes) -> outcomes.forEach(outcome -> requestCounter(source, outcome)));
    }

    /** The fixed outcome values of a source; no other value is ever a tag. */
    public static List<String> outcomes(Source source) {
        return OUTCOMES.get(source);
    }

    /** A counter set that records into a private registry; for jobs and clients built without the application context. */
    public static CollectMetrics none() {
        return new CollectMetrics(new SimpleMeterRegistry());
    }

    /** Called once per run, after the run row was closed. A status that is not final is ignored. */
    public void run(JobName job, JobStatus status) {
        if (FINAL_STATUSES.contains(status)) {
            safely(() -> runCounter(job, status).increment());
        }
    }

    /** Called once per request attempt. {@code outcome} is {@link #OK}, a retry mark or the stable code of the failure. */
    public void request(Source source, String outcome) {
        safely(() -> requestCounter(source, outcome != null && OUTCOMES.get(source).contains(outcome) ? outcome : OTHER).increment());
    }

    private Counter runCounter(JobName job, JobStatus status) {
        return Counter.builder(RUN).description("Collector runs by job and final status; counted once, after the run was closed")
                .tag("job", job.name()).tag("status", status.name()).register(registry);
    }

    private Counter requestCounter(Source source, String outcome) {
        return Counter.builder(REQUEST)
                .description("Collector requests by upstream and outcome; counted once per attempt, when its fate is known")
                .tag("source", source.name()).tag("outcome", outcome).register(registry);
    }

    // A metric failure must never change a run or hide its error.
    private static void safely(Runnable increment) {
        try {
            increment.run();
        } catch (RuntimeException e) {
            LOG.warn("metric increment failed", e);
        }
    }
}

package antlab.collect;

import antlab.collect.job.JobRunSummary;
import antlab.collect.price.TargetFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs the daily price job once at startup and exits: 0 when the run SUCCEEDED, 1 otherwise. Active only with
 * {@code antlab.collect.run-once=KIS_DAILY_PRICE}, which also needs {@code antlab.collect.kis.enabled=true}.
 * {@code antlab.collect.targets=KR:005930,US:AAPL} narrows the run.
 */
@Component
@ConditionalOnProperty(prefix = "antlab.collect", name = "run-once", havingValue = "KIS_DAILY_PRICE")
public class CollectRunOnceRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CollectRunOnceRunner.class);

    private final CollectJob job;
    private final TargetFilter filter;
    private final ProcessExit exit;

    public CollectRunOnceRunner(ObjectProvider<CollectJob> jobs, @Value("${antlab.collect.targets:}") String targets,
            ConfigurableApplicationContext context, ObjectProvider<ProcessExit> exit) {
        this.job = jobs.getIfAvailable();
        if (job == null) {
            throw new IllegalStateException("antlab.collect.run-once needs antlab.collect.kis.enabled=true");
        }
        this.filter = TargetFilter.parse(targets);
        this.exit = exit.getIfAvailable(() -> code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 1;
        try {
            JobRunSummary summary = job.run(RunTrigger.MANUAL, filter);
            code = summary.status() == JobStatus.SUCCEEDED ? 0 : 1;
        } catch (RuntimeException e) {
            LOG.error("collector run did not start: {}", e.getMessage());
        }
        exit.exit(code);
    }
}

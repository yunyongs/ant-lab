package antlab.regime;

import antlab.collect.ProcessExit;
import antlab.common.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs the regime label job once at startup and exits: 0 when every month was filled or already held the answer, 1 otherwise.
 * Active only with {@code antlab.regime.run-once=LABEL_MONTHS}. The engine must be reachable ({@code antlab.engine.url} and
 * the token), and a stored label that differs from the engine's answer stops the run without a write (the owner clears the
 * regime columns first to relabel).
 */
@Component
@ConditionalOnProperty(prefix = "antlab.regime", name = "run-once", havingValue = "LABEL_MONTHS")
public class RegimeLabelRunOnceRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RegimeLabelRunOnceRunner.class);

    private final RegimeLabelJob job;
    private final ProcessExit exit;

    RegimeLabelRunOnceRunner(RegimeLabelJob job, ConfigurableApplicationContext context, ObjectProvider<ProcessExit> exit) {
        this.job = job;
        this.exit = exit.getIfAvailable(() -> code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 1;
        try {
            RegimeLabelJob.Outcome outcome = job.run();
            LOG.info("regime.labelled rows={} filled={} unchanged={}", outcome.rows(), outcome.filled(), outcome.unchanged());
            code = 0;
        } catch (RuntimeException e) {
            LOG.error("regime.failed reason={}", describe(e));
        }
        exit.exit(code);
    }

    /**
     * What may be logged about a failure. Our own exceptions and the engine client's carry no month or value by design; any
     * other exception (a database error echoes the failing row) is reduced to its class name.
     */
    static String describe(RuntimeException e) {
        boolean safe = e instanceof DomainException || e instanceof MonthRegimeWriter.ConflictException
                || e instanceof MonthRegimeWriter.MonthsChangedException || e instanceof RegimeAnswerCheck.AnswerRejectedException
                || e instanceof RegimeLabelJob.RequestRefusedException;
        return safe ? e.getMessage() : e.getClass().getSimpleName();
    }
}

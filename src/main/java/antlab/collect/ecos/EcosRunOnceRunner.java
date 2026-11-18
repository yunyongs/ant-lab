package antlab.collect.ecos;

import antlab.collect.JobStatus;
import antlab.collect.ProcessExit;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobRunSummary;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
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
 * Runs the ECOS series job once at startup and exits: 0 when the run SUCCEEDED, 1 otherwise. Active only with
 * {@code antlab.collect.run-once=ECOS_SERIES}, which also needs {@code antlab.collect.ecos.enabled=true}.
 * {@code antlab.collect.ecos.series=KR_CPI,KR_CLI} narrows the run to those catalog codes.
 */
@Component
@ConditionalOnProperty(prefix = "antlab.collect", name = "run-once", havingValue = "ECOS_SERIES")
public class EcosRunOnceRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EcosRunOnceRunner.class);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    private final EcosJob job;
    private final Set<String> codes;
    private final ProcessExit exit;

    public EcosRunOnceRunner(ObjectProvider<EcosJob> jobs, @Value("${antlab.collect.ecos.series:}") String series,
            ConfigurableApplicationContext context, ObjectProvider<ProcessExit> exit) {
        this.job = jobs.getIfAvailable();
        if (job == null) {
            throw new IllegalStateException("antlab.collect.run-once=ECOS_SERIES needs antlab.collect.ecos.enabled=true");
        }
        this.codes = parse(series);
        this.exit = exit.getIfAvailable(() -> code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    static Set<String> parse(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(",", -1)) {
            String code = part.strip();
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("a series is written as its catalog code, for example KR_CPI");
            }
            out.add(code);
        }
        return Set.copyOf(out);
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 1;
        try {
            JobRunSummary summary = job.run(RunTrigger.MANUAL, codes);
            code = summary.status() == JobStatus.SUCCEEDED ? 0 : 1;
        } catch (RuntimeException e) {
            LOG.error("collector run did not start: {}", e.getMessage());
        }
        exit.exit(code);
    }
}

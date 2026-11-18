package antlab.collect.ecos;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.collect.ProcessExit;
import antlab.collect.RunTrigger;
import antlab.collect.job.JobAlreadyRunningException;
import antlab.collect.job.JobRunSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Property gates: the prod guard and the one-shot runner. No database and no network. */
class EcosWiringTest {

    private static final String ENABLED = "antlab.collect.ecos.enabled=true";

    private final List<Integer> exits = new ArrayList<>();
    private final List<Set<String>> selections = new ArrayList<>();

    @Test
    void prod_프로필에서_수집을_켜면_기동이_실패한다() {
        new ApplicationContextRunner().withUserConfiguration(EcosProdGuard.class)
                .withPropertyValues("spring.profiles.active=prod", ENABLED).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("prod").hasMessageContaining("no ECOS key");
                });
    }

    @Test
    void prod_프로필이어도_꺼져_있으면_뜨고_다른_프로필에서는_켜도_뜬다() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(EcosProdGuard.class);
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "antlab.collect.ecos.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=local", ENABLED).run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=test", ENABLED).run(context -> assertThat(context).hasNotFailed());
    }

    private JobRunSummary summary(JobStatus status) {
        return new JobRunSummary(1, status, 0, 0, 0, 0, status == JobStatus.SUCCEEDED ? 0 : 1,
                status == JobStatus.SUCCEEDED ? null : "ECOS_HTTP");
    }

    private ApplicationContextRunner runnerContext(AtomicReference<RuntimeException> failure, JobStatus status) {
        return new ApplicationContextRunner().withUserConfiguration(EcosRunOnceRunner.class)
                .withBean(EcosJob.class, () -> (trigger, codes) -> {
                    selections.add(codes);
                    if (failure.get() != null) {
                        throw failure.get();
                    }
                    assertThat(trigger).isEqualTo(RunTrigger.MANUAL);
                    return summary(status);
                })
                .withBean(ProcessExit.class, () -> exits::add);
    }

    @Test
    void 속성이_없으면_러너가_없다() {
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(EcosRunOnceRunner.class);
            assertThat(selections).isEmpty();
        });
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED).withPropertyValues("antlab.collect.run-once=KIS_DAILY_PRICE")
                .run(context -> assertThat(context).doesNotHaveBean(EcosRunOnceRunner.class));
    }

    @Test
    void 속성이_있으면_한_번_돌리고_성공이면_종료_코드_0이다() {
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED)
                .withPropertyValues("antlab.collect.run-once=ECOS_SERIES", "antlab.collect.ecos.series=KR_CPI,KR_CLI")
                .run(context -> {
                    context.getBean(EcosRunOnceRunner.class).run(null);

                    assertThat(exits).containsExactly(0);
                    assertThat(selections).containsExactly(Set.of("KR_CPI", "KR_CLI"));
                });
    }

    @Test
    void 코드를_주지_않으면_전체를_돌린다() {
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED).withPropertyValues("antlab.collect.run-once=ECOS_SERIES")
                .run(context -> {
                    context.getBean(EcosRunOnceRunner.class).run(null);

                    assertThat(selections).containsExactly(Set.of());
                });
    }

    @Test
    void 부분_성공과_실패와_이미_실행_중이면_종료_코드_1이다() {
        for (JobStatus status : List.of(JobStatus.PARTIAL, JobStatus.FAILED)) {
            exits.clear();
            runnerContext(new AtomicReference<>(), status).withPropertyValues("antlab.collect.run-once=ECOS_SERIES")
                    .run(context -> context.getBean(EcosRunOnceRunner.class).run(null));
            assertThat(exits).as(status.name()).containsExactly(1);
        }
        exits.clear();
        runnerContext(new AtomicReference<>(new JobAlreadyRunningException(JobName.ECOS_SERIES)), JobStatus.SUCCEEDED)
                .withPropertyValues("antlab.collect.run-once=ECOS_SERIES")
                .run(context -> context.getBean(EcosRunOnceRunner.class).run(null));
        assertThat(exits).containsExactly(1);
    }

    @Test
    void 잡이_없는데_속성만_있으면_이유를_말하고_기동이_실패한다() {
        new ApplicationContextRunner().withUserConfiguration(EcosRunOnceRunner.class)
                .withPropertyValues("antlab.collect.run-once=ECOS_SERIES").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("antlab.collect.ecos.enabled=true");
                });
    }

    @Test
    void 코드_문자열이_틀리면_잡을_부르기_전에_기동이_실패한다() {
        for (String bad : new String[] {"kr_cpi", "CPI;DROP", "KR_CPI,,KR_CLI", "KR CPI"}) {
            runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED)
                    .withPropertyValues("antlab.collect.run-once=ECOS_SERIES", "antlab.collect.ecos.series=" + bad)
                    .run(context -> assertThat(context).as(bad).hasFailed());
        }
        assertThat(selections).isEmpty();
    }
}

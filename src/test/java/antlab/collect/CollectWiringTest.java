package antlab.collect;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.job.JobAlreadyRunningException;
import antlab.collect.job.JobRunSummary;
import antlab.collect.price.TargetFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Property gates: the prod guard and the one-shot runner. No database and no network. */
class CollectWiringTest {

    private static final String ENABLED = "antlab.collect.kis.enabled=true";

    private final List<Integer> exits = new ArrayList<>();
    private final List<TargetFilter> filters = new ArrayList<>();

    private JobRunSummary summary(JobStatus status) {
        return new JobRunSummary(1, status, 0, 0, 0, 0, status == JobStatus.SUCCEEDED ? 0 : 1,
                status == JobStatus.SUCCEEDED ? null : "KIS_HTTP");
    }

    private ApplicationContextRunner runnerContext(AtomicReference<RuntimeException> failure, JobStatus status) {
        return new ApplicationContextRunner().withUserConfiguration(CollectRunOnceRunner.class)
                .withBean(CollectJob.class, () -> (trigger, filter) -> {
                    filters.add(filter);
                    if (failure.get() != null) {
                        throw failure.get();
                    }
                    assertThat(trigger).isEqualTo(RunTrigger.MANUAL);
                    return summary(status);
                })
                .withBean(ProcessExit.class, () -> exits::add);
    }

    @Test
    void prod_프로필에서_수집을_켜면_기동이_실패한다() {
        new ApplicationContextRunner().withUserConfiguration(CollectProdGuard.class)
                .withPropertyValues("spring.profiles.active=prod", ENABLED).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("prod").hasMessageContaining("no KIS real key");
                });
    }

    @Test
    void prod_프로필이어도_꺼져_있으면_뜨고_다른_프로필에서는_켜도_뜬다() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CollectProdGuard.class);
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "antlab.collect.kis.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=local", ENABLED).run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=test", ENABLED).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 속성이_없으면_러너가_없고_잡을_부르지_않는다() {
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CollectRunOnceRunner.class);
            assertThat(filters).isEmpty();
            assertThat(exits).isEmpty();
        });
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED).withPropertyValues("antlab.collect.run-once=OTHER_JOB")
                .run(context -> assertThat(context).doesNotHaveBean(CollectRunOnceRunner.class));
    }

    @Test
    void 속성이_있으면_한_번_돌리고_성공이면_종료_코드_0이다() {
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED)
                .withPropertyValues("antlab.collect.run-once=KIS_DAILY_PRICE", "antlab.collect.targets=KR:005930,US:SPY")
                .run(context -> {
                    context.getBean(CollectRunOnceRunner.class).run(null);

                    assertThat(exits).containsExactly(0);
                    assertThat(filters).hasSize(1);
                    assertThat(filters.getFirst().keys()).containsExactlyInAnyOrder("KR:005930", "US:SPY");
                });
    }

    @Test
    void 부분_성공과_실패와_이미_실행_중이면_종료_코드_1이다() {
        for (JobStatus status : List.of(JobStatus.PARTIAL, JobStatus.FAILED)) {
            exits.clear();
            runnerContext(new AtomicReference<>(), status).withPropertyValues("antlab.collect.run-once=KIS_DAILY_PRICE")
                    .run(context -> context.getBean(CollectRunOnceRunner.class).run(null));
            assertThat(exits).as(status.name()).containsExactly(1);
        }
        exits.clear();
        runnerContext(new AtomicReference<>(new JobAlreadyRunningException(JobName.KIS_DAILY_PRICE)), JobStatus.SUCCEEDED)
                .withPropertyValues("antlab.collect.run-once=KIS_DAILY_PRICE")
                .run(context -> context.getBean(CollectRunOnceRunner.class).run(null));
        assertThat(exits).containsExactly(1);
    }

    @Test
    void 잡이_없는데_속성만_있으면_이유를_말하고_기동이_실패한다() {
        new ApplicationContextRunner().withUserConfiguration(CollectRunOnceRunner.class)
                .withPropertyValues("antlab.collect.run-once=KIS_DAILY_PRICE").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("antlab.collect.kis.enabled=true");
                });
    }

    @Test
    void 대상_문자열이_틀리면_잡을_부르기_전에_기동이_실패한다() {
        runnerContext(new AtomicReference<>(), JobStatus.SUCCEEDED)
                .withPropertyValues("antlab.collect.run-once=KIS_DAILY_PRICE", "antlab.collect.targets=005930")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(filters).isEmpty();
                });
    }
}

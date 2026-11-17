package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KisPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KisClientConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 기본은_꺼져_있어_키가_없어도_뜨고_공장이_없다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KisQuoteClientFactory.class);
            KisProperties properties = context.getBean(KisProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.rps()).isEqualTo(2);
            assertThat(properties.maxRequestsPerRun()).isEqualTo(60);
            assertThat(properties.tokenCachePath()).isNull();
        });
    }

    @Test
    void 켰는데_키가_비면_기동이_실패하고_메시지는_변수_이름만_말한다() {
        runner.withPropertyValues("antlab.collect.kis.enabled=true", "antlab.collect.kis.app-secret=FAKE-APP-SECRET-0001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("KIS_APP_KEY").hasMessageNotContaining("KIS_APP_SECRET")
                            .hasMessageNotContaining("FAKE-APP-SECRET-0001");
                });
        runner.withPropertyValues("antlab.collect.kis.enabled=true").run(context -> assertThat(context.getStartupFailure())
                .rootCause().hasMessageContaining("KIS_APP_KEY").hasMessageContaining("KIS_APP_SECRET"));
    }

    @Test
    void 키가_있으면_공장이_생기고_토큰_디렉터리가_빈_문자열이면_메모리_전용이다() {
        runner.withPropertyValues("antlab.collect.kis.enabled=true", "antlab.collect.kis.app-key=FAKE-APP-KEY-0001",
                "antlab.collect.kis.app-secret=FAKE-APP-SECRET-0001", "antlab.collect.kis.token-cache-dir=").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KisQuoteClientFactory.class);
                    assertThat(context.getBean(KisProperties.class).tokenCachePath()).isNull();
                    assertThat(context.getBean(KisProperties.class).toString())
                            .doesNotContain("FAKE-APP-KEY-0001").doesNotContain("FAKE-APP-SECRET-0001");
                });
    }

    @Test
    void 초당_요청이_3이거나_0이면_바인딩이_실패한다() {
        for (String rps : new String[] {"3", "0", "-1"}) {
            runner.withPropertyValues("antlab.collect.kis.rps=" + rps).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("rps");
            });
        }
        runner.withPropertyValues("antlab.collect.kis.rps=1").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("antlab.collect.kis.rps=2").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 실행당_요청_상한은_1_이상이다() {
        runner.withPropertyValues("antlab.collect.kis.max-requests-per-run=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 토큰_디렉터리가_상대_경로면_기동이_실패하고_절대_경로는_된다() {
        String base = "antlab.collect.kis.";
        runner.withPropertyValues(base + "enabled=true", base + "app-key=FAKE-APP-KEY-0001", base + "app-secret=FAKE-APP-SECRET-0001",
                base + "token-cache-dir=data/cache/kis").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("KIS_TOKEN_CACHE_DIR").hasMessageContaining("absolute");
                });
        runner.withPropertyValues(base + "enabled=true", base + "app-key=FAKE-APP-KEY-0001", base + "app-secret=FAKE-APP-SECRET-0001",
                base + "token-cache-dir=" + System.getProperty("java.io.tmpdir")).run(context -> assertThat(context).hasNotFailed());
    }
}

package antlab.collect.fred;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.CollectJob;
import antlab.collect.CollectRunOnceRunner;
import antlab.collect.series.SeriesCatalog;
import antlab.support.IntegrationTest;
import antlab.support.PostgresTestcontainersConfig;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The real application: off by default, and on only when switched on (the context a one-shot run starts). */
class FredAppWiringTest {

    @Nested
    @IntegrationTest
    class Default {

        @Autowired
        ApplicationContext context;

        @Test
        void 기본에서는_잡도_러너도_클라이언트도_없다() {
            assertThat(context.getBeanNamesForType(FredJob.class)).isEmpty();
            assertThat(context.getBeanNamesForType(FredRunOnceRunner.class)).isEmpty();
            assertThat(context.getBeanNamesForType(FredClientFactory.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SeriesCatalog.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "antlab.collect.fred.enabled=true",
            "antlab.collect.fred.api-key=FAKE-FRED-KEY-0001"})
    @ActiveProfiles("test")
    @Import(PostgresTestcontainersConfig.class)
    class Enabled {

        @Autowired
        ApplicationContext context;

        @Test
        void 켜면_잡과_설정이_생기고_러너는_속성이_없으면_없으며_KIS_잡은_그대로_없다() {
            assertThat(context.getBeansOfType(FredJob.class)).hasSize(1).containsKey("fredSeriesJob");
            assertThat(context.getBean(FredJob.class)).isInstanceOf(FredSeriesJob.class);
            assertThat(context.getBean(FredProperties.class))
                    .isEqualTo(new FredProperties(true, "FAKE-FRED-KEY-0001", 1, 30, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2)));
            assertThat(context.getBean(SeriesCatalog.class).of(antlab.collect.series.SeriesSource.FRED)).hasSize(9);
            assertThat(context.getBeanNamesForType(FredRunOnceRunner.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CollectJob.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CollectRunOnceRunner.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "antlab.collect.fred.enabled=true",
            "antlab.collect.fred.api-key=FAKE-FRED-KEY-0001",
            "antlab.collect.run-once=FRED_SERIES",
            "antlab.collect.fred.series=NOPE_SERIES"})
    @ActiveProfiles("test")
    @Import({PostgresTestcontainersConfig.class, FredAppWiringTest.NoExit.class})
    class RunOnce {

        @Autowired
        ApplicationContext context;

        @Autowired
        org.springframework.jdbc.core.JdbcTemplate jdbc;

        @org.junit.jupiter.api.AfterEach
        void clean() {
            jdbc.update("DELETE FROM job_run");
        }

        @Test
        void 러너는_시작_때_한_번_돌고_알_수_없는_코드는_종료_코드_1로_끝난다() {
            assertThat(context.getBeanNamesForType(FredRunOnceRunner.class)).hasSize(1);
            assertThat(NoExit.codes).containsExactly(1);
        }
    }

    /** The runner exits through this bean instead of ending the test JVM. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class NoExit {

        static final java.util.List<Integer> codes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.annotation.Bean
        antlab.collect.ProcessExit processExit() {
            return codes::add;
        }
    }
}

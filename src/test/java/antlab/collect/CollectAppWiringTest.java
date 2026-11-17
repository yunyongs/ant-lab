package antlab.collect;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.price.KisDailyPriceJob;
import antlab.collect.price.PriceJobProperties;
import antlab.support.IntegrationTest;
import antlab.support.PostgresTestcontainersConfig;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The real application: off by default, and on only when switched on (the context a one-shot run starts). */
class CollectAppWiringTest {

    @Nested
    @IntegrationTest
    class Default {

        @Autowired
        ApplicationContext context;

        @Test
        void 기본에서는_잡도_러너도_없어서_시작_때_아무것도_돌지_않는다() {
            assertThat(context.getBeanNamesForType(CollectJob.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CollectRunOnceRunner.class)).isEmpty();
            assertThat(context.getBeanNamesForType(KisDailyPriceJob.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "antlab.collect.kis.enabled=true",
            "antlab.collect.kis.app-key=FAKE-APP-KEY-0001",
            "antlab.collect.kis.app-secret=FAKE-APP-SECRET-0001"})
    @ActiveProfiles("test")
    @Import(PostgresTestcontainersConfig.class)
    class Enabled {

        @Autowired
        ApplicationContext context;

        @Test
        void 켜면_잡이_생기고_설정이_묶이며_러너는_속성이_없으면_없다() {
            assertThat(context.getBeansOfType(CollectJob.class)).hasSize(1).containsKey("kisDailyPriceJob");
            assertThat(context.getBeansOfType(CollectJob.class).values().iterator().next()).isInstanceOf(KisDailyPriceJob.class);
            assertThat(context.getBean(PriceJobProperties.class)).isEqualTo(new PriceJobProperties(10, 30, Duration.ofHours(2)));
            assertThat(context.getBeanNamesForType(CollectRunOnceRunner.class)).isEmpty();
        }
    }
}

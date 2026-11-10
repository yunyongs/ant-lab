package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 시즌 설정: 값이 빠지거나 DB 제약 밖이면 기동에 실패한다(시즌 행 8개 설정 컬럼에는 기본값이 없다). */
class SeasonPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SeasonProperties.class)
    static class Config {
    }

    private static final List<String> VALID = List.of(
            "antlab.season.initial-cash=30000000", "antlab.season.fee-rate-kr=0.00015",
            "antlab.season.fee-rate-us=0.0025", "antlab.season.fx-spread-rate=0.0040",
            "antlab.season.data-snapshot-date=2026-11-07");

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private ApplicationContextRunner with(String... overrides) {
        List<String> values = new ArrayList<>(VALID);
        for (String o : overrides) {
            String key = o.substring(0, o.indexOf('='));
            values.removeIf(v -> v.startsWith(key + "="));
            if (!o.endsWith("=<빠짐>")) {
                values.add(o);
            }
        }
        return runner.withPropertyValues(values.toArray(String[]::new));
    }

    @Test
    void 올바른_값은_기동한다() {
        with().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SeasonProperties p = ctx.getBean(SeasonProperties.class);
            assertThat(p.initialCash()).isEqualByComparingTo(new BigDecimal("30000000"));
            assertThat(p.feeRateKr()).isEqualByComparingTo(new BigDecimal("0.00015"));
        });
    }

    @Test
    void 요율_0은_기동한다() {
        for (String key : List.of("fee-rate-kr", "fee-rate-us", "fx-spread-rate")) {
            for (String zero : List.of("0", "0.0")) {
                with("antlab.season." + key + "=" + zero).run(ctx -> assertThat(ctx).as(key + "=" + zero).hasNotFailed());
            }
        }
    }

    @Test
    void 키_하나를_빼면_기동에_실패한다() {
        for (String key : List.of("initial-cash", "fee-rate-kr", "fee-rate-us", "fx-spread-rate", "data-snapshot-date")) {
            with("antlab.season." + key + "=<빠짐>").run(ctx -> assertThat(ctx).as(key).hasFailed());
        }
    }

    @Test
    void 요율이_범위_밖이거나_소수_9자리면_기동에_실패한다() {
        for (String bad : List.of("1", "-0.1", "0.123456789", "1.5")) {
            with("antlab.season.fee-rate-kr=" + bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
        with("antlab.season.fx-spread-rate=0.123456789").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 초기_자금이_0이하이거나_소수이면_기동에_실패한다() {
        for (String bad : List.of("0", "-1", "100.5")) {
            with("antlab.season.initial-cash=" + bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }
}

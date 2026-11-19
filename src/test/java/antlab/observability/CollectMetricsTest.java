package antlab.observability;

import static antlab.support.MetricCounts.count;
import static antlab.support.MetricCounts.total;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.JobName;
import antlab.collect.JobStatus;
import antlab.observability.CollectMetrics.Source;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CollectMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final CollectMetrics metrics = new CollectMetrics(registry);

    @Test
    void 모든_작업과_끝난_상태_조합이_시작_때_0으로_있다() {
        for (JobName job : JobName.values()) {
            for (JobStatus status : new JobStatus[] {JobStatus.SUCCEEDED, JobStatus.PARTIAL, JobStatus.FAILED}) {
                assertThat(registry.find("antlab.collect.run").tags("job", job.name(), "status", status.name()).counter())
                        .as(job + "/" + status).isNotNull();
            }
        }
        assertThat(registry.find("antlab.collect.run").counters()).hasSize(JobName.values().length * 3);
        assertThat(total(registry, "antlab.collect.run")).isZero();
    }

    @Test
    void 모든_출처와_결과_조합이_시작_때_0으로_있다() {
        int expected = 0;
        for (Source source : Source.values()) {
            for (String outcome : CollectMetrics.outcomes(source)) {
                assertThat(registry.find("antlab.collect.request").tags("source", source.name(), "outcome", outcome).counter())
                        .as(source + "/" + outcome).isNotNull();
                expected++;
            }
        }
        assertThat(registry.find("antlab.collect.request").counters()).hasSize(expected);
        assertThat(total(registry, "antlab.collect.request")).isZero();
    }

    @Test
    void 실행은_한_번에_한_계열만_올린다() {
        metrics.run(JobName.FRED_SERIES, JobStatus.PARTIAL);

        assertThat(count(registry, "antlab.collect.run", "job", "FRED_SERIES", "status", "PARTIAL")).isEqualTo(1);
        assertThat(total(registry, "antlab.collect.run")).isEqualTo(1);
    }

    @Test
    void 끝나지_않은_RUNNING은_세지_않는다() {
        metrics.run(JobName.ECOS_SERIES, JobStatus.RUNNING);

        assertThat(total(registry, "antlab.collect.run")).isZero();
        assertThat(registry.find("antlab.collect.run").counters()).hasSize(JobName.values().length * 3);
    }

    @Test
    void 요청은_한_번에_한_계열만_올린다() {
        metrics.request(Source.KIS, CollectMetrics.OK);
        metrics.request(Source.KIS, "KIS_HTTP");

        assertThat(count(registry, "antlab.collect.request", "source", "KIS", "outcome", "OK")).isEqualTo(1);
        assertThat(count(registry, "antlab.collect.request", "source", "KIS", "outcome", "KIS_HTTP")).isEqualTo(1);
        assertThat(total(registry, "antlab.collect.request")).isEqualTo(2);
    }

    @Test
    void 목록_밖_코드와_다른_출처의_코드는_OTHER로_모이고_새_계열을_만들지_않는다() {
        int before = registry.find("antlab.collect.request").counters().size();

        metrics.request(Source.FRED, "SOMETHING_NEW");
        metrics.request(Source.FRED, "ECOS_AUTH");
        metrics.request(Source.ECOS, "https://api.example/x?api_key=SECRET-VALUE");
        metrics.request(Source.KIS, null);

        assertThat(count(registry, "antlab.collect.request", "source", "FRED", "outcome", "OTHER")).isEqualTo(2);
        assertThat(count(registry, "antlab.collect.request", "source", "ECOS", "outcome", "OTHER")).isEqualTo(1);
        assertThat(count(registry, "antlab.collect.request", "source", "KIS", "outcome", "OTHER")).isEqualTo(1);
        assertThat(registry.find("antlab.collect.request").counters()).hasSize(before);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).noneMatch(t -> t.getValue().contains("SECRET"));
        }
    }

    @Test
    void 결과_목록은_각_출처의_예외_코드와_재시도_표시를_모두_담는다() {
        assertThat(CollectMetrics.outcomes(Source.KIS)).contains("OK", "RATE_RETRY", "TOKEN_RETRY", "KIS_RATE_LIMIT", "KIS_HTTP",
                "KIS_REJECTED", "KIS_FORBIDDEN_REQUEST", "KIS_PARSE", "REQUEST_CAP", "OTHER");
        assertThat(CollectMetrics.outcomes(Source.FRED)).contains("OK", "RATE_RETRY", "FRED_RATE_LIMIT", "FRED_HTTP",
                "FRED_REJECTED", "FRED_FORBIDDEN_REQUEST", "FRED_PARSE", "REQUEST_CAP", "OTHER").doesNotContain("TOKEN_RETRY");
        assertThat(CollectMetrics.outcomes(Source.ECOS)).contains("OK", "RATE_RETRY", "ECOS_RATE_LIMIT", "ECOS_AUTH", "ECOS_HTTP",
                "ECOS_REJECTED", "ECOS_FORBIDDEN_REQUEST", "ECOS_PARSE", "REQUEST_CAP", "OTHER");
        assertThat(Arrays.stream(Source.values()).flatMap(s -> CollectMetrics.outcomes(s).stream()).distinct().count())
                .isLessThan(40);
    }

    @Test
    void none은_애플리케이션_레지스트리와_따로_센다() {
        CollectMetrics standalone = CollectMetrics.none();

        standalone.run(JobName.KIS_DAILY_PRICE, JobStatus.SUCCEEDED);
        standalone.request(Source.KIS, CollectMetrics.OK);

        assertThat(total(registry, "antlab.collect.run")).isZero();
        assertThat(total(registry, "antlab.collect.request")).isZero();
    }
}

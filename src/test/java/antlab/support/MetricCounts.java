package antlab.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Reads counters from a registry; a series that does not exist reads as 0. */
public final class MetricCounts {

    private MetricCounts() {}

    public static double count(MeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The sum over every series of the meter. */
    public static double total(MeterRegistry registry, String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }
}

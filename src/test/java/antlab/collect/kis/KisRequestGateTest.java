package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KisRequestGateTest {

    @Test
    void 여러_스레드가_동시에_와도_상한을_넘겨_보내지_않는다() throws Exception {
        int cap = 5;
        int threads = 24;
        KisRequestGate gate = new KisRequestGate(new MinIntervalRateLimiter(Clock.systemUTC(), d -> { }, 2), cap);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger capped = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        gate.intercept(null, new byte[0], (request, body) -> {
                            executed.incrementAndGet();
                            return null;
                        });
                    } catch (KisException e) {
                        assertThat(e.code()).isEqualTo("REQUEST_CAP");
                        capped.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(executed.get()).isEqualTo(cap);
        assertThat(capped.get()).isEqualTo(threads - cap);
        assertThat(gate.sent()).isEqualTo(cap);
    }
}

package antlab.support;

import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결정적 경합 시험의 공용 도구 — "쥔 트랜잭션 + 다른 스레드 요청" 패턴에서 요청이 <em>실제로 잠금을 기다리는지</em>
 * {@code pg_stat_activity.wait_event_type = 'Lock'} 으로 확인한 뒤에야 쥔 쪽을 풀어 준다(래치는 "동시에 시작"만 보장한다).
 * 시험은 한 JVM 에서 순차로 돌며(Gradle 기본) 현재 데이터베이스의 대기자 수를 센다.
 */
public final class LockWaitSupport {

    private static final long TIMEOUT_SECONDS = 10;

    private LockWaitSupport() {
    }

    /** 잠금을 기다리는 백엔드가 {@code count} 개 이상 될 때까지 기다린다. 못 되면 AssertionError. */
    public static void awaitWaiters(JdbcTemplate jdbc, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        long waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = waiters(jdbc);
            if (waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("잠금 대기자가 " + count + " 개가 되지 않았다(지금 " + waiting + ")");
    }

    public static long waiters(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Long.class);
    }
}

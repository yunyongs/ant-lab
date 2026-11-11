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

    /**
     * 기준선보다 대기자가 {@code count} 명 더 늘 때까지 기다린다. 못 되면 AssertionError.
     * 기준선({@link #waiters})은 기다릴 요청 스레드를 <em>시작하기 전에</em> 읽는다 — 앞선 시험이나 다른 연결의 대기자가
     * 섞여 있어도 이 시험의 요청이 실제로 기다리는지만 확인된다(절대 개수로 비교하면 빈말이 될 수 있다).
     */
    public static void awaitWaitersAbove(JdbcTemplate jdbc, long base, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        long waiting = base;
        while (System.nanoTime() < deadline) {
            waiting = waiters(jdbc);
            if (waiting >= base + count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("잠금 대기자가 기준선 " + base + " 에서 " + count + " 명 늘지 않았다(지금 " + waiting + ")");
    }

    public static long waiters(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Long.class);
    }
}

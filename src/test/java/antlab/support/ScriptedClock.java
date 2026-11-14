package antlab.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 시각 가드의 음성 사례를 만드는 제어형 시계 — 평소에는 흐르는 시스템 시계이고, 시험이 {@link #script} 로 맡긴 값만 앞에서부터
 * 한 번씩 돌려준다. 값을 다 쓰면 다시 흐르는 시계다(고정 시계가 아니다). 값 하나를 맡기면 그 값이 곧 가드가 확정하는 시각이다.
 * For a request through the security chain the first reader is bearer validation, then the event-time guard.
 */
public final class ScriptedClock extends Clock {

    private final Clock real = Clock.systemUTC();
    private final Deque<Instant> script = new ArrayDeque<>();

    public synchronized void script(Instant... values) {
        for (Instant v : values) {
            script.addLast(v);
        }
    }

    /** Scripted values not yet read. */
    public synchronized int remaining() {
        return script.size();
    }

    public synchronized void reset() {
        script.clear();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public synchronized Instant instant() {
        Instant next = script.pollFirst();
        return next != null ? next : real.instant();
    }
}

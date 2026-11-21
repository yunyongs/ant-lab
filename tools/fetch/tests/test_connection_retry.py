"""Connection-level failures (no HTTP response) are retried with backoff; every attempt is counted and journaled."""
from datetime import date

import pytest

import kis
from kis import KisClient
from tests.batch_helpers import Env, kr
from tests.fakes import FakeClock, Resp, cfg, day_rows_domestic, token_body, weekdays

A = lambda n=1, f="2019-01": kr("0000%02d" % n, n, f)  # noqa: E731


class ClosableSession:
    def __init__(self, handler):
        self.handler, self.calls, self.closed = handler, 0, 0

    def get(self, url, **kw):
        self.calls += 1
        return self.handler(self.calls, kw)

    def post(self, url, **kw):
        return Resp(token_body())

    def close(self):
        self.closed += 1


def make(tmp_path, handler, rps=1):
    clock, sess = FakeClock(), ClosableSession(handler)
    cl = KisClient(cfg(tmp_path, rps=rps), sess, tmp_path / "raw", clock)
    return cl, sess, clock


def reset_then(n_resets):
    days = weekdays(date(2024, 1, 1), 3)

    def h(n, kw):
        if n <= n_resets:
            raise ConnectionResetError(54, "Connection reset by peer")
        return Resp({"rt_cd": "0", "output2": day_rows_domestic(days)})
    return h, days


def test_reset_then_success_refreshes_session_and_backs_off(tmp_path):
    h, days = reset_then(1)
    cl, sess, clock = make(tmp_path, h)
    assert len(cl.domestic_page("005930", days[0], days[-1], True)) == 3
    assert sess.calls == 2 and sess.closed == 1
    assert 2 in clock.sleeps
    assert cl.requests_made == 3  # token + reset + success


def test_resets_exhaust_retries_then_connection_stop(tmp_path):
    h, days = reset_then(99)
    cl, sess, clock = make(tmp_path, h)
    with pytest.raises(kis.KisError) as e:
        cl.domestic_page("005930", days[0], days[-1], True)
    assert e.value.reason == "CONNECTION"
    assert sess.calls == 1 + len(kis.CONNECTION_RETRY_WAITS)
    assert [s for s in clock.sleeps if s in kis.CONNECTION_RETRY_WAITS] == list(kis.CONNECTION_RETRY_WAITS)


def test_every_attempt_counts_toward_cap(tmp_path):
    h, days = reset_then(99)
    cl, sess, _ = make(tmp_path, h)
    cl.max_requests = 3  # token + 2 attempts
    with pytest.raises(kis.BudgetExhausted):
        cl.domestic_page("005930", days[0], days[-1], True)
    assert cl.requests_made == 3 and sess.calls == 2


def test_pacing_preserved_between_attempts(tmp_path):
    h, days = reset_then(2)
    cl, sess, clock = make(tmp_path, h, rps=0.1)  # gap 10 s exceeds every backoff, so only the throttle can space attempts
    stamps = []
    cl.on_send = lambda: stamps.append(clock.now())
    cl.domestic_page("005930", days[0], days[-1], True)
    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    assert all(g >= 10.0 - 1e-9 for g in gaps)


def test_token_post_is_not_retried(tmp_path):
    clock, calls = FakeClock(), []

    class S(ClosableSession):
        def post(self, url, **kw):
            calls.append(1)
            raise ConnectionResetError(54, "reset")
    cl = KisClient(cfg(tmp_path, rps=1), S(lambda n, kw: None), tmp_path / "raw", clock)
    with pytest.raises(kis.KisError) as e:
        cl.token()
    assert e.value.reason == "CONNECTION" and len(calls) == 1


def test_batch_reset_then_success_each_attempt_in_ledger(tmp_path):
    env = Env(tmp_path)
    env.fake.fail_at = {2: "reset"}
    r = env.run("drip", [A(1)])
    assert r.stop_reason == "DONE"
    assert r.requests == env.fake.tokens + env.fake.gets  # the failed attempt is a counted request
    assert int(env.ledger()[-1]["requests"]) == r.requests


def test_batch_resets_stop_connection(tmp_path):
    env = Env(tmp_path)
    env.fake.fail_at = {2: "reset", 3: "reset", 4: "reset", 5: "reset"}
    r = env.run("drip", [A(1)])
    assert r.stop_reason == "CONNECTION"
    assert r.requests == env.fake.tokens + env.fake.gets
    assert env.ledger()[-1]["stop_reason"] == "CONNECTION" and int(env.ledger()[-1]["requests"]) == r.requests

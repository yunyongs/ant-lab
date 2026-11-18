import csv
import json
import os
from datetime import date

import pytest

import batch
import kis
from batch import BatchRefused, Ledger, estimate_requests
from kis import BudgetExhausted, KisClient, KisError
from tests.batch_helpers import Env, kr, tree_hashes, us
from tests.fake_kis import clock_at, kst_epoch
from tests.fakes import FakeClock, FakeSession, Resp, cfg, token_body

A = lambda n=1, f="2019-01": kr("0000%02d" % n, n, f)  # noqa: E731  ~19 daily pages each


def test_drip_happy_path_ledger_and_marker(tmp_path):
    env = Env(tmp_path)
    r = env.run("drip", [A(1)])
    assert (r.stop_reason, r.assets_done, r.assets_left) == ("DONE", 1, 0)
    (row,) = env.ledger()
    assert row["day_kst"] == "2026-11-19" and row["source"] == "KIS" and row["batch_no"] == "1"
    assert int(row["requests"]) == r.requests == env.fake.tokens + env.fake.gets  # token and pages are all counted
    assert row["first_target"] == row["last_target"] == "KR:000001"
    assert batch.asset_state(env.raw, "drip", A(1)) == "DONE"


def test_second_run_on_a_later_day_sends_zero_requests(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1), A(2)])
    before = tree_hashes(env.raw / "kis")
    n = env.fake.tokens + env.fake.gets
    env.next_day()
    r = env.run("drip", [A(1), A(2)])
    assert r.requests == 0 and r.assets_done == 0 and env.fake.tokens + env.fake.gets == n
    assert tree_hashes(env.raw / "kis") == before
    assert [x["requests"] for x in env.ledger()][-1] == "0"


def test_cap_refusal_reads_the_ledger_before_sending(tmp_path):
    env = Env(tmp_path)
    Ledger(env.raw / "ledger.csv").append({"day_kst": "2026-11-19", "source": "KIS", "batch_no": 1, "requests": 600,
                                          "stop_reason": "BUDGET", "first_target": "", "last_target": ""})
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)], same_day=True)
    assert e.value.reason == "CAP_REACHED" and env.session.calls == [] and len(env.ledger()) == 1


def test_cap_counts_what_the_ledger_already_holds(tmp_path):
    env = Env(tmp_path)
    Ledger(env.raw / "ledger.csv").append({"day_kst": "2026-11-19", "source": "KIS", "batch_no": 1, "requests": 590,
                                          "stop_reason": "BUDGET", "first_target": "", "last_target": ""})
    r = env.run("drip", [A(1)], same_day=True)           # 10 left: the asset needs more than that
    assert r.stop_reason == "BUDGET" and r.requests == 0 and env.session.calls == []


def test_other_days_and_sources_do_not_count(tmp_path):
    env = Env(tmp_path)
    for day, src in (("2026-11-18", "KIS"), ("2026-11-19", "YF")):
        Ledger(env.raw / "ledger.csv").append({"day_kst": day, "source": src, "batch_no": 1, "requests": 600,
                                              "stop_reason": "BUDGET", "first_target": "", "last_target": ""})
    assert env.run("drip", [A(1)]).stop_reason == "DONE"


@pytest.mark.parametrize("cap", [0, -1, 1501, 10_000])
def test_cap_flag_cannot_exceed_the_hard_maximum(tmp_path, cap):
    env = Env(tmp_path)
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)], cap=cap)
    assert e.value.reason == "CAP_FLAG" and env.session.calls == []
    assert batch.HARD_MAX_CAP == {"KIS": 1500, "YF": 50} and batch.DEFAULT_CAP["KIS"] == 600


def test_cap_1500_is_accepted(tmp_path):
    assert Env(tmp_path).run("drip", [A(1)], cap=1500).stop_reason == "DONE"


def test_whole_asset_never_started_when_it_does_not_fit(tmp_path):
    env = Env(tmp_path)
    one, _ = estimate_requests(env.raw, "drip", A(1), "2026-11-19")
    r = env.run("drip", [A(1), A(2)], cap=one + 1 + 5)   # token + first asset + a little: the second does not fit
    assert (r.stop_reason, r.stopped_on, r.assets_done) == ("BUDGET", "KR:000002", 1)
    assert not list((env.raw / "kis").glob("kis_kr_000002_*"))        # 0 pages of the second asset
    assert batch.asset_state(env.raw, "drip", A(2)) == "PENDING"


def test_budget_stops_before_the_next_asset_even_if_a_later_one_is_smaller(tmp_path):
    env = Env(tmp_path)
    big, small = A(1, "1995-12"), A(2, "2025-06")
    need, _ = estimate_requests(env.raw, "drip", small, "2026-11-19")
    r = env.run("drip", [big, small], cap=need + 3)
    assert r.stop_reason == "BUDGET" and r.stopped_on == "KR:000001" and r.assets_done == 0


def test_client_hard_stops_before_sending_request_n_plus_1(tmp_path):
    sess = FakeSession(lambda m, u, k: Resp(token_body()) if m == "post" else Resp({"rt_cd": "0", "output2": []}))
    cl = KisClient(cfg(tmp_path, 1), sess, tmp_path / "raw", FakeClock())
    cl.max_requests = 3
    cl.daily_range("KR", "005930", date(2024, 1, 1), date(2024, 1, 31), adjusted=True)      # token + 1 page = 2
    with pytest.raises(BudgetExhausted):
        cl.daily_range("KR", "000660", date(2024, 1, 1), date(2024, 1, 31), adjusted=True)  # token cached; 3rd ok
        cl.daily_range("KR", "000270", date(2024, 1, 1), date(2024, 1, 31), adjusted=True)  # the 4th is refused
    assert len(sess.calls) == 3 and cl.requests_made == 3


def test_underestimate_mid_asset_stops_on_budget_and_leaves_partial(tmp_path, monkeypatch):
    env = Env(tmp_path)
    monkeypatch.setattr(batch, "estimate_requests", lambda *a, **k: (1, False))
    r = env.run("drip", [A(1)], cap=6)
    assert r.stop_reason == "BUDGET" and r.requests == 6 and len(env.session.calls) == 6
    assert batch.asset_state(env.raw, "drip", A(1)) == "PARTIAL"


def test_requests_made_counts_token_and_retries(tmp_path):
    env = Env(tmp_path)
    env.fake.fail_at = {2: "rate"}                       # one EGW00201 then the retry succeeds
    r = env.run("drip", [A(1)])
    assert r.stop_reason == "DONE" and r.requests == env.fake.tokens + env.fake.gets
    assert env.fake.gets > env.fake.gets - 1 >= 1 and r.requests >= 3


# -- resume -------------------------------------------------------------------------------------
def test_interrupted_run_plus_rerun_equals_one_uninterrupted_run(tmp_path):
    a = Env(tmp_path / "a")
    a.run("drip", [A(1)])
    b = Env(tmp_path / "b")
    b.fake.fail_at = {5: "rate", 6: "rate", 7: "rate", 8: "rate"}
    r1 = b.run("drip", [A(1)])
    assert r1.stop_reason == "RATE_LIMIT" and batch.asset_state(b.raw, "drip", A(1)) == "PARTIAL"
    b.fake.fail_at = {}
    r2 = b.run("drip", [A(1)], same_day=True)
    assert r2.stop_reason == "DONE"
    assert tree_hashes(b.raw / "kis") == tree_hashes(a.raw / "kis")
    assert r1.requests + r2.requests - 1 <= a.fake.gets + a.fake.tokens + 4    # no page is fetched twice (one token at most extra)


def test_rerun_reads_cached_pages_with_zero_requests_when_marker_is_lost(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    (env.raw / "done" / "drip_KR_000001.json").write_text("not json", encoding="utf-8")
    n = len(env.session.calls)
    r = env.run("drip", [A(1)], same_day=True)
    assert r.requests == 0 and len(env.session.calls) == n and batch.asset_state(env.raw, "drip", A(1)) == "DONE"


def test_truncated_page_is_refetched_not_parsed(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    page = sorted((env.raw / "kis").glob("kis_kr_000001_D_*"))[3]
    good = page.read_text(encoding="utf-8")
    page.write_text(good[: len(good) // 2], encoding="utf-8")
    (env.raw / "done" / "drip_KR_000001.json").write_text("{", encoding="utf-8")
    r = env.run("drip", [A(1)], same_day=True)
    assert r.requests >= 1 and r.stop_reason == "DONE" and page.read_text(encoding="utf-8") == good


def test_pages_from_an_earlier_day_are_refetched_whole(tmp_path):
    env = Env(tmp_path)
    env.fake.fail_at = {5: "rate", 6: "rate", 7: "rate", 8: "rate"}
    env.run("drip", [A(1)])
    pages = len(list((env.raw / "kis").glob("kis_kr_000001_D_*")))
    env.fake.fail_at = {}
    env.next_day()
    r = env.run("drip", [A(1)])
    assert r.stop_reason == "DONE" and r.requests >= pages                 # not a continuation: the whole asset again
    assert len(list((env.raw / "stale" / "2026-11-20").glob("kis_kr_000001_D_*"))) == pages
    days = {json.loads(p.read_text(encoding="utf-8"))["fetched_day"] for p in (env.raw / "kis").glob("kis_kr_000001_D_*")}
    assert days == {"2026-11-20"}                                           # one fetch day for the whole series


# -- stop rules ---------------------------------------------------------------------------------
def test_rate_limit_waits_1_2_4_then_the_batch_ends(tmp_path):
    env = Env(tmp_path)
    env.fake.fail_at = {3: "rate", 4: "rate", 5: "rate", 6: "rate"}
    r = env.run("drip", [A(1), A(2)])
    assert r.stop_reason == "RATE_LIMIT" and r.stopped_on == "KR:000001"
    assert [s for s in env.clock.sleeps if s in (1, 2, 4)].count(4) == 1 and {1, 2, 4} <= set(env.clock.sleeps)
    assert not list((env.raw / "kis").glob("kis_kr_000002_*"))
    assert env.ledger()[-1]["stop_reason"] == "RATE_LIMIT"


@pytest.mark.parametrize("kind,reason", [("500", "HTTP"), ("rt", "RT_CD"), ("redirect", "REDIRECT"), ("nonjson", "NON_JSON")])
def test_any_bad_answer_ends_the_batch_and_caches_nothing_for_that_page(tmp_path, kind, reason):
    env = Env(tmp_path)
    env.fake.fail_at = {3: kind}
    r = env.run("drip", [A(1), A(2)])
    assert r.stop_reason == reason and r.assets_done == 0 and r.stopped_on == "KR:000001"
    assert len(list((env.raw / "kis").glob("kis_kr_000001_D_*"))) == 2       # only the two pages before the bad answer
    assert env.fake.gets == 3                                                # nothing sent after it
    assert env.ledger()[-1]["stop_reason"] == reason


def test_token_failure_is_not_retried_in_a_batch(tmp_path):
    env = Env(tmp_path)
    env.session.handler = lambda m, u, k: Resp({"error_code": "X"}, 403)
    r = env.run("drip", [A(1)])
    assert r.stop_reason == "TOKEN" and len(env.session.calls) == 1 and 65 not in env.clock.sleeps


def test_only_one_token_request_per_kst_day(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    assert env.fake.tokens == 1
    tok = env.cfg.token_cache_dir / "token_real.json"
    tok.write_text(json.dumps({"access_token": "OLD", "expires_at": env.clock.t + 60}), encoding="utf-8")   # < 1 h left
    r = env.run("drip", [A(2)], same_day=True)
    assert r.stop_reason == "TOKEN" and env.fake.tokens == 1
    env.next_day()
    assert env.run("drip", [A(2)]).stop_reason == "DONE" and env.fake.tokens == 2


def test_cached_token_is_reused_by_a_second_batch_the_same_day(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    env.run("drip", [A(2)], same_day=True)
    assert env.fake.tokens == 1


def test_kis_rate_is_one_request_per_second(tmp_path):
    env = Env(tmp_path)
    times = []
    inner = env.fake.handler
    env.session.handler = lambda m, u, k: (times.append(env.clock.t), inner(m, u, k))[1]
    env.run("drip", [A(1)])
    gaps = [b - a for a, b in zip(times, times[1:])]
    assert len(times) > 5 and min(gaps) >= 1.0 - 1e-9


def test_data_check_failure_marks_the_asset_failed_and_the_batch_goes_on(tmp_path):
    env = Env(tmp_path, listings={"000001": date(2030, 1, 1)})        # no rows at all
    r = env.run("drip", [A(1), A(2)])
    assert r.stop_reason == "DONE_WITH_FAILED" and r.failed == ["KR:000001:DATA_CHECK"] and r.assets_done == 1
    assert batch.asset_state(env.raw, "drip", A(1)) == "FAILED" and batch.asset_state(env.raw, "drip", A(2)) == "DONE"
    assert env.ledger()[-1]["stop_reason"] == "DONE_WITH_FAILED"
    n = len(env.session.calls)
    env.next_day()
    r2 = env.run("drip", [A(1), A(2)])                                 # not tried again every night
    assert r2.requests == 0 and len(env.session.calls) == n and not (env.raw / "stale").exists()
    assert "KR:000001" not in r2.stopped_on


def test_failed_assets_do_not_block_later_ones_and_clear_when_the_marker_is_deleted(tmp_path):
    env = Env(tmp_path, listings={"000001": date(2030, 1, 1)})
    env.run("drip", [A(1)])
    (env.raw / "failed" / "drip_KR_000001.json").unlink()
    assert batch.asset_state(env.raw, "drip", A(1)) != "FAILED"


# -- one batch a day, window, worktree, lock -----------------------------------------------------
def test_second_batch_on_the_same_day_is_refused_unless_typed(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(2)])
    assert e.value.reason == "SAME_DAY"
    r = env.run("drip", [A(2)], same_day=True)
    assert r.batch_no == 2 and r.stop_reason == "DONE"


def test_same_day_override_still_respects_the_cap(tmp_path):
    env = Env(tmp_path)
    one, _ = estimate_requests(env.raw, "drip", A(1), "2026-11-19")
    r1 = env.run("drip", [A(1), A(2)], cap=one + 1 + 3)
    used = r1.requests
    r2 = env.run("drip", [A(2)], cap=one + 1 + 3, same_day=True)
    assert r2.requests + used <= one + 1 + 3 and r2.stop_reason == "BUDGET"


def test_zero_request_batch_does_not_use_up_the_day(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    env.next_day()
    env.run("drip", [A(1)])                                            # 0 requests
    assert env.run("drip", [A(2)]).stop_reason == "DONE"               # allowed: the earlier batch the same day sent nothing


@pytest.mark.parametrize("hh,mm,ok", [(20, 59, False), (21, 0, True), (22, 59, True), (23, 0, False), (8, 30, False)])
def test_run_window_is_21_to_23_kst(tmp_path, hh, mm, ok):
    env = Env(tmp_path, clock=clock_at(hh=hh, mm=mm))
    if ok:
        assert env.run("drip", [A(1)]).stop_reason in ("DONE", "WINDOW_END")
    else:
        with pytest.raises(BatchRefused) as e:
            env.run("drip", [A(1)])
        assert e.value.reason == "WINDOW" and env.session.calls == []


def test_window_override_is_explicit(tmp_path):
    env = Env(tmp_path, clock=clock_at(hh=14))
    assert env.run("drip", [A(1)], outside_window=True).stop_reason == "DONE"


def test_asset_that_cannot_finish_before_23_is_not_started(tmp_path):
    env = Env(tmp_path, clock=clock_at(hh=22, mm=59))
    env.clock.t += 30                                                  # 30 s left, the asset needs about 22 requests
    r = env.run("drip", [A(1), A(2)])
    assert r.stop_reason == "WINDOW_END" and r.requests == 0 and r.assets_done == 0
    assert env.session.calls == [] and not list((env.raw / "kis").glob("*.json"))


def test_batch_stops_between_assets_when_the_window_closes(tmp_path):
    env = Env(tmp_path, clock=clock_at(hh=22, mm=59))
    r = env.run("drip", [A(1, "2025-06"), A(2), A(3)])
    assert (r.stop_reason, r.assets_done, r.assets_left) == ("WINDOW_END", 2, 1)
    assert not list((env.raw / "kis").glob("kis_kr_000003_*"))


def test_no_request_leaves_after_23_even_inside_an_asset(tmp_path, monkeypatch):
    env = Env(tmp_path, clock=clock_at(hh=22, mm=59))
    env.clock.t += 50
    monkeypatch.setattr(batch, "estimate_requests", lambda *a, **k: (1, False))   # the estimate was too low
    monkeypatch.setattr(batch, "WINDOW_MARGIN_SECONDS", 0)
    r = env.run("drip", [A(1)])
    assert r.stop_reason == "WINDOW_END" and r.stopped_on == "KR:000001"
    assert batch.kst_now(env.clock).strftime("%H:%M") in ("23:00", "23:01") and env.fake.gets < 14
    assert batch.asset_state(env.raw, "drip", A(1)) == "PARTIAL"
    monkeypatch.undo()
    env.next_day()                                                     # next night: the half series is not used, fetched whole
    r2 = env.run("drip", [A(1)])
    assert r2.stop_reason == "DONE" and list((env.raw / "stale" / "2026-11-20").glob("kis_kr_000001_D_*"))


def test_outside_window_override_still_stops_at_midnight(tmp_path, monkeypatch):
    env = Env(tmp_path, clock=clock_at(hh=23, mm=59))
    env.clock.t += 50
    monkeypatch.setattr(batch, "estimate_requests", lambda *a, **k: (1, False))
    r = env.run("drip", [A(1)], outside_window=True)
    assert r.stop_reason == "WINDOW_END" and batch.kst_now(env.clock).strftime("%d") == "20"


def test_estimate_pins_and_token_reserve(tmp_path):
    assert (batch.pages_for(0), batch.pages_for(100), batch.pages_for(101)) == (1, 2, 3)
    env = Env(tmp_path)
    need, _ = estimate_requests(env.raw, "drip", A(1), "2026-11-19")
    r = env.run("drip", [A(1)], cap=need)                              # the token request does not fit on top
    assert r.stop_reason == "BUDGET" and r.requests == 0 and env.session.calls == []


def test_bad_answer_shapes_and_exit_classification(tmp_path):
    env = Env(tmp_path)
    env.session.handler = lambda m, u, k: Resp(token_body()) if m == "post" else Resp([1, 2])
    assert env.run("drip", [A(1)]).stop_reason == "NON_JSON"
    for reason in ("RATE_LIMIT", "HTTP", "REDIRECT", "NON_JSON", "RT_CD", "TOKEN", "BUDGET_X", "YF_ERROR", "CRASH", "SIGTERM"):
        assert reason not in batch.CLEAN_STOPS
    assert set(batch.CLEAN_STOPS) == {"DONE", "DONE_WITH_FAILED", "BUDGET", "WINDOW_END"}


@pytest.mark.parametrize("status,body", [(500, {"msg1": "x"}), (403, {"msg1": "x"}), (404, {})])
def test_non_200_without_rt_cd_stops_the_batch(tmp_path, status, body):
    env = Env(tmp_path)
    inner = env.fake.handler
    env.session.handler = lambda m, u, k: Resp(body, status) if m == "get" and env.fake.gets >= 1 else inner(m, u, k)
    env.fake.gets = 1
    r = env.run("drip", [A(1), A(2)])
    assert r.stop_reason == "HTTP" and r.assets_done == 0 and not list((env.raw / "kis").glob("kis_kr_*"))
    assert len(env.session.calls) == 2                                 # token + the bad page, nothing after it


def test_refuses_to_send_from_a_git_worktree(tmp_path):
    env = Env(tmp_path)
    (env.repo / ".git").write_text("gitdir: /elsewhere/.git/worktrees/x\n", encoding="utf-8")
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)])
    assert e.value.reason == "WORKTREE" and env.session.calls == [] and not (env.raw / "ledger.csv").exists()
    assert batch.is_git_worktree(env.repo)


def test_main_checkout_with_git_directory_is_allowed(tmp_path):
    env = Env(tmp_path)
    (env.repo / ".git").mkdir()
    assert not batch.is_git_worktree(env.repo)
    assert env.run("drip", [A(1)]).stop_reason == "DONE"


def test_raw_dir_must_be_inside_data(tmp_path):
    env = Env(tmp_path)
    env.raw = tmp_path / "elsewhere"
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)])
    assert e.value.reason == "RAW_DIR"


def test_lock_blocks_a_second_batch_and_is_released(tmp_path):
    env = Env(tmp_path)
    env.raw.mkdir(parents=True)
    (env.raw / "batch.lock").write_text("", encoding="utf-8")
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)])
    assert e.value.reason == "LOCK" and env.session.calls == []
    (env.raw / "batch.lock").unlink()
    env.run("drip", [A(1)])
    assert not (env.raw / "batch.lock").exists()


def test_crash_writes_a_ledger_row_and_releases_the_lock(tmp_path, monkeypatch):
    env = Env(tmp_path)

    def boom(*a, **k):
        raise RuntimeError("bug")
    monkeypatch.setattr(batch, "_check_asset", boom)
    with pytest.raises(RuntimeError):
        env.run("drip", [A(1)])
    assert env.ledger()[-1]["stop_reason"] == "CRASH" and not (env.raw / "batch.lock").exists()


# -- atomic write --------------------------------------------------------------------------------
def test_page_is_written_to_a_temp_name_then_renamed(tmp_path, monkeypatch):
    env = Env(tmp_path)
    seen = []
    real = os.replace
    monkeypatch.setattr(kis.os, "replace", lambda a, b: (seen.append((os.path.basename(a), os.path.basename(b))), real(a, b))[1])
    env.run("drip", [A(1, "2025-06")])
    pages = [s for s in seen if s[1].startswith("kis_kr_")]
    assert pages and all(a == b + ".tmp" for a, b in pages)
    assert not list((env.raw / "kis").glob("*.tmp"))


def test_crash_during_write_never_leaves_a_final_file(tmp_path, monkeypatch):
    env = Env(tmp_path)

    def die(a, b):
        raise OSError("disk")
    monkeypatch.setattr(kis.os, "replace", die)
    with pytest.raises(OSError):
        env.run("drip", [A(1, "2025-06")])
    assert not list((env.raw / "kis").glob("*.json"))


def test_unparsable_page_counts_as_missing_in_status(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1, "2025-06")])
    (env.raw / "done" / "drip_KR_000001.json").write_text("x", encoding="utf-8")
    for p in (env.raw / "kis").glob("kis_kr_000001_D_*"):
        p.write_text('{"request": {}', encoding="utf-8")
    assert batch.asset_state(env.raw, "drip", A(1, "2025-06")) == "PENDING"


# -- sweep, status -------------------------------------------------------------------------------
def test_sweep_writes_first_month_and_2006_12_flag(tmp_path):
    env = Env(tmp_path, listings={"000002": date(2010, 3, 15), "SPY": date(2007, 8, 20)})
    ts = [kr("000001", 1, ""), kr("000002", 2, ""), us("SPY", 3, "", "")]
    r = env.run("sweep", ts)
    assert r.stop_reason == "DONE" and r.assets_done == 3
    rows = list(csv.DictReader((env.raw / "sweep" / "sweep_result.csv").open(encoding="utf-8")))
    got = {x["ticker"]: x for x in rows}
    assert (got["000001"]["kis_first_month"], got["000001"]["has_2006_12"]) == ("1995-12", "Y")
    assert (got["000002"]["kis_first_month"], got["000002"]["has_2006_12"]) == ("2010-03", "N")
    assert (got["SPY"]["kis_first_month"], got["SPY"]["kis_last_month"]) == ("2007-08", "2025-12")
    assert set(rows[0]) == set(batch.SWEEP_HEADER)
    base = [q for q in env.fake.requested if q.get("FID_INPUT_DATE_1") == "20260102" or q.get("BYMD") == "20260102"]
    assert len(base) == 3                                              # the base-date close of every candidate


def test_sweep_skips_the_base_close_when_there_are_no_bars_and_second_run_is_free(tmp_path):
    env = Env(tmp_path, listings={"000001": date(2030, 1, 1)})
    env.run("sweep", [kr("000001", 1, ""), kr("000002", 2, "")])
    n = len(env.session.calls)
    env.next_day()
    assert env.run("sweep", [kr("000001", 1, ""), kr("000002", 2, "")]).requests == 0 and len(env.session.calls) == n


def test_sweep_pages_do_not_count_against_the_drip_estimate(tmp_path):
    env = Env(tmp_path)
    ft = kr("000001", 1, "2019-01")
    before, _ = estimate_requests(env.raw, "drip", ft, "2026-11-19")
    env.run("sweep", [kr("000001", 1, "")])
    after, _ = estimate_requests(env.raw, "drip", ft, "2026-11-19")
    assert after == before - 1                                          # only the already cached base-date close is saved


def test_status_counts_each_state(tmp_path):
    env = Env(tmp_path)
    ts = [A(1), A(2), A(3), kr("069500", 4, "")]
    env.fake.fail_at = {3: "500"}
    env.run("drip", ts[:1])                                             # 1 stops partway -> PARTIAL
    env.fake.fail_at = {}
    env.run("drip", [A(2)], same_day=True)                              # 2 done
    st = batch.batch_status(ts, env.raw, "2026-11-19")
    assert st["drip"] == {"PARTIAL": 1, "DONE": 1, "PENDING": 1, "NO_KIS": 1}
    assert st["requests_today"]["KIS"] == sum(int(r["requests"]) for r in env.ledger())


# -- audit C1/C4: durable counting, shared state ---------------------------------------------------
import subprocess
import sys
from pathlib import Path

FETCH = Path(__file__).resolve().parents[1]


def _driver(tmp_path, mode):
    env = Env(tmp_path)
    p = subprocess.run([sys.executable, "-B", str(FETCH / "tests" / "kill_driver.py"), str(env.repo), mode],
                       cwd=FETCH, capture_output=True, text=True, timeout=120)
    return env, p


def test_killed_batch_still_counts_its_requests(tmp_path):
    env, p = _driver(tmp_path, "exit")                                 # os._exit: no finally, no ledger row
    assert p.returncode == 9 and (env.raw / "batch.lock").exists() and not (env.raw / "ledger.csv").exists()
    with pytest.raises(BatchRefused) as e:                             # the rerun the same night books the journal first
        env.run("drip", [A(1)])
    assert e.value.reason == "SAME_DAY"
    (row,) = env.ledger()
    assert (row["stop_reason"], row["requests"], row["day_kst"]) == ("CRASH", "6", "2026-11-19")
    assert not (env.raw / "batch.lock").exists()
    r = env.run("drip", [A(1)], same_day=True, cap=8)                  # cap 8 - 6 already sent = 2 left
    assert r.stop_reason == "BUDGET" and r.requests == 0


def test_sigterm_ends_the_batch_with_a_ledger_row(tmp_path):
    env, p = _driver(tmp_path, "term")
    assert p.returncode != 0
    (row,) = env.ledger()
    assert (row["stop_reason"], row["requests"]) == ("SIGTERM", "6") and not (env.raw / "batch.lock").exists()


def test_lock_of_a_live_process_is_not_stolen(tmp_path):
    env = Env(tmp_path)
    env.raw.mkdir(parents=True)
    (env.raw / "batch.lock").write_text(f"2026-11-19,KIS,{os.getpid()}\n1\n", encoding="utf-8")
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)])
    assert e.value.reason == "LOCK" and not (env.raw / "ledger.csv").exists()


def test_every_request_is_journaled_before_it_is_sent(tmp_path):
    env = Env(tmp_path)
    seen = []
    inner = env.fake.handler
    env.session.handler = lambda m, u, k: (seen.append(len((env.raw / "batch.lock").read_text().splitlines()) - 1), inner(m, u, k))[1]
    env.run("drip", [A(1, "2025-06")])
    assert seen == list(range(1, len(seen) + 1))                       # journal lines == requests sent so far, at send time


def test_a_second_raw_dir_shares_ledger_cap_and_token_log(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    env.raw = env.repo / "data" / "raw" / "mvp-04b"
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(2)])
    assert e.value.reason == "SAME_DAY"
    r = env.run("drip", [A(2)], same_day=True)
    assert r.stop_reason == "DONE" and env.fake.tokens == 1            # token log shared: one tokenP that day
    ledger = Ledger(env.repo / "data" / "raw" / "mvp-04" / "ledger.csv").rows()
    assert [x["batch_no"] for x in ledger] == ["1", "2"] and not (env.raw / "ledger.csv").exists()


@pytest.mark.parametrize("sub", ["p0-08", "p0-08/kis"])
def test_p0_08_cache_is_never_a_batch_raw_dir(tmp_path, sub):
    env = Env(tmp_path)
    env.raw = env.repo / "data" / "raw" / sub
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)])
    assert e.value.reason == "RAW_DIR" and env.session.calls == [] and not (env.raw / "stale").exists()


def test_token_rule_holds_across_raw_dirs(tmp_path):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    (env.cfg.token_cache_dir / "token_real.json").write_text(json.dumps({"access_token": "OLD", "expires_at": env.clock.t + 60}), encoding="utf-8")
    env.raw = env.repo / "data" / "raw" / "mvp-04b"
    r = env.run("drip", [A(2)], same_day=True)
    assert r.stop_reason == "TOKEN" and env.fake.tokens == 1


# -- audit round 2 ------------------------------------------------------------------------------------
import threading


def _fail_first_append(monkeypatch, exc):
    real = Ledger.append
    calls = {"n": 0}

    def flaky(self, row):
        calls["n"] += 1
        if calls["n"] == 1:
            raise exc
        return real(self, row)
    monkeypatch.setattr(Ledger, "append", flaky)


@pytest.mark.parametrize("exc", [OSError("disk full"), batch.Terminated("SIGTERM")])
def test_lock_is_kept_when_the_ledger_row_could_not_be_written(tmp_path, monkeypatch, exc):
    env = Env(tmp_path)
    _fail_first_append(monkeypatch, exc)
    with pytest.raises(type(exc)):
        env.run("drip", [A(1)])
    assert (env.raw / "batch.lock").exists() and not (env.raw / "ledger.csv").exists()
    monkeypatch.undo()
    lock = env.raw / "batch.lock"
    day, src, _ = lock.read_text(encoding="utf-8").splitlines()[0].split(",")
    lock.write_text(lock.read_text(encoding="utf-8").replace(f",{os.getpid()}\n", f",{_dead_pid()}\n", 1), encoding="utf-8")
    with pytest.raises(BatchRefused) as e:                              # the same night: recovery books the requests first
        env.run("drip", [A(1)])
    assert e.value.reason == "SAME_DAY"
    (row,) = env.ledger()
    assert row["stop_reason"] == "CRASH" and int(row["requests"]) == env.fake.tokens + env.fake.gets > 0


def test_lock_is_removed_when_nothing_was_journaled(tmp_path, monkeypatch):
    env = Env(tmp_path)
    env.run("drip", [A(1)])
    env.next_day()
    _fail_first_append(monkeypatch, OSError("disk full"))
    with pytest.raises(OSError):
        env.run("drip", [A(1)])                                         # 0 requests: nothing to lose
    assert not (env.raw / "batch.lock").exists()


def _dead_pid():
    p = subprocess.Popen([sys.executable, "-c", "pass"])
    p.wait()
    return p.pid


def test_two_concurrent_recoveries_book_one_crash_row(tmp_path, monkeypatch):
    env = Env(tmp_path)
    env.raw.mkdir(parents=True)
    (env.raw / "batch.lock").write_text(f"2026-11-19,KIS,{_dead_pid()}\n1\n1\n1\n", encoding="utf-8")
    barrier = threading.Barrier(2, timeout=10)
    monkeypatch.setattr(batch, "_pid_alive", lambda pid: (barrier.wait(), False)[1])
    ledger = Ledger(env.raw / "ledger.csv")
    errors = []

    def go():
        try:
            batch._recover_dead_batch(env.raw / "batch.lock", ledger)
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    ts = [threading.Thread(target=go) for _ in range(2)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert errors == [] and [(r["stop_reason"], r["requests"]) for r in ledger.rows()] == [("CRASH", "3")]
    assert not list(env.raw.glob("batch.lock*"))


def test_a_claim_left_by_a_dead_claimer_is_booked(tmp_path):
    env = Env(tmp_path)
    env.raw.mkdir(parents=True)
    (env.raw / f"batch.lock.dead.{_dead_pid()}").write_text("2026-11-18,KIS,1\n1\n1\n", encoding="utf-8")
    r = env.run("drip", [A(1)])
    rows = env.ledger()
    assert (rows[0]["day_kst"], rows[0]["stop_reason"], rows[0]["requests"]) == ("2026-11-18", "CRASH", "2") and r.stop_reason == "DONE"
    assert not list(env.raw.glob("batch.lock.dead.*"))


FAIL3 = {"000001": date(2030, 1, 1), "000002": date(2030, 1, 1), "000003": date(2030, 1, 1)}


def test_three_failed_assets_in_a_row_end_the_batch_and_are_not_marked(tmp_path):
    env = Env(tmp_path, listings=FAIL3)
    r = env.run("drip", [A(1), A(2), A(3), A(4)])
    assert r.stop_reason == "FAILED_RUN" and r.stopped_on == "KR:000003" and r.assets_done == 0
    assert r.failed == ["KR:000001:DATA_CHECK(unmarked)", "KR:000002:DATA_CHECK(unmarked)", "KR:000003:DATA_CHECK(unmarked)"]
    assert not (env.raw / "failed").exists() and not list((env.raw / "kis").glob("kis_kr_000004_*"))
    assert "FAILED_RUN" not in batch.CLEAN_STOPS


def test_two_failures_then_a_success_are_marked(tmp_path):
    env = Env(tmp_path, listings={"000001": date(2030, 1, 1), "000002": date(2030, 1, 1)})
    r = env.run("drip", [A(1), A(2), A(3)])
    assert r.stop_reason == "DONE_WITH_FAILED" and r.assets_done == 1
    assert [batch.asset_state(env.raw, "drip", A(i)) for i in (1, 2, 3)] == ["FAILED", "FAILED", "DONE"]


def test_throttle_comes_before_the_window_check_and_journal(tmp_path):
    clock = FakeClock()
    seen = []
    sess = FakeSession(lambda m, u, k: Resp(token_body()) if m == "post" else Resp({"rt_cd": "0", "output2": []}))
    cl = KisClient(cfg(tmp_path, 1), sess, tmp_path / "raw", clock)
    cl.on_send = lambda: seen.append(clock.t)
    cl.daily_range("KR", "005930", date(2024, 1, 1), date(2024, 1, 31), adjusted=True)
    assert len(seen) == 2 and seen[1] - seen[0] >= 1.0 - 1e-9          # checked after the 1 s wait, right before sending


def test_durable_sync_uses_full_fsync_where_available_and_plain_fsync_otherwise(monkeypatch):
    import fcntl
    calls = []
    monkeypatch.setattr(fcntl, "fcntl", lambda fd, cmd, *a: calls.append(("full", cmd)))
    kis.durable_sync(1)
    assert calls == [("full", fcntl.F_FULLFSYNC)]
    plain = []
    monkeypatch.setattr(fcntl, "fcntl", lambda *a: (_ for _ in ()).throw(OSError("unsupported")))
    monkeypatch.setattr(kis.os, "fsync", lambda fd: plain.append(fd))
    kis.durable_sync(7)
    assert plain == [7]


def test_journal_and_lock_use_durable_sync(tmp_path, monkeypatch):
    env = Env(tmp_path)
    n = []
    monkeypatch.setattr(batch, "durable_sync", lambda fd: n.append(fd))
    env.run("drip", [A(1, "2025-06")])
    assert len(n) == 1 + env.fake.tokens + env.fake.gets               # the lock header and one line per request


def test_p0_08_refusal_compares_real_folders_not_spellings(tmp_path):
    env = Env(tmp_path)
    (env.repo / "data" / "raw" / "p0-08").mkdir(parents=True)
    env.raw = env.repo / "data" / "raw" / "P0-08"
    if not env.raw.exists():
        pytest.skip("case-sensitive volume")
    with pytest.raises(BatchRefused) as e:
        env.run("drip", [A(1)])
    assert e.value.reason == "RAW_DIR"

import json
import shutil
from pathlib import Path

import pandas as pd
import pytest

import fetch_aux as fa
from batch import Ledger
from proxy_sources import AuxError
from tests.fake_kis import clock_at
from tests.fakes import FakeSession

REAL = Path(__file__).resolve().parents[3] / "db" / "full"
KEY = "FAKEECOSKEY0123456789"


class R:
    def __init__(self, body, status=200):
        self.content = body.encode() if isinstance(body, str) else body
        self.status_code = status


@pytest.fixture
def world(tmp_path):
    full = tmp_path / "full"
    full.mkdir()
    shutil.copy(REAL / "proxy_series.csv", full / "proxy_series.csv")
    repo = tmp_path / "repo"
    raw = repo / "data" / "raw" / "mvp-04"
    raw.mkdir(parents=True)
    return repo, raw, full


def fred_handler(method, url, kw):
    sid = kw["params"]["id"]
    return R(f"observation_date,{sid}\n1995-12-29,5.71\n1996-01-03,5.65\n")


def run(world, source, handler=fred_handler, **kw):
    repo, raw, full = world
    return fa.run_fetch(source, raw=raw, repo_root=repo, session=FakeSession(handler), clock=kw.pop("clock", clock_at()), full=full, **kw)


def test_fred_batch_fetches_every_series_once_and_books_the_ledger(world):
    res = run(world, "FRED")
    assert (res.requests, res.stop_reason, res.fetched) == (5, "DONE", 5)
    rows = Ledger(world[1] / "ledger.csv").rows()
    assert [(r["source"], r["requests"], r["stop_reason"], r["batch_no"]) for r in rows] == [("FRED", "5", "DONE", "1")]
    again = run(world, "FRED")
    assert again.requests == 0 and again.cached == 5 and again.fetched == 0  # a second run reads the cache


def test_the_fx_input_dexkous_is_fetched_with_the_fred_batch_and_is_not_a_proxy_series(world):
    seen = []

    def handler(method, url, kw):
        seen.append(kw["params"]["id"])
        return fred_handler(method, url, kw)

    run(world, "FRED", handler)
    assert "DEXKOUS" in seen and fa.FX_FRED_INPUTS == ("DEXKOUS",)
    assert (world[1] / "aux" / "fred" / "DEXKOUS.csv").is_file()
    assert "DEXKOUS" not in (REAL / "proxy_series.csv").read_text()


def test_the_daily_cap_is_enforced_from_the_ledger(world):
    res = run(world, "FRED", cap=3)
    assert (res.requests, res.stop_reason, res.fetched) == (3, "BUDGET", 3)
    with pytest.raises(AuxError, match="already used 3 requests"):
        run(world, "FRED", cap=3)
    res = run(world, "FRED", cap=5)  # a higher cap allows the two missing files
    assert (res.requests, res.stop_reason) == (2, "DONE")


def test_the_cap_cannot_be_raised_above_the_hard_maximum(world):
    res = run(world, "FRED", cap=500)
    assert res.requests == 5
    repo, raw, _ = world
    Ledger(raw / "ledger.csv").append({"day_kst": "2026-11-19", "source": "FRED", "batch_no": 2, "requests": 20, "stop_reason": "BUDGET", "first_target": "", "last_target": ""})
    with pytest.raises(AuxError, match="already used"):
        run(world, "FRED", cap=500, clock=clock_at())


def test_a_git_worktree_is_refused_before_anything_is_sent(world):
    repo, raw, _ = world
    (repo / ".git").write_text("gitdir: /elsewhere\n", encoding="utf-8")
    session = FakeSession(fred_handler)
    with pytest.raises(AuxError, match="main checkout"):
        fa.run_fetch("FRED", raw=raw, repo_root=repo, session=session, clock=clock_at(), full=world[2])
    assert session.calls == [] and not (raw / "ledger.csv").exists()


def test_a_failed_answer_ends_the_run_with_a_ledger_row_and_keeps_finished_files(world, capsys):
    def handler(method, url, kw):
        return R("<html>", 200) if kw["params"]["id"] == "DGS2" else fred_handler(method, url, kw)

    res = run(world, "FRED", handler=handler)
    assert res.stop_reason == "ERROR" and res.fetched == 2 and res.requests == 3
    assert (world[1] / "aux" / "fred" / "DGS20.csv").is_file() and not (world[1] / "aux" / "fred" / "DGS2.csv").exists()
    assert Ledger(world[1] / "ledger.csv").rows()[-1]["stop_reason"] == "ERROR"


def test_ecos_run_uses_the_key_but_never_prints_it(world, capsys):
    def handler(method, url, kw):
        assert KEY in url
        stat, item = url.split("/")[-5], url.split("/")[-1]
        return R(json.dumps({"StatisticSearch": {"list_total_count": 1, "row": [{"STAT_CODE": stat, "ITEM_CODE1": item, "TIME": "19960103", "DATA_VALUE": "1.5"}]}}))

    res = run(world, "ECOS", handler=handler, ecos_key=KEY)
    out = capsys.readouterr()
    assert res.stop_reason == "DONE" and res.requests == 6 and res.fetched == 6
    assert KEY not in out.out + out.err
    bad = run(world, "ECOS", handler=handler, ecos_key="", cap=40)
    assert bad.requests == 0 and bad.cached == 6  # everything is cached, so a missing key does not matter


def test_ecos_without_a_key_stops_with_an_error_and_no_request(world, capsys):
    res = run(world, "ECOS", ecos_key="")
    assert res.stop_reason == "ERROR" and res.requests == 0


def test_wb_downloads_one_workbook_for_three_series(world):
    from tests.test_proxy_sources import _xlsx

    src = _xlsx(world[0].parent / "w.xlsx")
    res = run(world, "WB", handler=lambda m, u, k: R(src.read_bytes()))
    assert (res.requests, res.stop_reason, res.fetched) == (1, "DONE", 3)


def test_yfinance_run_spaces_calls_and_counts_them(world):
    class Ticker:
        def __init__(self, sym):
            self.sym = sym

        def history(self, start, end, auto_adjust):
            return pd.DataFrame({"Close": [10.5, 11.25], "Adj Close": [10.5, 11.25]}, index=pd.to_datetime(["2025-11-28", "2025-12-31"]))

    clock = clock_at()
    res = run(world, "YF", clock=clock, yf_factory=Ticker)
    assert (res.requests, res.stop_reason, res.fetched) == (7, "DONE", 7)
    assert len([s for s in clock.sleeps if s >= 3.0 - 1e-9]) == 6  # 3 s between seven calls
    assert run(world, "YF", yf_factory=Ticker).requests == 0


def test_status_reads_the_cache_offline(world, capsys):
    assert fa.main(["status", "--raw-dir", str(world[1])]) == 0
    out = capsys.readouterr().out
    assert "FRED: 0 of 5 cached" in out and "ECOS: 0 of 6 cached" in out and "YF: 0 of 7 cached" in out


def test_monthly_average_ecos_rows_are_fetched_as_monthly_statistics(world):
    paths = []

    def handler(method, url, kw):
        paths.append(url)
        stat, item = url.split("/")[-5], url.split("/")[-1]
        return R(json.dumps({"StatisticSearch": {"list_total_count": 1, "row": [{"STAT_CODE": stat, "ITEM_CODE1": item, "TIME": "199601", "DATA_VALUE": "12.4"}]}}))

    run(world, "ECOS", handler=handler, ecos_key=KEY)
    monthly = [u for u in paths if "/721Y001/" in u]
    assert len(monthly) == 2 and all("/M/199512/202512/" in u for u in monthly)
    assert all("/D/19951201/20251231/" in u for u in paths if "/721Y001/" not in u)
    assert (world[1] / "aux" / "ecos" / "721Y001_5020000_M.csv").is_file()


def test_an_interrupted_run_still_books_its_requests(world):
    calls = {"n": 0}

    def handler(method, url, kw):
        calls["n"] += 1
        if calls["n"] == 2:
            raise KeyboardInterrupt
        return fred_handler(method, url, kw)

    with pytest.raises(KeyboardInterrupt):
        run(world, "FRED", handler=handler)
    rows = Ledger(world[1] / "ledger.csv").rows()
    assert [(r["source"], r["requests"], r["stop_reason"]) for r in rows] == [("FRED", "2", "ERROR")]
    with pytest.raises(AuxError, match="already used"):
        run(world, "FRED", cap=2)  # the interrupted run counts toward the cap

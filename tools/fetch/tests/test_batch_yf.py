from datetime import date

import pytest

import batch
from batch import BatchRefused, FetchTarget, Ledger, run_yf_batch
from tests.batch_helpers import YfTicker, make_repo
from tests.fake_kis import clock_at


def t(sym, order, kr=True):
    return FetchTarget("KR" if kr else "US", sym.split(".")[0], None if kr else "NAS", "ADJUSTED", None, sym, None, order)


def run(tmp_path, targets, clock=None, repo=None, **kw):
    repo, raw = repo or make_repo(tmp_path)
    clock = clock or clock_at()
    YfTicker.calls = []
    return run_yf_batch(targets, raw_dir=raw, repo_root=repo, clock=clock, ticker_factory=YfTicker, **kw), clock, raw


def test_one_full_range_call_per_symbol_spaced_three_seconds(tmp_path):
    r, clock, raw = run(tmp_path, [t("005930.KS", 1), t("000660.KS", 2), t("AAPL", 3, kr=False)])
    assert r.stop_reason == "DONE" and r.requests == 3 and YfTicker.calls == ["005930.KS", "000660.KS", "AAPL"]
    assert clock.sleeps == [3.0, 3.0]
    files = sorted(p.name for p in (raw / "yf").glob("yf_px_*"))
    assert files == ["yf_px_000660.KS_19951201_20260102.csv", "yf_px_005930.KS_19951201_20260102.csv", "yf_px_AAPL_19951201_20260102.csv"]


def test_second_run_makes_no_calls(tmp_path):
    repo = make_repo(tmp_path)
    run(tmp_path, [t("005930.KS", 1)], repo=repo)
    clock = clock_at(d=20)
    r, _, _ = run(tmp_path, [t("005930.KS", 1)], clock=clock, repo=repo)
    assert r.requests == 0 and YfTicker.calls == []


def test_daily_call_cap_is_50_and_cannot_be_raised(tmp_path):
    targets = [t(f"S{i:03d}.KS", i + 1) for i in range(60)]
    r, _, raw = run(tmp_path, targets)
    assert (r.stop_reason, r.requests, r.assets_done, r.stopped_on) == ("BUDGET", 50, 50, "KR:S050")
    assert len(YfTicker.calls) == 50 and Ledger(raw / "ledger.csv").requests_on("2026-11-19", "YF") == 50
    with pytest.raises(BatchRefused) as e:
        run(tmp_path / "x", targets, cap=51)
    assert e.value.reason == "CAP_FLAG" and batch.HARD_MAX_CAP["YF"] == 50 and batch.DEFAULT_CAP["YF"] == 50


def test_cap_counts_the_ledger_across_batches_the_same_day(tmp_path):
    repo, raw = make_repo(tmp_path)
    Ledger(raw / "ledger.csv").append({"day_kst": "2026-11-19", "source": "YF", "batch_no": 1, "requests": 48,
                                      "stop_reason": "BUDGET", "first_target": "", "last_target": ""})
    r, _, _ = run(tmp_path, [t(f"S{i}.KS", i + 1) for i in range(5)], repo=(repo, raw), same_day=True)
    assert r.requests == 2 and r.stop_reason == "BUDGET"


def test_empty_answer_marks_the_symbol_failed_once_and_the_batch_goes_on(tmp_path):
    repo = make_repo(tmp_path)
    r, _, raw = run(tmp_path, [t("005930.KS", 1), t("EMPTY.KS", 2), t("000660.KS", 3)], repo=repo)
    assert r.stop_reason == "DONE_WITH_FAILED" and r.failed == ["KR:EMPTY:YF_EMPTY"]
    assert YfTicker.calls == ["005930.KS", "EMPTY.KS", "000660.KS"] and not list((raw / "yf").glob("yf_px_EMPTY*"))
    assert batch.asset_state(raw, "yf", t("EMPTY.KS", 2)) == "FAILED"
    r2, _, _ = run(tmp_path, [t("005930.KS", 1), t("EMPTY.KS", 2), t("000660.KS", 3)], clock=clock_at(d=20), repo=repo)
    assert r2.requests == 0 and YfTicker.calls == []                   # no call is spent on it again


def test_failed_answer_ends_the_batch_and_prints_only_the_exception_class(tmp_path, capsys):
    r, _, raw = run(tmp_path, [t("BOOM.KS", 1), t("000660.KS", 2)])
    assert r.stop_reason == "YF_ERROR" and YfTicker.calls == ["BOOM.KS"] and not list((raw / "yf").glob("*.csv"))
    out = capsys.readouterr().out
    assert "RuntimeError" in out and "boom" not in out


def test_symbols_without_yf_symbol_are_skipped(tmp_path):
    ft = FetchTarget("KR", "000001", None, "ADJUSTED", None, None, None, 1)
    r, _, _ = run(tmp_path, [ft, t("005930.KS", 2)])
    assert r.requests == 1 and batch.asset_state(make_repo(tmp_path)[1], "yf", ft) == "NO_YF"


def test_truncated_price_file_counts_as_missing(tmp_path):
    repo, raw = make_repo(tmp_path)
    run(tmp_path, [t("005930.KS", 1)], repo=(repo, raw))
    f = raw / "yf" / "yf_px_005930.KS_19951201_20260102.csv"
    f.write_text("trade_date,close,adj_close\n2024-01-31,10.5\n", encoding="utf-8")
    assert batch.asset_state(raw, "yf", t("005930.KS", 1)) == "PENDING"
    r, _, _ = run(tmp_path, [t("005930.KS", 1)], repo=(repo, raw), same_day=True)
    assert r.requests == 1 and batch.asset_state(raw, "yf", t("005930.KS", 1)) == "DONE"


def test_yf_batch_obeys_window_worktree_and_one_batch_a_day(tmp_path):
    with pytest.raises(BatchRefused) as e:
        run(tmp_path / "w", [t("005930.KS", 1)], clock=clock_at(hh=12))
    assert e.value.reason == "WINDOW"
    repo, raw = make_repo(tmp_path / "g")
    (repo / ".git").write_text("gitdir: x", encoding="utf-8")
    with pytest.raises(BatchRefused) as e:
        run(tmp_path / "g", [t("005930.KS", 1)], repo=(repo, raw))
    assert e.value.reason == "WORKTREE"
    repo, raw = make_repo(tmp_path / "d")
    run(tmp_path / "d", [t("005930.KS", 1)], repo=(repo, raw))
    with pytest.raises(BatchRefused) as e:
        run(tmp_path / "d", [t("000660.KS", 2)], repo=(repo, raw))
    assert e.value.reason == "SAME_DAY"


def test_yf_and_kis_ledgers_are_separate_sources(tmp_path):
    repo, raw = make_repo(tmp_path)
    Ledger(raw / "ledger.csv").append({"day_kst": "2026-11-19", "source": "KIS", "batch_no": 1, "requests": 600,
                                      "stop_reason": "BUDGET", "first_target": "", "last_target": ""})
    r, _, _ = run(tmp_path, [t("005930.KS", 1)], repo=(repo, raw))
    assert r.stop_reason == "DONE" and r.batch_no == 1


def test_fetcher_refuses_a_call_past_its_own_limit_without_calling_yfinance():
    from datetime import date as d
    import tempfile
    from pathlib import Path
    from yf import YfBudgetExhausted, YfFetcher
    YfTicker.calls = []
    with tempfile.TemporaryDirectory() as tmp:
        y = YfFetcher(Path(tmp), YfTicker, sleep=lambda s: None, now=lambda: 0.0, max_requests=1)
        y.series("AAA", d(2024, 1, 1), d(2024, 3, 1))
        with pytest.raises(YfBudgetExhausted):
            y.series("BBB", d(2024, 1, 1), d(2024, 3, 1))
    assert YfTicker.calls == ["AAA"]


def test_three_empty_answers_in_a_row_end_the_yfinance_batch_unmarked(tmp_path):
    r, _, raw = run(tmp_path, [t("EMPTY1.KS", 1), t("EMPTY2.KS", 2), t("EMPTY3.KS", 3), t("000660.KS", 4)])
    assert r.stop_reason == "FAILED_RUN" and YfTicker.calls == ["EMPTY1.KS", "EMPTY2.KS", "EMPTY3.KS"]
    assert not (raw / "failed").exists()


def test_empty_answers_separated_by_a_success_are_marked(tmp_path):
    r, _, raw = run(tmp_path, [t("EMPTY1.KS", 1), t("EMPTY2.KS", 2), t("000660.KS", 3), t("EMPTY3.KS", 4)])
    assert r.stop_reason == "DONE_WITH_FAILED" and len(list((raw / "failed").glob("*.json"))) == 3

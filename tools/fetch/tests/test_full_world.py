"""04-B: compare, judge and norm over a fake fetched world (sweep + drip through the real batches, cached pages only)."""
import csv
import hashlib
import json
from datetime import date

import pytest

import batch
import compare_full as cf
import fetch_batch as cli
from batch import BatchRefused
from tests.full_helpers import World, clone_world, weekdays, write_yf


@pytest.fixture(scope="module")
def world(tmp_path_factory):
    return World(tmp_path_factory.mktemp("world"))


@pytest.fixture
def w(world, tmp_path, monkeypatch):
    repo, raw = clone_world(world, tmp_path)
    monkeypatch.setattr(cli, "REPO_ROOT", repo)
    return repo, raw, world.targets, world.kr, world.us


def targets_csv(tmp_path, targets):
    p = tmp_path / "t.csv"
    rows = [[t.market, t.ticker, t.kis_excd or "", t.kis_basis, f"{t.kis_from:%Y-%m}" if t.kis_from else "", t.yf_symbol or "",
             f"{t.yf_canonical_to:%Y-%m}" if t.yf_canonical_to else "", str(t.order_no)] for t in targets]
    p.write_text("\n".join([",".join(batch.TARGETS_HEADER)] + [",".join(r) for r in rows]) + "\n", encoding="utf-8")
    return p


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def corrupt_monthly(raw):
    """Every monthly bar of the KR asset closes at 105 instead of 100: the sweep-day and drip-day adjustments disagree."""
    for page in sorted((raw / "kis").glob("kis_kr_005930_M_*")):
        doc = json.loads(page.read_text(encoding="utf-8"))
        for r in doc["response"]["output2"]:
            r["stck_clpr"] = "105"
        page.write_text(json.dumps(doc), encoding="utf-8")


def report(raw):
    return list(csv.DictReader((raw / "compare" / "compare_report.csv").open(encoding="utf-8")))


def test_flat_world_compares_clean_from_the_cache_with_zero_requests(w):
    repo, raw, targets, *_ = w
    res, jprob = cf.run_compare(targets, raw, repo)
    assert not res.problems and not res.skipped and not jprob and not cf.blocking(res.rows)
    kinds = {(r["kind"], r["verdict"]) for r in res.rows}
    assert kinds == {("CROSS", "MATCH"), ("INTERNAL", "EQUAL"), ("NO_OVERLAP", "NONE")}
    assert not [r for r in res.rows if r["kind"] == "CROSS" and r["month"] > "2025-12"]
    spy = [r for r in res.rows if r["ticker"] == "SPY" and r["kind"] == "CROSS"]
    assert {r["segment"] for r in spy} == {2} and spy[0]["month"] == "2008-01"  # KIS canonical after the 2007-12 cut
    assert [r for r in res.rows if r["ticker"] == "SPY" and r["kind"] == "NO_OVERLAP"][0]["canonical"] == "YF"
    kr = [r for r in res.rows if r["ticker"] == "005930" and r["kind"] == "CROSS"]
    assert kr[0]["month"] == "1996-01" and kr[-1]["month"] == "2025-12" and len(kr) == 360
    assert (raw / "compare" / "compare_report.csv").is_file() and (raw / "compare" / "refetch.csv").is_file()


def test_report_header_and_no_price_value(w):
    repo, raw, targets, *_ = w
    cf.run_compare(targets, raw, repo)
    rows = report(raw)
    assert list(rows[0]) == cf.REPORT_HEADER
    text = (raw / "compare" / "compare_report.csv").read_text(encoding="utf-8")
    assert "100.0" not in text and all("close" not in h for h in cf.REPORT_HEADER)


def test_unfetched_assets_are_skipped_and_named_not_compared(w):
    repo, raw, targets, kr, us = w
    for p in (raw / "done").glob("drip_US_SPY.json"):
        p.write_text("not json", encoding="utf-8")
    res, _ = cf.run_compare(targets, raw, repo)
    assert res.skipped == ["US:SPY: drip PARTIAL"] and {r["ticker"] for r in res.rows} == {"005930"}


def test_a_missing_cached_page_is_a_problem_for_that_asset_never_a_request(w):
    repo, raw, targets, *_ = w
    victim = sorted((raw / "kis").glob("kis_kr_005930_M_*"))[0]
    victim.write_text('{"request": {', encoding="utf-8")
    res, _ = cf.run_compare(targets, raw, repo)
    assert len(res.problems) == 1 and res.problems[0].startswith("KR:005930:") and {r["ticker"] for r in res.rows} == {"SPY"}


def test_a_missing_yfinance_file_is_a_problem_not_a_call(w):
    repo, raw, targets, *_ = w
    (raw / "yf").joinpath("yf_px_SPY_19951201_20260102.csv").write_text("trade_date,close,adj_close\n2007-01-02,1,1\n", encoding="utf-8")
    (raw / "yf").joinpath("yf_px_005930.KS_19951201_20260102.csv").unlink()
    res, _ = cf.run_compare(targets, raw, repo)
    assert res.skipped == ["KR:005930: yf PENDING"]
    assert any(p.startswith("US:SPY:") for p in res.problems)  # a one-row file is a partial response


def test_a_partial_yfinance_response_for_a_canonical_segment_is_refused(w):
    repo, raw, targets, *_ = w
    f = write_yf(raw, "SPY", drop={d.isoformat() for d in weekdays(date(2025, 6, 1), date(2026, 1, 2))})
    res, _ = cf.run_compare(targets, raw, repo)
    assert any(p.startswith("US:SPY:") and "부분 응답" in p for p in res.problems) and f.is_file()


def test_internal_diff_lists_the_asset_for_a_whole_refetch(w):
    repo, raw, targets, kr, _ = w
    corrupt_monthly(raw)
    res, _ = cf.run_compare(targets, raw, repo)
    assert res.refetch == [("KR", "005930", "DIFF")] and cf.blocking(res.rows)
    assert (raw / "compare" / "refetch.csv").read_text(encoding="utf-8").splitlines() == ["market,ticker,reason", "KR,005930,DIFF"]
    assert batch.asset_state(raw, "drip", kr) == "DONE"  # listing alone moves nothing


def test_refetch_diff_moves_the_whole_asset_to_stale_and_status_goes_back_to_pending(w):
    repo, raw, targets, kr, us = w
    corrupt_monthly(raw)
    before = {p.name for p in (raw / "kis").glob("kis_kr_005930_*")}
    cf.run_compare(targets, raw, repo, clock=None, refetch_diff=True)
    assert not list((raw / "kis").glob("kis_kr_005930_*")) and batch.asset_state(raw, "drip", kr) == "PENDING"
    assert batch.asset_state(raw, "sweep", kr) == "PENDING" and batch.asset_state(raw, "drip", us) == "DONE"
    (day,) = [p for p in (raw / "stale").iterdir()]
    assert before <= {p.name for p in day.iterdir()}  # nothing was deleted
    assert not (repo / "data" / "raw" / "mvp-04" / "batch.lock").exists()


def test_refetch_waits_for_a_running_batch(w):
    repo, raw, targets, *_ = w
    import os
    (raw / "batch.lock").write_text(f"2026-11-19,KIS,{os.getpid()}\n", encoding="utf-8")
    corrupt_monthly(raw)
    with pytest.raises(BatchRefused) as e:
        cf.run_compare(targets, raw, repo, refetch_diff=True)
    assert e.value.reason == "LOCK" and list((raw / "kis").glob("kis_kr_005930_*")) and not (raw / "stale").exists()


# -- judge ---------------------------------------------------------------------------------------------
def break_yf(raw, symbol, month_end, close, adj):
    write_yf(raw, symbol, {month_end: (close, adj)})


def test_judge_writes_rule_rows_and_the_rest_to_the_human_queue(w):
    repo, raw, targets, *_ = w
    # 2010-05-31 is a Monday: a 0.5 % high close there makes May and June opposite and the two-month product equal
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.5", "100.5")})
    out = cf.run_judge(targets, raw, repo)
    assert out["proposed"] == {"ONE_DAY": 2} and out["queue"] == [] and not out["problems"]
    rows = {(r["month"]): r["verdict"] for r in out["rows"] if r["kind"] == "CROSS" and r["ticker"] == "005930"}
    assert rows["2010-05"] == rows["2010-06"] == "JUDGED"
    j = cf.load_judgments_v2(raw / "judgments.csv")
    assert {k[3] for k in j} == {"2010-05", "2010-06"} and {v["proposed_by"] for v in j.values()} == {"RULE"}
    assert (raw / "compare" / "human_queue.csv").read_text(encoding="utf-8").splitlines() == [",".join(cf.QUEUE_HEADER)]
    res, jprob = cf.run_compare(targets, raw, repo)  # compare now passes on the same file
    assert not cf.blocking(res.rows) and not jprob


def test_judge_leaves_a_step_for_a_person_and_a_rerun_changes_nothing(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "005930.KS", {"2010-05-31": ("102", "102"), "2010-06-30": ("102", "102"), "2010-07-30": ("102", "102")})
    first = cf.run_judge(targets, raw, repo)
    assert first["queue"] and {q["hint"] for q in first["queue"]} <= {"STEP", "UNCLASSIFIED", "BIG_DIFF"}
    h1 = (sha(raw / "judgments.csv"), sha(raw / "compare" / "human_queue.csv"))
    second = cf.run_judge(targets, raw, repo)
    assert (sha(raw / "judgments.csv"), sha(raw / "compare" / "human_queue.csv")) == h1 and second["queue"] == first["queue"]


def test_judge_never_overwrites_a_human_row_and_rederives_a_stale_rule_row(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.5", "100.5")})
    cf.run_judge(targets, raw, repo)
    j = cf.load_judgments_v2(raw / "judgments.csv")
    human = {**j[("KR", "005930", "1", "2010-05")], "proposed_by": "HUMAN", "reason": "checked by hand", "diff_bp": "9.99"}
    cf.write_judgments(raw / "judgments.csv", [human, j[("KR", "005930", "1", "2010-06")]])
    out = cf.run_judge(targets, raw, repo)
    kept = cf.load_judgments_v2(raw / "judgments.csv")
    assert kept[("KR", "005930", "1", "2010-05")]["reason"] == "checked by hand" and kept[("KR", "005930", "1", "2010-05")]["proposed_by"] == "HUMAN"
    assert any("stale judgement" in p for p in out["problems"])  # the stale human row is reported, the cell is not hidden
    assert [q["month"] for q in out["queue"]] == ["2010-05"]
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.6", "100.6")})  # data changed: both RULE rows are re-derived
    cf.write_judgments(raw / "judgments.csv", [kept[("KR", "005930", "1", "2010-06")]])
    again = cf.run_judge(targets, raw, repo)
    assert again["proposed"] == {"ONE_DAY": 2} and not again["problems"]


def test_a_changed_difference_reopens_the_stop_in_compare(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.5", "100.5")})
    cf.run_judge(targets, raw, repo)
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.6", "100.6")})
    res, jprob = cf.run_compare(targets, raw, repo)
    assert [r["month"] for r in cf.blocking(res.rows)] == ["2010-05", "2010-06"] and jprob


# -- norm ------------------------------------------------------------------------------------------------
def norm_rows(raw):
    return list(csv.DictReader((raw / "norm" / "daily.csv").open(encoding="utf-8")))


def test_norm_writes_canonical_rows_per_segment_and_a_second_run_has_the_same_hashes(w):
    repo, raw, targets, *_ = w
    res = cf.run_norm(targets, raw, repo)
    assert res.ok and set(res.hashes) == {"daily.csv", "dividends.csv", "shares.csv"}
    rows = norm_rows(raw)
    kr = [r for r in rows if r["ticker"] == "005930"]
    assert {r["source"] for r in kr} == {"KIS"} and kr[0]["trade_date"].startswith("1995-12") and kr[-1]["trade_date"] == "2026-01-02"
    assert kr[-1]["close_raw"] == "100" and sum(1 for r in kr if r["close_raw"]) == 1
    spy = [r for r in rows if r["ticker"] == "SPY"]
    yf_rows = [r for r in spy if r["source"] == "YF"]
    kis_rows = [r for r in spy if r["source"] == "KIS"]
    assert yf_rows[-1]["trade_date"] == "2007-12-31" and yf_rows[0]["close_price"] == "100"
    assert kis_rows[0]["trade_date"].startswith("2007-12") and kis_rows[-1]["trade_date"] == "2026-01-02"
    first = {n: sha(raw / "norm" / n) for n in ("daily.csv", "dividends.csv", "shares.csv")} | {"MANIFEST": sha(raw / "MANIFEST.csv")}
    res2 = cf.run_norm(targets, raw, repo)
    assert res2.ok and res2.hashes == res.hashes
    assert first == {n: sha(raw / "norm" / n) for n in ("daily.csv", "dividends.csv", "shares.csv")} | {"MANIFEST": sha(raw / "MANIFEST.csv")}
    assert (raw / "MANIFEST.csv").read_text(encoding="utf-8").splitlines()[0] == "file,source,request,rows,sha256"


def test_norm_rows_start_the_month_before_the_first_return_month_and_stay_in_the_segment(w):
    repo, raw, targets, *_ = w
    cf.run_norm(targets, raw, repo)
    spy_yf = [r["trade_date"] for r in norm_rows(raw) if r["ticker"] == "SPY" and r["source"] == "YF"]
    assert spy_yf[0].startswith("1995-12")  # return month 1996-01 needs the 1995-12 month-end
    assert max(spy_yf) <= "2007-12-31" and all(r["close_raw"] == "" for r in norm_rows(raw) if r["source"] == "YF")


def test_norm_refuses_while_an_asset_is_not_done_and_writes_nothing(w):
    repo, raw, targets, *_ = w
    (raw / "done" / "drip_US_SPY.json").write_text("x", encoding="utf-8")
    res = cf.run_norm(targets, raw, repo)
    assert not res.ok and any("not done: US:SPY" in m for m in res.messages)
    assert not (raw / "norm").exists() or not list((raw / "norm").glob("*"))


def test_norm_refuses_on_an_open_stop_and_after_judging_it_runs(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.5", "100.5")})
    assert not cf.run_norm(targets, raw, repo).ok and not (raw / "norm" / "daily.csv").exists()
    cf.run_judge(targets, raw, repo)
    assert cf.run_norm(targets, raw, repo).ok and (raw / "norm" / "daily.csv").is_file()


def test_norm_failure_in_the_middle_leaves_the_old_files_untouched_and_no_temp_file(w, monkeypatch):
    repo, raw, targets, *_ = w
    cf.run_norm(targets, raw, repo)
    before = {p.name: sha(p) for p in (raw / "norm").glob("*.csv")}
    real = cf.asset_norm_rows
    calls = []

    def boom(ft, data, base):
        calls.append(ft.key)
        if ft.ticker == "SPY":
            raise ValueError("boom")
        return real(ft, data, base)

    monkeypatch.setattr(cf, "asset_norm_rows", boom)
    with pytest.raises(ValueError, match="boom"):
        cf.run_norm(targets, raw, repo)
    assert calls == ["KR:005930", "US:SPY"] and {p.name: sha(p) for p in (raw / "norm").glob("*.csv")} == before
    assert not list((raw / "norm").glob("*.tmp")) and not list(raw.glob("MANIFEST.csv.tmp"))


def test_norm_rejects_a_base_close_without_a_canonical_row(w):
    repo, raw, targets, kr, _ = w
    data = cf.AssetData(kis=[("2000-01-31", "100"), ("2000-02-29", "100")], raw_close={"2001-01-02": "100"})
    with pytest.raises(ValueError, match="base-date"):
        cf.asset_norm_rows(kr.__class__("KR", "005930", None, "ADJUSTED", date(2000, 1, 1), None, None, 1), data, date(2001, 1, 2))


# -- guards and CLI -----------------------------------------------------------------------------------------
@pytest.mark.parametrize("cmd", ["compare", "judge", "norm"])
def test_offline_commands_refuse_a_git_worktree_and_a_foreign_raw_dir(w, tmp_path, capsys, cmd):
    repo, raw, targets, *_ = w
    t = targets_csv(tmp_path, targets)
    assert cli.main([cmd, "--targets", str(t), "--raw-dir", str(tmp_path / "elsewhere")]) == 3 and "RAW_DIR" in capsys.readouterr().err
    (repo / ".git").write_text("gitdir: /x\n", encoding="utf-8")
    assert cli.main([cmd, "--targets", str(t), "--raw-dir", str(raw)]) == 3 and "WORKTREE" in capsys.readouterr().err
    assert not (raw / "compare").exists()


def test_cli_compare_judge_norm_exit_codes_and_output(w, tmp_path, capsys):
    repo, raw, targets, *_ = w
    t = targets_csv(tmp_path, targets)
    base = ["--targets", str(t), "--raw-dir", str(raw)]
    assert cli.main(["compare", *base]) == 0
    out = capsys.readouterr().out
    assert "CROSS:MATCH" in out and "assets not done 0" in out
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.5", "100.5")})
    assert cli.main(["compare", *base]) == 1 and "CROSS:STOP 2" in capsys.readouterr().out
    assert cli.main(["norm", *base]) == 1 and "norm stopped" in capsys.readouterr().err
    assert cli.main(["judge", *base]) == 0 and "new RULE judgements: ONE_DAY 2" in capsys.readouterr().out
    assert cli.main(["compare", *base]) == 0 and cli.main(["norm", *base]) == 0
    assert "daily.csv sha256=" in capsys.readouterr().out


def test_cli_compare_refetch_diff_flag(w, tmp_path, capsys):
    repo, raw, targets, kr, _ = w
    t = targets_csv(tmp_path, targets)
    corrupt_monthly(raw)
    assert cli.main(["compare", "--targets", str(t), "--raw-dir", str(raw)]) == 1
    assert "compare --refetch-diff" in capsys.readouterr().out and batch.asset_state(raw, "drip", kr) == "DONE"
    assert cli.main(["compare", "--targets", str(t), "--raw-dir", str(raw), "--refetch-diff"]) == 1
    assert "moved to stale/" in capsys.readouterr().out and batch.asset_state(raw, "drip", kr) == "PENDING"


def test_offline_commands_send_nothing_even_when_the_cache_is_incomplete(w, tmp_path):
    repo, raw, targets, *_ = w
    for p in (raw / "kis").glob("kis_kr_005930_D_*"):
        p.unlink()
    res, _ = cf.run_compare(targets, raw, repo)
    assert any(p.startswith("KR:005930:") for p in res.problems)  # a real request would have hit the blocked network and failed loudly


def test_the_offline_readers_cannot_send_a_request(tmp_path):
    from kis import KisError
    from yf import YfBudgetExhausted

    (tmp_path / "kis").mkdir()
    client, yf = cf.offline_readers(tmp_path)
    with pytest.raises(YfBudgetExhausted):
        yf.series("NOPE", batch.FULL_START, date(2026, 1, 2))
    with pytest.raises(KisError):
        client.daily_range("KR", "005930", date(2000, 1, 1), date(2000, 2, 1), adjusted=True)
    assert client.requests_made == 0 and yf.requests_made == 0


def test_a_skipped_asset_makes_compare_and_judge_exit_non_zero_and_names_it(w, tmp_path, capsys):
    repo, raw, targets, *_ = w
    t = targets_csv(tmp_path, targets)
    (raw / "yf" / "yf_px_SPY_19951201_20260102.csv").unlink()
    base = ["--targets", str(t), "--raw-dir", str(raw)]
    for cmd in ("compare", "judge"):
        assert cli.main([cmd, *base]) == 1
        assert "not done: US:SPY: yf PENDING" in capsys.readouterr().err


def test_a_front_truncated_yfinance_cache_fails_for_a_canonical_segment(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "SPY", first=date(2010, 1, 4))
    res, _ = cf.run_compare(targets, raw, repo)
    assert any(p.startswith("US:SPY:") and "front-truncated" in p for p in res.problems)
    write_yf(raw, "SPY", first=date(2006, 12, 1))
    res2, _ = cf.run_compare(targets, raw, repo)
    assert not res2.problems


def test_compare_exits_non_zero_on_asset_problems_alone(w, tmp_path, capsys):
    repo, raw, targets, *_ = w
    t = targets_csv(tmp_path, targets)
    for p in (raw / "kis").glob("kis_kr_005930_D_*"):
        p.write_text('{"request": {', encoding="utf-8")
    res, jprob = cf.run_compare(targets, raw, repo)
    assert res.problems and not res.skipped and not jprob and not cf.blocking(res.rows)  # nothing but the asset problem
    assert cli.main(["compare", "--targets", str(t), "--raw-dir", str(raw)]) == 1


def test_norm_refuses_on_a_stale_or_unused_judgement_alone(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "005930.KS", {"2010-05-31": ("100.5", "100.5")})
    cf.run_judge(targets, raw, repo)
    j = cf.load_judgments_v2(raw / "judgments.csv")
    assert cf.run_norm(targets, raw, repo).ok
    for f in (raw / "norm").glob("*"):
        f.unlink()
    extra = {**j[("KR", "005930", "1", "2010-05")], "month": "2010-09", "proposed_by": "HUMAN"}  # no such open cell
    cf.write_judgments(raw / "judgments.csv", [*j.values(), extra])
    res, jprob = cf.run_compare(targets, raw, repo)
    assert jprob and not cf.blocking(res.rows)
    out = cf.run_norm(targets, raw, repo)
    assert not out.ok and not (raw / "norm" / "daily.csv").exists()


def drop_bars(raw, month):
    for page in sorted((raw / "kis").glob("kis_kr_005930_M_*")):
        doc = json.loads(page.read_text(encoding="utf-8"))
        doc["response"]["output2"] = [r for r in doc["response"]["output2"] if not r["stck_bsop_date"].startswith(month)]
        page.write_text(json.dumps(doc), encoding="utf-8")


def test_a_missing_bar_alone_lists_the_asset_and_refetch_diff_moves_it(w):
    repo, raw, targets, kr, _ = w
    drop_bars(raw, "201006")
    res, _ = cf.run_compare(targets, raw, repo)
    assert res.refetch == [("KR", "005930", "NO_BAR")] and [r["verdict"] for r in cf.blocking(res.rows)] == ["NO_BAR"]
    assert (raw / "compare" / "refetch.csv").read_text(encoding="utf-8").splitlines()[1] == "KR,005930,NO_BAR"
    cf.run_compare(targets, raw, repo, refetch_diff=True)
    assert batch.asset_state(raw, "drip", kr) == "PENDING" and batch.asset_state(raw, "sweep", kr) == "PENDING"


def test_the_yfinance_start_bound_is_2006_12_inclusive(w):
    repo, raw, targets, *_ = w
    write_yf(raw, "SPY", first=date(2006, 12, 1))
    assert not cf.run_compare(targets, raw, repo)[0].problems
    write_yf(raw, "SPY", first=date(2007, 1, 1))
    assert any("front-truncated" in p for p in cf.run_compare(targets, raw, repo)[0].problems)

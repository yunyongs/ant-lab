"""db/full/build_full.sql on the tiny synthetic world (MVP-06 A).

The runner is pytest because the repository's Python tests already run in CI and in the worktrees, and a throwaway PostgreSQL
17 can be started per test session (a unix socket in a temp directory, no TCP, never the owner's database). The whole module is
skipped with a reason when no PostgreSQL 17 binaries are found.
"""
from __future__ import annotations

import csv
import hashlib
import os
import shutil
import subprocess
import tempfile
from decimal import Decimal
from pathlib import Path

import pytest

import build_full_reference as ref
from proxy_map import months as month_range

HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "fixtures" / "build_full"
SQL = HERE.parents[2] / "db" / "full" / "build_full.sql"
FIRST, LAST = "2006-09", "2007-04"
RANGE_VARS = ["-v", "first_month=2006-09-01", "-v", "last_month=2007-04-01", "-v", "base_date=2007-05-02"]
OUTPUTS = ["month_block.csv", "fx_month.csv", "asset_position.csv", "asset_month_return.csv", "lineage.csv"]


def _find(name: str) -> str | None:
    hit = shutil.which(name)
    if hit:
        return hit
    for base in ("/opt/homebrew/opt/postgresql@17/bin", "/usr/local/opt/postgresql@17/bin", "/usr/lib/postgresql/17/bin"):
        p = Path(base) / name
        if p.is_file():
            return str(p)
    return None


BIN = {n: _find(n) for n in ("initdb", "pg_ctl", "psql")}
pytestmark = pytest.mark.skipif(not all(BIN.values()), reason="PostgreSQL 17 binaries (initdb, pg_ctl, psql) not found; build_full.sql tests need a throwaway server")


@pytest.fixture(scope="session")
def pg():
    root = Path(tempfile.mkdtemp(prefix="bf"))  # short: a unix socket path is limited to about 100 characters
    data, sock = root / "d", root / "s"
    sock.mkdir()
    subprocess.run([BIN["initdb"], "-D", str(data), "-A", "trust", "-U", "postgres", "-E", "UTF8", "--no-locale"], check=True, capture_output=True)
    port = "54000"
    subprocess.run([BIN["pg_ctl"], "-D", str(data), "-o", f"-c listen_addresses= -p {port} -k {sock} -c fsync=off", "-l", str(root / "log"), "-w", "start"],
                   check=True, capture_output=True)
    yield {"sock": str(sock), "port": port}
    subprocess.run([BIN["pg_ctl"], "-D", str(data), "-m", "immediate", "stop"], capture_output=True)
    shutil.rmtree(root, ignore_errors=True)


def build(pg, world: Path, out: Path, *extra: str, sql: Path = SQL) -> subprocess.CompletedProcess:
    out.mkdir(parents=True, exist_ok=True)
    work = out.parent / (out.name + "_work")
    work.mkdir(exist_ok=True)
    cmd = [BIN["psql"], "-X", "-q", "-v", "ON_ERROR_STOP=1", "-h", pg["sock"], "-p", pg["port"], "-U", "postgres", "-d", "postgres",
           "-v", f"indir={world / 'raw'}", "-v", f"mapdir={world / 'map'}", "-v", f"outdir={out}", "-v", f"workdir={work}", *RANGE_VARS, *extra, "-f", str(sql)]
    return subprocess.run(cmd, capture_output=True, text=True, cwd=str(world))


def sha(out: Path) -> dict[str, str]:
    return {n: hashlib.sha256((out / n).read_bytes()).hexdigest() for n in OUTPUTS}


def read(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as h:
        return list(csv.DictReader(h))


@pytest.fixture
def world(tmp_path) -> Path:
    shutil.copytree(FIXTURE, tmp_path / "w")
    return tmp_path / "w"


def edit(path: Path, fn) -> None:
    text = path.read_text(encoding="utf-8")
    new = fn(text)
    assert new != text, "the edit changed nothing"
    path.write_text(new, encoding="utf-8")


def drop_lines(path: Path, contains: str) -> None:
    edit(path, lambda t: "".join(line for line in t.splitlines(keepends=True) if contains not in line))


# -- golden and parity -----------------------------------------------------------------------------------------------
def test_matches_the_golden_output_and_a_rerun_gives_the_same_bytes(pg, world, tmp_path):
    a = build(pg, world, tmp_path / "a")
    assert a.returncode == 0, a.stderr
    for n in OUTPUTS:
        assert (tmp_path / "a" / n).read_bytes() == (FIXTURE / "expected" / n).read_bytes(), n
    b = build(pg, world, tmp_path / "b")
    assert b.returncode == 0, b.stderr
    assert sha(tmp_path / "a") == sha(tmp_path / "b")


def test_every_cell_equals_the_independent_python_reference(pg, world, tmp_path):
    assert build(pg, world, tmp_path / "o").returncode == 0
    expected = ref.compute(world / "map", world / "raw", FIRST, LAST)
    got = {(r["market"], r["ticker"], r["month_start"][:7]): r for r in read(tmp_path / "o" / "asset_month_return.csv")}
    assert set(got) == set(expected) and len(got) == 8 * 8
    proxied = 0
    for k, (r, kind, series_id) in expected.items():
        assert got[k]["return_kind"] == kind, k
        assert got[k]["local_return"] == ref.r8(r), k  # exact: both sides round once, half away from zero
        proxied += kind == "PROXY_INDEX"
    assert proxied == 20
    lineage = {(r["market"], r["ticker"], r["month_start"][:7]): r["series_id"] for r in read(tmp_path / "o" / "lineage.csv")}
    assert lineage == {k: v[2] for k, v in expected.items()}
    fx = ref.fx_changes(world / "raw", month_range(FIRST, LAST))
    assert {r["month_start"][:7]: r["fx_change"] for r in read(tmp_path / "o" / "fx_month.csv")} == {m: ref.r8(v) for m, v in fx.items()}


def test_proxy_rules_on_named_cells(pg, world, tmp_path):
    assert build(pg, world, tmp_path / "o").returncode == 0
    got = {(r["market"], r["ticker"], r["month_start"][:7]): r for r in read(tmp_path / "o" / "asset_month_return.csv")}
    # priority: 200001 takes the won-converted Treasury series from 2006-11 and the KOSPI before (the first rung starts 2006-10 with a return)
    lineage = {(r["market"], r["ticker"], r["month_start"][:7]): r["series_id"] for r in read(tmp_path / "o" / "lineage.csv")}
    assert [lineage[("KR", "200001", m)] for m in ("2006-09", "2006-10", "2006-11", "2006-12", "2007-01")] == ["KR_KOSPI", "US_UST10Y_TR", "US_UST10Y_TR", "US_UST10Y_TR", ""]
    assert [lineage[("US", "MIXE", m)] for m in ("2006-09", "2006-10", "2006-12", "2007-01")] == ["MIX_SPX_GOLD_70", "MIX_SPX_GOLD", "MIX_SPX_GOLD", ""]
    # a month without a close and the month after it are proxy months; the other months of the same stock are actual
    assert [got[("US", "TECH1", m)]["return_kind"] for m in ("2006-09", "2006-10", "2006-11", "2006-12", "2007-01")] == ["ACTUAL", "PROXY_INDEX", "PROXY_INDEX", "ACTUAL", "ACTUAL"]
    # no fee on a stock proxy month: the stock takes SPY's actual return unchanged
    spy = {m: Decimal(got[("US", "SPY", m)]["local_return"]) for m in ("2006-10", "2006-11")}
    assert {m: Decimal(got[("US", "TECH1", m)]["local_return"]) for m in spy} == spy
    # the canonical source flips at 2007-01: the return uses the KIS closes of both month-ends, not YF 2006-12 with KIS 2007-01
    world_rows = read(world / "raw" / "norm" / "daily.csv")
    kis = {r["trade_date"]: Decimal(r["close"]) for r in world_rows if (r["ticker"], r["source"]) == ("SPY", "KIS")}
    assert Decimal(got[("US", "SPY", "2007-01")]["local_return"]) == (kis["2007-01-31"] / kis["2006-12-29"] - 1).quantize(Decimal("1e-8"))


def test_position_and_calendar_files(pg, world, tmp_path):
    assert build(pg, world, tmp_path / "o").returncode == 0
    pos = read(tmp_path / "o" / "asset_position.csv")
    assert list(pos[0]) == ["market", "ticker", "base_date", "base_price", "base_fx_rate", "market_cap_rank"]
    by = {(r["market"], r["ticker"]): r for r in pos}
    assert by[("KR", "100001")]["base_fx_rate"] == "1.0000" and by[("KR", "100001")]["market_cap_rank"] == "1"
    assert by[("KR", "200001")]["market_cap_rank"] == ""  # ETFs have no rank
    assert by[("US", "SPY")]["base_fx_rate"] != "1.0000"
    assert [r["month_start"] for r in read(tmp_path / "o" / "month_block.csv")] == [f"{m}-01" for m in month_range(FIRST, LAST)]
    assert list(read(tmp_path / "o" / "fx_month.csv")[0]) == ["month_start", "currency", "fx_change"]
    assert len(read(tmp_path / "o" / "asset_month_return.csv")) == 64


# -- hard stops: each stops the build with a non-zero status and writes nothing -------------------------------------
def stops(pg, world, tmp_path, needle: str) -> None:
    out = tmp_path / "stopped"
    res = build(pg, world, out)
    assert res.returncode != 0, "the build should have stopped"
    assert needle in res.stderr, res.stderr
    assert not any((out / n).exists() for n in OUTPUTS), "a stopped build wrote an output"


def test_stop_cell_with_neither_actual_nor_proxy(pg, world, tmp_path):
    drop_lines(world / "map" / "proxy_map.csv", "KR,100002")
    stops(pg, world, tmp_path, "NO_ACTUAL_NO_SERIES")


def test_stop_chosen_series_has_no_value_for_the_month(pg, world, tmp_path):
    drop_lines(world / "raw" / "aux" / "ecos" / "802Y001_0001000_D.csv", "200610")  # no October level: the October and November KOSPI returns are missing
    stops(pg, world, tmp_path, "SERIES_VALUE_MISSING")


def test_stop_pending_fee_on_an_asset_with_proxy_months(pg, world, tmp_path):
    edit(world / "map" / "proxy_map.csv", lambda t: t.replace("KR,200002,1,WB_GOLD_USD,Y,Y,0.0019,MACRO_ETF_3_2", "KR,200002,1,WB_GOLD_USD,Y,Y,,PENDING"))
    stops(pg, world, tmp_path, "FEE_PENDING")


def test_pending_fee_is_fine_on_a_rung_that_is_never_used(pg, world, tmp_path):
    edit(world / "map" / "proxy_map.csv", lambda t: t.replace("KR,200003,1,MIX_SPX_CD,N,Y,0.0025,MACRO_ETF_3_2", "KR,200003,1,MIX_SPX_CD,N,Y,0.0025,MACRO_ETF_3_2\nKR,200003,2,KR_KOSPI,N,Y,,PENDING"))
    res = build(pg, world, tmp_path / "o")
    assert res.returncode == 0, res.stderr


def test_stop_stock_without_an_actual_return_from_2007_01(pg, world, tmp_path):
    drop_lines(world / "raw" / "norm" / "daily.csv", "KR,100001,2007-02")
    stops(pg, world, tmp_path, "STOCK_NOT_ACTUAL_FROM_2007")


def test_stop_duplicate_daily_row(pg, world, tmp_path):
    edit(world / "raw" / "norm" / "daily.csv", lambda t: t + "KR,100001,2007-02-28,1.0000,,,KIS\n")
    stops(pg, world, tmp_path, "repeats")


def test_stop_duplicate_asset_row(pg, world, tmp_path):
    edit(world / "map" / "asset.csv", lambda t: t + "KR,100001,Fake stock A,STOCK,KRW,FIN,2000-01-03,PRICE,\n")
    stops(pg, world, tmp_path, "duplicate (market, ticker)")


def test_stop_missing_input_file(pg, world, tmp_path):
    (world / "raw" / "aux" / "fred" / "DGS10.csv").unlink()
    res = build(pg, world, tmp_path / "o")
    assert res.returncode != 0 and "DGS10.csv" in res.stderr


def test_stop_header_only_file(pg, world, tmp_path):
    (world / "raw" / "aux" / "wb" / "monthly_prices.csv").write_text("series,month,value\n", encoding="utf-8")
    stops(pg, world, tmp_path, "FILE_EMPTY_OR_MISSING")


def test_stop_non_numeric_close(pg, world, tmp_path):
    edit(world / "raw" / "norm" / "daily.csv", lambda t: t.replace("KR,100001,2006-09-15,30", "KR,100001,2006-09-15,x30", 1))
    stops(pg, world, tmp_path, "malformed rows")


def test_stop_ticker_not_in_the_pool(pg, world, tmp_path):
    edit(world / "raw" / "norm" / "daily.csv", lambda t: t + "KR,999999,2007-02-28,1.0000,,,KIS\n")
    stops(pg, world, tmp_path, "not in asset.csv")


@pytest.mark.parametrize("spelling", ["db/sample", "db//sample", "db/./sample", "./db/sample/", "DB/Sample"])
def test_refuses_to_write_into_db_sample(pg, world, tmp_path, spelling):
    (tmp_path / "db" / "sample").mkdir(parents=True)
    bad = str(tmp_path) + "/" + spelling
    cmd = [BIN["psql"], "-X", "-q", "-v", "ON_ERROR_STOP=1", "-h", pg["sock"], "-p", pg["port"], "-U", "postgres", "-d", "postgres",
           "-v", f"indir={world / 'raw'}", "-v", f"mapdir={world / 'map'}", "-v", f"outdir={bad}", *RANGE_VARS, "-f", str(SQL)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode != 0 and "refusing to write into db/sample" in res.stderr
    assert not any((tmp_path / "db" / "sample" / n).exists() for n in OUTPUTS + [".build_full_probe"])


def test_refuses_a_workdir_inside_db_sample(pg, world, tmp_path):
    (tmp_path / "db" / "sample").mkdir(parents=True)
    (tmp_path / "o").mkdir()
    res = build(pg, world, tmp_path / "o", "-v", f"workdir={tmp_path}/db//sample")
    assert res.returncode != 0 and "refusing to write into db/sample" in res.stderr


def test_stop_ticker_case_duplicate(pg, world, tmp_path):
    edit(world / "raw" / "norm" / "daily.csv", lambda t: t + "US,tech1,2007-02-28,1.0000,,,KIS\n")
    stops(pg, world, tmp_path, "repeats")


def test_empty_fred_cells_are_missing_values_not_malformed_rows(pg, world, tmp_path):
    assert (world / "raw" / "aux" / "fred" / "DGS10.csv").read_text().count(",\n") == 1  # the fixture holds an empty cell
    res = build(pg, world, tmp_path / "o")
    assert res.returncode == 0, res.stderr


def test_missing_output_directory_stops_before_any_work(pg, world, tmp_path):
    out = tmp_path / "nowhere"
    cmd = [BIN["psql"], "-X", "-q", "-v", "ON_ERROR_STOP=1", "-h", pg["sock"], "-p", pg["port"], "-U", "postgres", "-d", "postgres",
           "-v", f"indir={world / 'raw'}", "-v", f"mapdir={world / 'map'}", "-v", f"outdir={out}", *RANGE_VARS, "-f", str(SQL)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode != 0 and ".build_full_probe" in res.stderr and not out.exists()


def test_stopped_build_leaves_existing_files_alone(pg, world, tmp_path):
    out = tmp_path / "o"
    assert build(pg, world, out).returncode == 0
    before = sha(out)
    drop_lines(world / "map" / "proxy_map.csv", "KR,100002")
    assert build(pg, world, out).returncode != 0
    assert sha(out) == before


# -- partial mode ---------------------------------------------------------------------------------------------------------
def test_partial_mode_lists_every_gap_and_writes_nothing(pg, world, tmp_path):
    drop_lines(world / "map" / "proxy_map.csv", "KR,100002")
    drop_lines(world / "raw" / "norm" / "daily.csv", "KR,100001,2007-02")
    (world / "raw" / "aux" / "fred" / "DGS10.csv").unlink()
    out = tmp_path / "o"
    res = build(pg, world, out, "-v", "partial=1")
    assert res.returncode == 0, res.stderr
    assert not any((out / n).exists() for n in OUTPUTS)
    for kind in ("NO_ACTUAL_NO_SERIES", "STOCK_NOT_ACTUAL_FROM_2007", "FILE_EMPTY_OR_MISSING"):
        assert kind in res.stdout, res.stdout


def test_partial_mode_on_a_clean_world_reports_no_gaps(pg, world, tmp_path):
    res = build(pg, world, tmp_path / "o", "-v", "partial=1")
    assert res.returncode == 0 and "(0 rows)" in res.stdout
    assert not any((tmp_path / "o" / n).exists() for n in OUTPUTS)


# -- a longer run: ten years of random yields through every bond maturity, compared cell by cell --------------------------
def test_bond_and_cash_formulas_match_the_reference_over_ten_years(pg, tmp_path):
    import random

    import make_build_full_fixture as mk

    rng = random.Random(7)
    w = tmp_path / "w"
    ms = month_range("1999-12", "2009-12")
    first, last = "2000-01", "2009-12"
    mats = {"A": 2, "B": 3, "C": 10, "D": 20}
    (tmp_path / "o").mkdir()
    mk._write(w / "map" / "asset.csv", mk.ASSET_COLS, [("US", f"BND{k}", f"Fake bond {k}", "ETF", "USD", "BOND", "2000-01-03", "TOTAL", "") for k in mats])
    mk._write(w / "map" / "fetch_targets.csv", mk.TARGET_COLS, [("US", f"BND{k}", "AMS", "ADJUSTED", "", f"BND{k}", "", i + 1) for i, k in enumerate(mats)])
    rows = [(f"{k}_YLD", "FRED", f"FRED{k}", "USD", "RATE", "MONTH_END", "1999-12", last, "", mk.URL, "2026-11-21") for k in mats]
    rows += [(f"{k}_TR", "DERIVED", "", "USD", "TOTAL", "MONTH_END", first, last, f"PAR_BOND_TR({k}_YLD,{n})", "", "") for k, n in mats.items()]
    rows.append(("CASH_TR", "DERIVED", "", "USD", "TOTAL", "MONTH_END", first, last, "CASH_ACCRUAL(A_YLD)", "", ""))
    mk._write(w / "map" / "proxy_series.csv", mk.SERIES_COLS, rows)
    mk._write(w / "map" / "proxy_map.csv", mk.MAP_COLS, [("US", f"BND{k}", 1, f"{k}_TR", "N", "Y", "0.0005", "MACRO_ETF_3_2") for k in mats] + [("US", "BNDA", 2, "CASH_TR", "N", "Y", "0.0005", "MACRO_ETF_3_2")])
    mk._write(w / "map" / "pool_proposal.csv", mk.POOL_COLS,
              [("US", f"BND{k}", "x", "ETF", "BOND", "2000-01-03", "CONFIRMED", "TOTAL", "R", i + 1, "", "", mk.URL, "", "", "", "", "Y", "SELECTED", "", "") for i, k in enumerate(mats)])
    mk._write(w / "raw" / "norm" / "daily.csv", mk.DAILY_COLS, [("US", f"BND{k}", "2010-01-04", "50.0000", "", "50.0000", "KIS") for k in mats])
    for i, k in enumerate(mats):
        y = [round(rng.uniform(-0.5, 12.0), 4) for _ in ms]
        if k == "A":
            y[10] = y[11] = 0.0  # j = 0: the annuity is n, not a division by zero
        mk._write(w / "raw" / "aux" / "fred" / f"FRED{k}.csv", ["observation_date", f"FRED{k}"], [(f"{m}-28", str(v)) for m, v in zip(ms, y)])
    fx = [str(round(rng.uniform(900, 1500), 2)) for _ in ms]
    mk._write(w / "raw" / "aux" / "fred" / "DEXKOUS.csv", ["observation_date", "DEXKOUS"], [(f"{m}-27", v) for m, v in zip(ms, fx)] + [("2010-01-04", "1000.00")])
    res = subprocess.run([BIN["psql"], "-X", "-q", "-v", "ON_ERROR_STOP=1", "-h", pg["sock"], "-p", pg["port"], "-U", "postgres", "-d", "postgres",
                          "-v", f"indir={w / 'raw'}", "-v", f"mapdir={w / 'map'}", "-v", f"outdir={tmp_path / 'o'}", "-v", f"workdir={tmp_path}",
                          "-v", "first_month=2000-01-01", "-v", "last_month=2009-12-01", "-v", "base_date=2010-01-04", "-f", str(SQL)], capture_output=True, text=True)
    assert res.returncode == 0, res.stderr
    expected = ref.compute(w / "map", w / "raw", first, last)
    got = {(r["market"], r["ticker"], r["month_start"][:7]): r["local_return"] for r in read(tmp_path / "o" / "asset_month_return.csv")}
    assert len(got) == 4 * 120 and set(got) == set(expected)
    for k, (r, kind, _s) in expected.items():
        assert kind == "PROXY_INDEX" and got[k] == ref.r8(r), k

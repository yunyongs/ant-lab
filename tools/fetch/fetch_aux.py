"""Fetch the public proxy series named in db/full/proxy_series.csv (MVP-03 A). Run from the main checkout only.

  PY=tools/fetch/.venv/bin/python
  $PY tools/fetch/fetch_aux.py status                      # offline: which source files are cached
  $PY tools/fetch/fetch_aux.py fetch --source FRED         # keyless CSV files, one request per series
  $PY tools/fetch/fetch_aux.py fetch --source ECOS         # needs ECOS_API_KEY in .env (read by this tool, never printed)
  $PY tools/fetch/fetch_aux.py fetch --source WB           # one workbook (gold, copper, WTI month averages)
  $PY tools/fetch/fetch_aux.py fetch --source YF           # one full-range call per index symbol, 3 s apart

Files go to data/raw/mvp-04/aux/{fred,ecos,wb}/ and data/raw/mvp-04/yf/ (git-ignored). Every run is one row in
data/raw/mvp-04/ledger.csv; the per-source daily cap is checked against that ledger before anything is sent, and a cached file
costs no request. Nothing here touches a database.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from batch import Ledger, is_git_worktree, kst_now  # noqa: E402
from common import REPO_ROOT, SecretFilter, mask, read_env  # noqa: E402
from kis import Clock  # noqa: E402
from proxy_map import FULL, SERIES_COLUMNS, read_csv  # noqa: E402
from proxy_sources import AuxBudgetExhausted, AuxClient, AuxError, DEFAULT_CAP, HARD_MAX_CAP  # noqa: E402
from yf import EmptySeries, YfBudgetExhausted, YfFetcher  # noqa: E402

DEFAULT_RAW_DIR = REPO_ROOT / "data" / "raw" / "mvp-04"
YF_CAP = 50  # the same per-day yfinance ceiling the price batch uses; both count in one ledger
HISTORY_START, HISTORY_END = date(1995, 12, 1), date(2026, 1, 2)
ECOS_WINDOW = {"D": ("19951201", "20251231"), "M": ("199512", "202512")}
SOURCES = ("FRED", "ECOS", "WB", "YF")


@dataclass
class AuxResult:
    source: str
    day: str
    requests: int
    stop_reason: str
    fetched: int
    cached: int
    first: str
    last: str


def rows_for(source: str, full: Path = FULL) -> list[dict[str, str]]:
    return [r for r in read_csv(full / "proxy_series.csv", SERIES_COLUMNS) if r["source"] == source]


def ecos_cycle(row: dict[str, str]) -> str:
    """Month-average rows are fetched as monthly statistics, the others as daily ones."""
    return "M" if row["observation"] == "MONTH_AVG" else "D"


def cached_state(raw: Path, rows: list[dict[str, str]], source: str) -> dict[str, bool]:
    """Offline view: True when the source file is present (parse checks happen at fetch time)."""
    aux = raw / "aux"
    out = {}
    for r in rows:
        code = r["source_code"]
        if source == "FRED":
            out[r["series_id"]] = (aux / "fred" / f"{code}.csv").is_file()
        elif source == "ECOS":
            stat, item = code.split("/")
            out[r["series_id"]] = (aux / "ecos" / f"{stat}_{item}_{ecos_cycle(r)}.csv").is_file()
        elif source == "WB":
            out[r["series_id"]] = (aux / "wb" / "CMO-Historical-Data-Monthly.xlsx").is_file()
        else:
            out[r["series_id"]] = any((raw / "yf").glob(f"yf_px_{code}_{HISTORY_START:%Y%m%d}_{HISTORY_END:%Y%m%d}.csv"))
    return out


def run_fetch(source: str, *, raw: Path, repo_root: Path, session, clock: Clock, ecos_key: str = "", cap: int | None = None,
              full: Path = FULL, yf_factory=None) -> AuxResult:
    if is_git_worktree(repo_root):
        raise AuxError("refused: run from the main checkout (the raw cache and ledger would be lost with a worktree)")
    hard = YF_CAP if source == "YF" else HARD_MAX_CAP[source]
    cap = min(cap if cap is not None else (YF_CAP if source == "YF" else DEFAULT_CAP[source]), hard)
    ledger = Ledger(raw / "ledger.csv")
    day = kst_now(clock).strftime("%Y-%m-%d")
    left = cap - ledger.requests_on(day, source)
    if left <= 0:
        raise AuxError(f"refused: {source} already used {cap} requests on {day} (KST)")
    rows = rows_for(source, full)
    before = cached_state(raw, rows, source)
    todo = [r for r in rows if not before[r["series_id"]]]
    done_ids: list[str] = []
    yf_fetcher = YfFetcher(raw / "yf", ticker_factory=yf_factory, sleep=clock.sleep, now=clock.now, interval=3.0, max_requests=left) if source == "YF" else None
    client = AuxClient(session, raw / "aux", clock=clock, ecos_key=ecos_key, max_requests={**DEFAULT_CAP, source: left}) if source != "YF" else None
    stop = "ERROR"  # stays ERROR when the run dies on anything unexpected; the ledger row is written in finally either way
    try:
        if yf_fetcher is not None:
            for r in todo:
                yf_fetcher.series(r["source_code"], HISTORY_START, HISTORY_END)
                done_ids.append(r["series_id"])
        elif source == "WB":
            if todo:
                client.wb_pink_sheet()
                done_ids = [r["series_id"] for r in todo]
        else:
            for r in todo:
                if source == "FRED":
                    client.fred_csv(r["source_code"])
                else:
                    stat, item = r["source_code"].split("/")
                    cycle = ecos_cycle(r)
                    client.ecos_series(stat, item, cycle, *ECOS_WINDOW[cycle])
                done_ids.append(r["series_id"])
        stop = "DONE"
    except (AuxBudgetExhausted, YfBudgetExhausted):
        stop = "BUDGET"
    except (AuxError, EmptySeries, OSError, RuntimeError) as exc:
        print(mask(str(exc), [ecos_key]), file=sys.stderr)
    finally:
        fetched = len(done_ids)
        made = yf_fetcher.requests_made if yf_fetcher is not None else client.requests_made[source]
        ledger.append({"day_kst": day, "source": source, "batch_no": ledger.next_batch_no(day, source), "requests": made, "stop_reason": stop,
                       "first_target": todo[0]["series_id"] if todo else "", "last_target": done_ids[-1] if done_ids else ""})
    return AuxResult(source, day, made, stop, fetched, len(rows) - len(todo), todo[0]["series_id"] if todo else "-", done_ids[-1] if done_ids else "-")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("status", "fetch"):
        p = sub.add_parser(name)
        p.add_argument("--raw-dir", default=str(DEFAULT_RAW_DIR))
        if name == "fetch":
            p.add_argument("--source", required=True, choices=SOURCES)
            p.add_argument("--daily-cap", type=int, default=None)
            p.add_argument("--env-file", default=str(REPO_ROOT / ".env"))
    args = ap.parse_args(argv)
    raw = Path(args.raw_dir)
    if args.cmd == "status":
        for s in SOURCES:
            st = cached_state(raw, rows_for(s), s)
            print(f"{s}: {sum(st.values())} of {len(st)} cached" + ("" if all(st.values()) else " - missing " + ",".join(k for k, v in st.items() if not v)))
        return 0
    import requests  # lazy import

    key = read_env(Path(args.env_file)).get("ECOS_API_KEY", "") if args.source == "ECOS" else ""
    session = requests.Session()
    session.trust_env = False
    import logging

    logging.basicConfig(level=logging.INFO, format="%(message)s")
    for h in logging.getLogger().handlers:
        h.addFilter(SecretFilter([key] if key else []))
    try:
        res = run_fetch(args.source, raw=raw, repo_root=REPO_ROOT, session=session, clock=Clock(), ecos_key=key, cap=args.daily_cap)
    except AuxError as exc:
        print(mask(str(exc), [key]), file=sys.stderr)
        return 3
    print(f"{res.source} on {res.day}: requests={res.requests} stop={res.stop_reason} fetched={res.fetched} cached={res.cached} first={res.first} last={res.last}")
    return 0 if res.stop_reason in ("DONE", "BUDGET") else 1


if __name__ == "__main__":
    sys.exit(main())

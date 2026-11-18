"""Full-history compare, judgement and norm for the fetch targets file (MVP-04 PR 04-B). Offline: no request ever leaves.

Reads only the cached pages of the batch raw dir (the sweep's fixed monthly window, the drip's daily range, one
full-range yfinance file per symbol), so every read is a cache hit. A missing or unreadable page is a problem
for that asset, never a request.

Segments come from `fetch_targets.csv`: KIS from `kis_from`, yfinance canonical through `yf_canonical_to`
(US), one source per (asset x segment). A judgement never changes a value; it records that the fixed canonical
source was kept. No price value is written to any report.
"""
from __future__ import annotations

import csv
import io
import os
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Iterator

from batch import (
    FULL_START, BatchRefused, FetchTarget, Ledger, _acquire, _base_name, _check_raw_dir, _move_to_stale, _write_rows,
    asset_state, drip_range, is_git_worktree, kst_now, series_pages, state_dir,
)
from common import load_config, mask
from kis import Clock, KisClient, KisError, durable_sync
from pipeline import (
    BASE_DATE, BP, GAP_CODE, JUDGMENT_HEADER, TOL_WARN, _check_decimal, _prev_month, check_edges, compare_returns,
    internal_verdict, manifest_rows, manifest_rows_yf, month_window, returns_from_month_ends, sha256,
)
from yf import YfBudgetExhausted, YfFetcher

REPORT_FIRST = "1996-01"
YF_REQUIRED_FIRST = "2006-12"  # every pool asset has a 2006-12 close (plan D-6); a yfinance canonical series must reach back to it
BIG_BP = Decimal(500)
RULE_CLASSES = ("ONE_DAY", "YF_MISSING_DAY", "YF_EXTRA_DAY")
BLOCKING = ("STOP", "DIFF", "GAP", "GAP_CANONICAL", "NO_BAR")

REPORT_HEADER = ["kind", "market", "ticker", "segment", "month", "basis", "canonical", "diff_bp", "signed_diff_bp",
                 "kis_month_end", "yf_month_end", "verdict"]
JUDGMENT_V2_HEADER = JUDGMENT_HEADER + ["signed_diff_bp", "kis_month_end", "yf_month_end", "proposed_by"]
QUEUE_HEADER = ["market", "ticker", "segment", "month", "verdict", "hint", "canonical", "diff_bp", "signed_diff_bp",
                "kis_month_end", "yf_month_end", "prev_signed_bp", "next_signed_bp"]
REFETCH_HEADER = ["market", "ticker", "reason"]
NORM_DAILY_HEADER = ["market", "ticker", "trade_date", "close", "close_price", "close_raw", "source"]
NORM_DIV_HEADER = ["market", "ticker", "ex_date", "amount", "source"]
NORM_SHARES_HEADER = ["market", "ticker", "as_of", "shares", "source"]
RULE_REASON = {
    "ONE_DAY": "Opposite-sign difference in an adjacent month and the two-month product within 10 bp: one day's close differs; fixed canonical source kept",
    "YF_MISSING_DAY": "The yfinance month-end date is earlier than the KIS month-end date (yfinance lacks the last trading day); fixed canonical source kept",
    "YF_EXTRA_DAY": "The yfinance month-end date is later than the KIS month-end date (a date only yfinance has); fixed canonical source kept",
}


# -- months ---------------------------------------------------------------------------------
def next_month(m: str) -> str:
    y, mo = int(m[:4]), int(m[5:7])
    return f"{y + 1}-01" if mo == 12 else f"{y}-{mo + 1:02d}"


def months_between(a: str, b: str) -> list[str]:
    out, m = [], a
    while m <= b:
        out.append(m)
        m = next_month(m)
    return out


def report_last(base: date) -> str:
    """Last return month: the December before the base-date year (the base date itself is a price, not a month)."""
    return f"{base.year - 1}-12"


# -- one asset's cached data ---------------------------------------------------------------------
@dataclass
class AssetData:
    kis: list[tuple[str, str]] | None = None  # daily (date, close) from the drip start to the base date
    bars: list[tuple[str, str]] | None = None  # monthly bars of the sweep window (may hold a partial duplicate per month)
    yf: list[tuple[str, str, str]] | None = None  # (date, close, adj close)
    raw_close: dict[str, str] = field(default_factory=dict)  # base-date actual close


@dataclass(frozen=True)
class Seg:
    no: int
    source: str  # "KIS" | "YF"
    first: str  # first return month
    last: str


def month_ends(rows: list[tuple[str, str]], where: str) -> dict[str, tuple[str, Decimal]]:
    """{month: (last date of the month, its close)}. A close that is not a positive number is an error."""
    last: dict[str, tuple[str, str]] = {}
    for d, c in rows:
        m = d[:7]
        if m not in last or d > last[m][0]:
            last[m] = (d, c)
    return {m: (d, Decimal(_check_decimal(c, f"{where} {d}"))) for m, (d, c) in last.items()}


def bar_by_month(bars: list[tuple[str, str]], where: str) -> dict[str, tuple[str, Decimal]]:
    """One bar per month: the latest-dated one. KIS monthly pages can repeat a partial bar of the page-boundary month (issue 454)."""
    return month_ends(bars, where)


def _returns(me: dict[str, tuple[str, Decimal]], hi: str) -> dict[str, Decimal]:
    closes = {m: c for m, (_, c) in me.items()}
    return {m: r for m, r in returns_from_month_ends(closes).items() if REPORT_FIRST <= m <= hi}


def full_segments(ft: FetchTarget, kis_me: dict, yf_me: dict, last_m: str) -> list[Seg]:
    """Canonical segments of one asset, in time order. First return month = the month after the source's first month."""
    cut = ft.yf_canonical_to
    segs: list[Seg] = []
    if ft.kis_from is None:
        if yf_me:
            segs.append(Seg(1, "YF", max(REPORT_FIRST, next_month(min(yf_me))), last_m))
    elif cut is None:
        if kis_me:
            segs.append(Seg(1, "KIS", max(REPORT_FIRST, next_month(min(kis_me))), last_m))
    else:
        cm = f"{cut:%Y-%m}"
        if yf_me:
            segs.append(Seg(1, "YF", max(REPORT_FIRST, next_month(min(yf_me))), cm))
        if kis_me:
            segs.append(Seg(2, "KIS", max(REPORT_FIRST, next_month(cm), next_month(min(kis_me))), last_m))
    return [s for s in segs if s.first <= s.last]


def _seg_for(segs: list[Seg], m: str) -> Seg:
    for s in segs:
        if m <= s.last:
            return s
    return segs[-1]


def _fmt(d: Decimal) -> str:
    return format(d.quantize(Decimal("0.01")), "f")


def compare_asset(ft: FetchTarget, data: AssetData, base: date = BASE_DATE) -> list[dict]:
    """CROSS, INTERNAL and NO_OVERLAP rows of one asset. Pure: works on the data it is given."""
    last_m = report_last(base)
    us = ft.market == "US"
    basis = "TOTAL" if us else "PRICE"
    where = ft.key
    kis_me = month_ends(data.kis, f"{where} KIS") if data.kis else {}
    yf_me = month_ends([(d, adj if us else c) for d, c, adj in data.yf], f"{where} YF") if data.yf else {}
    kis_ret, yf_ret = _returns(kis_me, last_m), _returns(yf_me, last_m)
    segs = full_segments(ft, kis_me, yf_me, last_m)
    if not segs:
        raise ValueError(f"{where}: no canonical segment can be built from the cached data")
    out: list[dict] = []
    for seg in segs:
        canon_me, other_me = (kis_me, yf_me) if seg.source == "KIS" else (yf_me, kis_me)
        canon_ret, other_ret = (kis_ret, yf_ret) if seg.source == "KIS" else (yf_ret, kis_ret)
        other_from = next_month(min(other_me)) if other_me else None
        cross = 0
        for m in months_between(seg.first, seg.last):
            row = {"kind": "CROSS", "market": ft.market, "ticker": ft.ticker, "segment": seg.no, "month": m, "basis": basis,
                   "canonical": seg.source, "diff_bp": Decimal(0), "signed": None,
                   "kis_end": kis_me[m][0] if m in kis_me else "", "yf_end": yf_me[m][0] if m in yf_me else "",
                   "kis_ret": kis_ret.get(m), "yf_ret": yf_ret.get(m)}
            if m not in canon_ret:
                out.append({**row, "verdict": "GAP_CANONICAL"})
            elif other_from is None or m < other_from:
                continue  # the other source has no data yet: nothing to compare
            elif m not in other_ret:
                out.append({**row, "verdict": "GAP"})
            else:
                (r,) = compare_returns({m: kis_ret[m]}, {m: yf_ret[m]})
                out.append({**row, "diff_bp": r["diff"] * BP, "signed": (kis_ret[m] - yf_ret[m]) * BP, "verdict": r["verdict"]})
            cross += 1
        if not cross:
            out.append({"kind": "NO_OVERLAP", "market": ft.market, "ticker": ft.ticker, "segment": seg.no, "month": "",
                        "basis": basis, "canonical": seg.source, "diff_bp": Decimal(0), "signed": None, "kis_end": "",
                        "yf_end": "", "verdict": "NONE", "kis_ret": None, "yf_ret": None})
    if data.kis and data.bars is not None:
        bars = bar_by_month(data.bars, f"{where} bar")
        first_bar = min(bars) if bars else None
        for m in sorted(kis_me):
            if m > last_m:
                continue
            seg = _seg_for(segs, m)
            base_row = {"kind": "INTERNAL", "market": ft.market, "ticker": ft.ticker, "segment": seg.no, "month": m,
                        "basis": basis, "canonical": seg.source, "signed": None, "kis_end": kis_me[m][0], "yf_end": "",
                        "kis_ret": None, "yf_ret": None}
            if m not in bars:
                if first_bar is not None and m >= first_bar:
                    out.append({**base_row, "diff_bp": Decimal(0), "verdict": "NO_BAR"})
                continue
            bar, daily = bars[m][1], kis_me[m][1]
            out.append({**base_row, "diff_bp": Decimal(0) if bar == daily else abs(bar / daily - 1) * BP,
                        "verdict": internal_verdict(ft.market, bar, daily)})
    return out


def blocking(rows: list[dict]) -> list[dict]:
    return [r for r in rows if r["verdict"] in BLOCKING]


# -- judgements v2 (B9) ------------------------------------------------------------------------
def _row_key(r: dict) -> tuple[str, str, str, str]:
    return (r["market"], r["ticker"], str(r["segment"]), r["month"])


def load_judgments_v2(path: Path) -> dict[tuple[str, str, str, str], dict]:
    """Strict reader of judgments.csv (v2). Missing file = no judgements. Key = (market, ticker, segment, month)."""
    p = Path(path)
    if not p.is_file():
        return {}
    out: dict[tuple[str, str, str, str], dict] = {}
    with p.open(encoding="utf-8", newline="") as h:
        rd = csv.DictReader(h)
        if rd.fieldnames != JUDGMENT_V2_HEADER:
            raise ValueError(f"{p.name}: header must be {JUDGMENT_V2_HEADER}")
        for r in rd:
            key = _row_key(r)
            if None in r or None in r.values():
                raise ValueError(f"{p.name}: wrong column count at {key}")
            if r["decision"] != "CANONICAL" or not r["reason"].strip() or key in out:
                raise ValueError(f"{p.name}: bad judgement row {key} (decision=CANONICAL, reason required, no duplicates)")
            if r["proposed_by"] not in ("RULE", "HUMAN"):
                raise ValueError(f"{p.name}: proposed_by must be RULE or HUMAN at {key}")
            if r["proposed_by"] == "RULE" and r["reason_code"] not in RULE_CLASSES:
                raise ValueError(f"{p.name}: a RULE row must use one of {RULE_CLASSES} at {key}")
            is_gap = r["reason_code"] == GAP_CODE
            if not (is_gap == (r["diff_bp"] == "GAP") == (r["signed_diff_bp"] == "GAP")):
                raise ValueError(f"{p.name}: {GAP_CODE}, diff_bp=GAP and signed_diff_bp=GAP go together at {key}")
            out[key] = r
    return out


def _matches(r: dict, j: dict) -> bool:
    """A judgement is valid for a report row only if canonical, both differences and both month-end dates are equal."""
    if j["canonical"] != r["canonical"] or j["kis_month_end"] != r["kis_end"] or j["yf_month_end"] != r["yf_end"]:
        return False
    if r["verdict"] == "STOP":
        return (j["reason_code"] != GAP_CODE and j["diff_bp"] == _fmt(r["diff_bp"]) and j["signed_diff_bp"] == _fmt(r["signed"]))
    return j["reason_code"] == GAP_CODE and j["diff_bp"] == "GAP"


def apply_judgments_v2(rows: list[dict], judgments: dict[tuple[str, str, str, str], dict]) -> tuple[list[dict], list[str]]:
    """STOP and GAP cells with a valid judgement become JUDGED. A judgement that does not fit (changed difference,
    canonical or dates) leaves the cell open and is reported; judgements no cell used are reported too.
    GAP_CANONICAL cannot be judged: refetch the canonical source."""
    used: set[tuple[str, str, str, str]] = set()
    problems: list[str] = []
    out = []
    idx = _neighbour_index(rows)
    for r in rows:
        if r["kind"] == "CROSS" and r["verdict"] in ("STOP", "GAP"):
            key = _row_key(r)
            j = judgments.get(key)
            if j is not None:
                if _matches(r, j) and (j["proposed_by"] != "RULE" or classify(r, idx)[0] == j["reason_code"]):
                    used.add(key)
                    r = {**r, "verdict": "JUDGED"}
                else:
                    problems.append(f"stale judgement (difference, canonical or dates changed) {key}")
        out.append(r)
    problems += [f"unused judgement {k}" for k in sorted(set(judgments) - used)]
    return out, problems


def classify(r: dict, idx: dict) -> tuple[str | None, str]:
    """(rule class, human hint) of an unjudged STOP cell. Rule classes are mechanical; the rest goes to a person.

    A STEP (a same-sign neighbour above 10 bp on either side) is decided first: it is never rule-proposed.
    """
    if r["diff_bp"] > BIG_BP:
        return None, "BIG_DIFF"
    neighbours = [idx.get((r["market"], r["ticker"], r["segment"], nm)) for nm in (_prev_month(r["month"]), next_month(r["month"]))]
    neighbours = [n for n in neighbours if n is not None]
    if any(r["signed"] * n["signed"] > 0 and n["diff_bp"] > TOL_WARN * BP for n in neighbours):
        return None, "STEP"
    if r["kis_end"] != r["yf_end"]:
        return ("YF_MISSING_DAY" if r["yf_end"] < r["kis_end"] else "YF_EXTRA_DAY"), ""
    for n in neighbours:
        if r["signed"] * n["signed"] < 0:
            two_month = abs((1 + r["kis_ret"]) * (1 + n["kis_ret"]) - (1 + r["yf_ret"]) * (1 + n["yf_ret"]))
            if two_month <= TOL_WARN:
                return "ONE_DAY", ""
    return None, "UNCLASSIFIED"


def propose(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    """(RULE judgement rows for the three mechanical classes, queue rows for every other open CROSS cell)."""
    proposals, queue = _entries(rows)
    return proposals, [q for q in queue if q["hint"] not in RULE_CLASSES]


def open_queue(rows: list[dict]) -> list[dict]:
    """Every open CROSS cell (a rule class is shown as its hint when a human row blocks the proposal)."""
    return _entries(rows)[1]


def _neighbour_index(rows: list[dict]) -> dict:
    return {(r["market"], r["ticker"], r["segment"], r["month"]): r for r in rows
            if r["kind"] == "CROSS" and r["verdict"] in ("MATCH", "WARN", "STOP", "JUDGED") and r["signed"] is not None}


def _entries(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    idx = _neighbour_index(rows)
    proposals: list[dict] = []
    queue: list[dict] = []
    for r in rows:
        if r["kind"] != "CROSS" or r["verdict"] not in ("STOP", "GAP", "GAP_CANONICAL"):
            continue
        cls, hint = classify(r, idx) if r["verdict"] == "STOP" else (None, r["verdict"])
        if cls is not None:
            hint = cls
            proposals.append({"market": r["market"], "ticker": r["ticker"], "segment": str(r["segment"]), "month": r["month"],
                              "diff_bp": _fmt(r["diff_bp"]), "canonical": r["canonical"], "reason_code": cls,
                              "reason": RULE_REASON[cls], "decision": "CANONICAL", "signed_diff_bp": _fmt(r["signed"]),
                              "kis_month_end": r["kis_end"], "yf_month_end": r["yf_end"], "proposed_by": "RULE"})
        side = {}
        for tag, nm in (("prev", _prev_month(r["month"])), ("next", next_month(r["month"]))):
            n = idx.get((r["market"], r["ticker"], r["segment"], nm))
            side[tag] = _fmt(n["signed"]) if n is not None else ""
        queue.append({"market": r["market"], "ticker": r["ticker"], "segment": r["segment"], "month": r["month"],
                      "verdict": r["verdict"], "hint": hint, "canonical": r["canonical"],
                      "diff_bp": _fmt(r["diff_bp"]) if r["signed"] is not None else "", "signed_diff_bp": _fmt(r["signed"]) if r["signed"] is not None else "",
                      "kis_month_end": r["kis_end"], "yf_month_end": r["yf_end"],
                      "prev_signed_bp": side["prev"], "next_signed_bp": side["next"]})
    return proposals, queue


# -- reading the cache -----------------------------------------------------------------------------
def offline_readers(raw: Path) -> tuple[KisClient, YfFetcher]:
    """A KIS client and a yfinance fetcher that cannot send: a page that is not cached is an error, never a request."""
    cfg = load_config({"KIS_APP_KEY": "x", "KIS_APP_SECRET": "x"}, None)
    return KisClient(cfg, None, raw / "kis", offline=True), YfFetcher(raw / "yf", None, max_requests=0)


def not_done_reason(raw: Path, ft: FetchTarget, base: date) -> str:
    if ft.kis_from is None and ft.yf_symbol is None:
        return "no source in the targets file"
    if ft.yf_canonical_to is not None and ft.yf_symbol is None:
        return "yf_canonical_to is set but yf_symbol is blank"
    need = (["sweep", "drip"] if ft.kis_from is not None else []) + (["yf"] if ft.yf_symbol is not None else [])
    for kind in need:
        st = asset_state(raw, kind, ft, base)
        if st != "DONE":
            return f"{kind} {st}"
    return ""


def load_asset_data(ft: FetchTarget, client: KisClient, yf: YfFetcher, base: date) -> AssetData:
    """Cached pages only (same parameters as the batches, so every read is a cache hit); the partial-response checks run again."""
    tg = ft.as_target()
    data = AssetData()
    if ft.kis_from is not None:
        start, lenient = drip_range(ft)
        rows = client.daily_range(ft.market, ft.ticker, start, base, adjusted=ft.adjusted, excd=ft.kis_excd)
        check_edges(rows, tg, start, base, listing_month=lenient)
        data.kis = [(r["date"], r["close"]) for r in rows]
        close = check_edges(client.daily_range(ft.market, ft.ticker, base, base, adjusted=False, excd=ft.kis_excd), tg, base, base)
        data.raw_close = {r["date"]: r["close"] for r in close}
        ma, mb = month_window(FULL_START, base)
        bars = client.daily_range(ft.market, ft.ticker, ma, mb, adjusted=ft.adjusted, excd=ft.kis_excd, period="M")
        if not bars:
            raise KisError(f"{ft.key}: the monthly sweep pages hold no bars")
        data.bars = [(r["date"], r["close"]) for r in bars]
    if ft.yf_symbol is not None:
        data.yf = yf.series(ft.yf_symbol, FULL_START, base)
        if ft.kis_from is None or ft.yf_canonical_to is not None:  # yfinance is canonical somewhere: neither end may be cut short
            if data.yf[0][0][:7] > YF_REQUIRED_FIRST:
                raise KisError(f"{ft.key}: the yfinance cache starts {data.yf[0][0]}, after {YF_REQUIRED_FIRST}: front-truncated or listed too late for a yfinance segment")
            first = date.fromisoformat(data.yf[0][0])
            check_edges([{"date": d} for d, _, _ in data.yf], tg, first, base)
    return data


@dataclass
class CompareResult:
    rows: list[dict]
    problems: list[str]
    skipped: list[str]
    refetch: list[tuple[str, str, str]]


def collect(targets: list[FetchTarget], raw: Path, base: date) -> CompareResult:
    client, yf = offline_readers(raw)
    rows: list[dict] = []
    problems: list[str] = []
    skipped: list[str] = []
    for ft in sorted(targets, key=lambda t: (t.market, t.ticker)):
        why = not_done_reason(raw, ft, base)
        if why:
            skipped.append(f"{ft.key}: {why}")
            continue
        try:
            rows.extend(compare_asset(ft, load_asset_data(ft, client, yf, base), base))
        except YfBudgetExhausted:
            problems.append(f"{ft.key}: a yfinance file is missing or unreadable (offline: nothing is fetched)")
        except (KisError, ValueError) as exc:
            problems.append(f"{ft.key}: {mask(exc)}")
    if client.requests_made or yf.requests_made:
        raise AssertionError("an offline command sent a request")
    refetch = sorted({(r["market"], r["ticker"], r["verdict"]) for r in rows if r["kind"] == "INTERNAL" and r["verdict"] in ("DIFF", "NO_BAR")})
    return CompareResult(rows, problems, skipped, refetch)


# -- files -----------------------------------------------------------------------------------------
def _csv_text(header: list[str], rows: list[list[str]]) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    return buf.getvalue()


def write_rows(path: Path, header: list[str], rows: list[list[str]]) -> None:
    _write_rows(path, header, rows)


def write_report(path: Path, rows: list[dict]) -> None:
    write_rows(path, REPORT_HEADER, [[r["kind"], r["market"], r["ticker"], str(r["segment"]), r["month"], r["basis"], r["canonical"],
                                      _fmt(r["diff_bp"]), _fmt(r["signed"]) if r["signed"] is not None else "",
                                      r["kis_end"], r["yf_end"], r["verdict"]] for r in rows])


def write_judgments(path: Path, rows: list[dict]) -> None:
    ordered = sorted(rows, key=lambda r: (r["market"], r["ticker"], int(r["segment"]), r["month"]))
    write_rows(path, JUDGMENT_V2_HEADER, [[r[k] for k in JUDGMENT_V2_HEADER] for r in ordered])


def write_queue(path: Path, queue: list[dict]) -> None:
    ordered = sorted(queue, key=lambda r: (r["market"], r["ticker"], int(r["segment"]), r["month"]))
    write_rows(path, QUEUE_HEADER, [[str(r[k]) for k in QUEUE_HEADER] for r in ordered])


def summary(rows: list[dict]) -> str:
    counts: dict[str, int] = {}
    for r in rows:
        k = f"{r['kind']}:{r['verdict']}"
        counts[k] = counts.get(k, 0) + 1
    return ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))


# -- guards and commands --------------------------------------------------------------------------------
def guard(raw_dir: Path, repo_root: Path) -> Path:
    """Offline commands still write into the raw dir: same location rules as the batches, and never from a worktree."""
    if is_git_worktree(repo_root):
        raise BatchRefused("WORKTREE", "refusing to write from a git worktree: the raw cache lives in the main checkout")
    raw = _check_raw_dir(raw_dir, repo_root)
    if not (raw / "kis").is_dir() and not (raw / "yf").is_dir():
        raise BatchRefused("RAW_DIR", f"{raw} holds no kis/ or yf/ cache")
    return raw


@contextmanager
def _exclusive(repo_root: Path, day: str) -> Iterator[None]:
    """The batch lock: file moves must not race a running batch."""
    state = state_dir(repo_root)
    state.mkdir(parents=True, exist_ok=True)
    lock = state / "batch.lock"
    fd = _acquire(lock, Ledger(state / "ledger.csv"), day, "KIS")
    try:
        yield
    finally:
        os.close(fd)
        lock.unlink(missing_ok=True)


def stale_asset(raw: Path, ft: FetchTarget, day: str, base: date) -> int:
    """Move a KIS asset's pages and done markers to stale/<day>/ so the next sweep and drip fetch it whole. Returns files moved."""
    kis = raw / "kis"
    files: list[Path] = []
    if ft.kis_from is not None:
        files += series_pages(kis, ft, "M", FULL_START) + series_pages(kis, ft, "D", drip_range(ft)[0])
        files += [p for p in (kis / f"{_base_name(ft, base)}.json",) if p.is_file()]
    for kind in ("sweep", "drip"):
        m = raw / "done" / f"{kind}_{ft.market}_{ft.ticker}.json"
        if m.is_file():
            files.append(m)
    _move_to_stale(raw, files, day)
    return len(files)


def run_compare(targets: list[FetchTarget], raw_dir: Path, repo_root: Path, base: date = BASE_DATE, clock: Clock | None = None,
                refetch_diff: bool = False) -> tuple[CompareResult, list[str]]:
    """Compare from the cache, apply judgements.csv, write the report. Returns (result, judgement problems)."""
    raw = guard(raw_dir, repo_root)
    res = collect(targets, raw, base)
    judgments = load_judgments_v2(raw / "judgments.csv")
    res.rows, jprob = apply_judgments_v2(res.rows, judgments)
    write_report(raw / "compare" / "compare_report.csv", res.rows)
    write_rows(raw / "compare" / "refetch.csv", REFETCH_HEADER, [list(x) for x in res.refetch])
    if refetch_diff and res.refetch:
        day = kst_now(clock or Clock()).strftime("%Y-%m-%d")
        by_key = {(t.market, t.ticker): t for t in targets}
        with _exclusive(repo_root, day):
            for m, t, _ in res.refetch:
                stale_asset(raw, by_key[(m, t)], day, base)
    return res, jprob


def run_judge(targets: list[FetchTarget], raw_dir: Path, repo_root: Path, base: date = BASE_DATE) -> dict:
    """Compare, then write RULE judgements for the three mechanical classes into judgements.csv and the rest into human_queue.csv.
    HUMAN rows are never overwritten or deleted; stale or unused RULE rows are re-derived."""
    raw = guard(raw_dir, repo_root)
    res = collect(targets, raw, base)
    path = raw / "judgments.csv"
    existing = load_judgments_v2(path)
    applied, _ = apply_judgments_v2(res.rows, {k: j for k, j in existing.items() if j["proposed_by"] == "HUMAN"})
    kept = {k: j for k, j in existing.items() if j["proposed_by"] == "HUMAN"}
    proposals, _ = propose(applied)
    new = [p for p in proposals if (p["market"], p["ticker"], p["segment"], p["month"]) not in kept]
    final = {**kept, **{(p["market"], p["ticker"], p["segment"], p["month"]): p for p in new}}
    write_judgments(path, list(final.values()))
    rows, problems = apply_judgments_v2(res.rows, final)
    queue = open_queue(rows)
    write_queue(raw / "compare" / "human_queue.csv", queue)
    write_report(raw / "compare" / "compare_report.csv", rows)
    by_class: dict[str, int] = {}
    for p in new:
        by_class[p["reason_code"]] = by_class.get(p["reason_code"], 0) + 1
    return {"rows": rows, "problems": problems + res.problems, "skipped": res.skipped, "proposed": by_class, "queue": queue,
            "refetch": res.refetch}


# -- norm ------------------------------------------------------------------------------------------------
def asset_norm_rows(ft: FetchTarget, data: AssetData, base: date) -> list[list[str]]:
    """daily.csv rows of one asset: per segment only its canonical source, from the month before the first return month.

    Both sources can hold rows of the same date where two segments meet (the cut month): rows are told apart by `source`.
    """
    last_m = report_last(base)
    us = ft.market == "US"
    kis_me = month_ends(data.kis, ft.key) if data.kis else {}
    yf_me = month_ends([(d, adj if us else c) for d, c, adj in data.yf], ft.key) if data.yf else {}
    segs = full_segments(ft, kis_me, yf_me, last_m)
    if not segs:
        raise ValueError(f"{ft.key}: no canonical segment can be built from the cached data")
    out: list[list[str]] = []
    have: set[str] = set()
    for seg in segs:
        lo = _prev_month(seg.first)
        if seg.source == "YF":
            src = [(d, adj, c) for d, c, adj in data.yf or [] if lo <= d[:7] <= seg.last]
        else:
            src = [(d, c, "") for d, c in data.kis or [] if lo <= d[:7] and d <= base.isoformat()]
        for d, close, price in src:
            where = f"{ft.key} {d}"
            raw = data.raw_close.get(d, "") if seg.source == "KIS" else ""
            out.append([ft.market, ft.ticker, d, _check_decimal(close, where), _check_decimal(price, where) if price else "",
                        _check_decimal(raw, where) if raw else "", seg.source])
            if seg.source == "KIS":
                have.add(d)
    missing = set(data.raw_close) - have
    if missing:
        raise ValueError(f"{ft.key}: a base-date actual close exists but no canonical close row {sorted(missing)}")
    return sorted(out, key=lambda r: (r[2], r[6]))


@dataclass
class NormResult:
    ok: bool
    messages: list[str]
    hashes: dict[str, str] = field(default_factory=dict)


def _fsync_replace(staged: list[tuple[Path, Path]]) -> None:
    for tmp, final in staged:
        fd = os.open(tmp, os.O_RDONLY)
        try:
            durable_sync(fd)
        finally:
            os.close(fd)
    for tmp, final in staged:
        os.replace(tmp, final)


def run_norm(targets: list[FetchTarget], raw_dir: Path, repo_root: Path, base: date = BASE_DATE) -> NormResult:
    """Write norm/{daily,dividends,shares}.csv and MANIFEST.csv from the cache. Refuses unless every asset is DONE and the compare is clean."""
    raw = guard(raw_dir, repo_root)
    res = collect(targets, raw, base)
    rows, jprob = apply_judgments_v2(res.rows, load_judgments_v2(raw / "judgments.csv"))
    stops = blocking(rows)
    if res.skipped or res.problems or jprob or stops:
        msgs = [f"norm stopped: {len(res.skipped)} assets not done, {len(res.problems)} asset problems, {len(stops)} blocking cells, "
                f"{len(jprob)} judgement problems (run compare)"]
        return NormResult(False, msgs + [f"not done: {s}" for s in res.skipped[:10]])
    norm_dir = raw / "norm"
    norm_dir.mkdir(parents=True, exist_ok=True)
    client, yf = offline_readers(raw)
    staged: list[tuple[Path, Path]] = []
    try:
        daily_tmp = norm_dir / "daily.csv.tmp"
        with daily_tmp.open("w", encoding="utf-8", newline="") as h:
            w = csv.writer(h, lineterminator="\n")
            w.writerow(NORM_DAILY_HEADER)
            for ft in sorted(targets, key=lambda t: (t.market, t.ticker)):
                w.writerows(asset_norm_rows(ft, load_asset_data(ft, client, yf, base), base))
        staged.append((daily_tmp, norm_dir / "daily.csv"))
        div_rows: list[list[str]] = []
        for p in sorted((raw / "yf").glob("yf_div_*.csv")):
            t = p.stem.removeprefix("yf_div_")
            with p.open(encoding="utf-8", newline="") as h:
                r = csv.reader(h)
                next(r)
                div_rows += [["US", t, a, _check_decimal(b, f"{t} dividend {a}"), "YF"] for a, b in r]
        div_tmp = norm_dir / "dividends.csv.tmp"
        div_tmp.write_text(_csv_text(NORM_DIV_HEADER, sorted(div_rows)), encoding="utf-8", newline="")
        staged.append((div_tmp, norm_dir / "dividends.csv"))
        if not (norm_dir / "shares.csv").exists():
            sh_tmp = norm_dir / "shares.csv.tmp"
            sh_tmp.write_text(_csv_text(NORM_SHARES_HEADER, []), encoding="utf-8", newline="")
            staged.append((sh_tmp, norm_dir / "shares.csv"))
        man_tmp = raw / "MANIFEST.csv.tmp"
        man_tmp.write_text(_csv_text(["file", "source", "request", "rows", "sha256"],
                                     manifest_rows(raw / "kis") + manifest_rows_yf(raw / "yf")), encoding="utf-8", newline="")
        staged.append((man_tmp, raw / "MANIFEST.csv"))
    except BaseException:
        for tmp, _ in staged:
            tmp.unlink(missing_ok=True)
        (norm_dir / "daily.csv.tmp").unlink(missing_ok=True)
        raise
    if client.requests_made or yf.requests_made:
        raise AssertionError("an offline command sent a request")
    _fsync_replace(staged)
    return NormResult(True, [], {p.name: sha256(p) for p in sorted(norm_dir.glob("*.csv"))})

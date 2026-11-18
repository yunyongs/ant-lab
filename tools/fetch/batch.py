"""Incremental batch fetch (MVP-04 PR 04-A): targets file, request ledger, daily cap, whole assets only,
atomic resume, run window, worktree refusal. No DB access, no prices in any output.

The rules are enforced here, not by convention (plan section 5.3). The CLI is `fetch_batch.py`.
"""
from __future__ import annotations

import csv
import io
import json
import os
import re
import signal
import threading
from dataclasses import dataclass, field
from datetime import date, datetime, time as dtime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

from kis import Clock, KisClient, KisError, durable_sync, write_atomic
from common import KisConfig
from pipeline import BASE_DATE, Target, check_edges, month_window
from yf import EmptySeries, YfBudgetExhausted, YfFetcher, px_file_rows

KST = timezone(timedelta(hours=9))
WINDOW_OPEN = dtime(21, 0)
WINDOW_CLOSE = dtime(23, 0)
FULL_START = date(1995, 12, 1)

DEFAULT_CAP = {"KIS": 600, "YF": 50}
HARD_MAX_CAP = {"KIS": 1500, "YF": 50}
KIS_BATCH_RPS = 1
YF_BATCH_INTERVAL = 3.0
PAGE_ROWS = 100

TARGETS_HEADER = ["market", "ticker", "kis_excd", "kis_basis", "kis_from", "yf_symbol", "yf_canonical_to", "order_no"]
LEDGER_HEADER = ["day_kst", "source", "batch_no", "requests", "stop_reason", "first_target", "last_target"]
SWEEP_HEADER = ["market", "ticker", "kis_first_month", "kis_last_month", "bars", "has_2006_12"]
FAILED_RUN_LIMIT = 3  # consecutive failed assets that end the batch (a failing source, not failing assets)
CLEAN_STOPS = ("DONE", "DONE_WITH_FAILED", "BUDGET", "WINDOW_END")
WINDOW_MARGIN_SECONDS = 30

_SYMBOL = re.compile(r"^[A-Za-z0-9._-]{1,20}$")
_YF_SYMBOL = re.compile(r"^[A-Za-z0-9^._=-]{1,24}$")
_MONTH = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")
_EXCD = ("NAS", "NYS", "AMS")


class WindowClosed(KisError):
    """The 21:00-23:00 KST window (or the KST day) ended; raised before a request leaves."""


class Terminated(BaseException):
    """SIGTERM/SIGHUP turned into an exception so the ledger row is still written."""


class BatchRefused(Exception):
    """The batch did not start. Nothing was sent and no ledger row was written."""

    def __init__(self, reason: str, message: str):
        super().__init__(message)
        self.reason = reason


# -- targets file (B3) ------------------------------------------------------------------
@dataclass(frozen=True)
class FetchTarget:
    market: str
    ticker: str
    kis_excd: str | None
    kis_basis: str
    kis_from: date | None  # first day of the first month KIS has; None = no KIS data
    yf_symbol: str | None
    yf_canonical_to: date | None  # first day of the last return month whose canonical source is yfinance
    order_no: int

    @property
    def adjusted(self) -> bool:
        return self.kis_basis == "ADJUSTED"

    @property
    def key(self) -> str:
        return f"{self.market}:{self.ticker}"

    def as_target(self) -> Target:
        return Target(self.market, self.ticker, (), self.kis_excd)


def _month_first(text: str, where: str) -> date | None:
    if not text:
        return None
    if not _MONTH.match(text):
        raise ValueError(f"{where}: month must be YYYY-MM, got {text!r}")
    return date(int(text[:4]), int(text[5:7]), 1)


def load_fetch_targets(path: Path) -> list[FetchTarget]:
    """Strict reader: exact header, valid values, unique assets, order_no strictly increasing in file order."""
    out: list[FetchTarget] = []
    seen: set[tuple[str, str]] = set()
    last_order = 0
    with Path(path).open(encoding="utf-8", newline="") as h:
        rd = csv.DictReader(h)
        if rd.fieldnames != TARGETS_HEADER:
            raise ValueError(f"{Path(path).name}: header must be {TARGETS_HEADER}")
        for n, r in enumerate(rd, start=2):
            where = f"{Path(path).name} line {n}"
            if None in r or any(v is None for v in r.values()):
                raise ValueError(f"{where}: wrong column count")
            m, t = r["market"], r["ticker"]
            if m not in ("KR", "US") or not _SYMBOL.match(t):
                raise ValueError(f"{where}: bad market/ticker {m!r} {t!r}")
            if (m, t) in seen:
                raise ValueError(f"{where}: duplicate asset {m}:{t}")
            seen.add((m, t))
            excd = r["kis_excd"] or None
            if m == "US" and excd not in _EXCD:
                raise ValueError(f"{where}: US asset needs kis_excd in {_EXCD}")
            if m == "KR" and excd is not None:
                raise ValueError(f"{where}: KR asset must have blank kis_excd")
            basis = r["kis_basis"]
            if basis not in ("ADJUSTED", "RAW") or (m == "US" and basis == "RAW"):
                raise ValueError(f"{where}: kis_basis must be ADJUSTED (RAW only for KR), got {basis!r}")
            cut = _month_first(r["yf_canonical_to"], where)
            if cut is not None and m == "KR":
                raise ValueError(f"{where}: yf_canonical_to must be blank for KR")
            yf = r["yf_symbol"] or None
            if yf is not None and not _YF_SYMBOL.match(yf):
                raise ValueError(f"{where}: bad yf_symbol {yf!r}")
            try:
                order = int(r["order_no"])
            except ValueError:
                raise ValueError(f"{where}: order_no must be an integer") from None
            if order <= last_order:
                raise ValueError(f"{where}: order_no must be strictly increasing (got {order} after {last_order})")
            last_order = order
            out.append(FetchTarget(m, t, excd, basis, _month_first(r["kis_from"], where), yf, cut, order))
    return out


# -- ledger (B5) ------------------------------------------------------------------------
def _write_rows(path: Path, header: list[str], rows: list[list[str]]) -> None:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    write_atomic(path, buf.getvalue())


class Ledger:
    def __init__(self, path: Path):
        self.path = Path(path)

    def rows(self) -> list[dict[str, str]]:
        if not self.path.is_file():
            return []
        with self.path.open(encoding="utf-8", newline="") as h:
            rd = csv.DictReader(h)
            if rd.fieldnames != LEDGER_HEADER:
                raise ValueError(f"{self.path.name}: header must be {LEDGER_HEADER}")
            rows = list(rd)
        for r in rows:
            if None in r or None in r.values() or not r["requests"].isdigit() or not r["batch_no"].isdigit():
                raise ValueError(f"{self.path.name}: unreadable row {r}")
        return rows

    def requests_on(self, day: str, source: str) -> int:
        return sum(int(r["requests"]) for r in self.rows() if r["day_kst"] == day and r["source"] == source)

    def sent_batch_on(self, day: str, source: str) -> bool:
        return any(r["day_kst"] == day and r["source"] == source and int(r["requests"]) > 0 for r in self.rows())

    def next_batch_no(self, day: str, source: str) -> int:
        return 1 + sum(1 for r in self.rows() if r["day_kst"] == day and r["source"] == source)

    def append(self, row: dict[str, Any]) -> None:
        rows = [[r[k] for k in LEDGER_HEADER] for r in self.rows()]
        rows.append([str(row[k]) for k in LEDGER_HEADER])
        _write_rows(self.path, LEDGER_HEADER, rows)


class TokenLog:
    """At most one tokenP request per KST day; the day is recorded before the request is sent."""

    def __init__(self, path: Path, day: str):
        self.path, self.day = Path(path), day

    def __call__(self) -> None:
        days = []
        if self.path.is_file():
            days = [ln.strip() for ln in self.path.read_text(encoding="utf-8").splitlines()[1:] if ln.strip()]
        if self.day in days:
            raise KisError("token was already issued on this KST day — refusing a second tokenP", reason="TOKEN")
        _write_rows(self.path, ["day_kst"], [[d] for d in days + [self.day]])


# -- environment guards -----------------------------------------------------------------
def is_git_worktree(repo_root: Path) -> bool:
    """A linked worktree has a `.git` file; the main checkout has a `.git` directory."""
    return (Path(repo_root) / ".git").is_file()


def kst_now(clock: Clock) -> datetime:
    return datetime.fromtimestamp(clock.now(), KST)


def in_window(now: datetime) -> bool:
    return WINDOW_OPEN <= now.time() < WINDOW_CLOSE


# -- series estimates and file names ----------------------------------------------------
def _weekdays(a: date, b: date) -> int:
    if b < a:
        return 0
    days = (b - a).days + 1
    full, rest = divmod(days, 7)
    return full * 5 + sum(1 for i in range(rest) if (a + timedelta(days=full * 7 + i)).weekday() < 5)


def _saturdays_before(a: date, b: date, limit: date) -> int:
    end = min(b, limit - timedelta(days=1))
    if end < a:
        return 0
    return sum(1 for i in range((end - a).days + 1) if (a + timedelta(days=i)).weekday() == 5)


def pages_for(rows: int) -> int:
    """Upper bound: full pages plus one terminating page."""
    return -(-rows // PAGE_ROWS) + 1


def _months(a: date, b: date) -> int:
    return (b.year - a.year) * 12 + b.month - a.month + 1


def drip_range(ft: FetchTarget) -> tuple[date, bool]:
    """Daily start date and whether the first row is only required to fall in the start month."""
    assert ft.kis_from is not None
    start = max(FULL_START, ft.kis_from, ft.yf_canonical_to or FULL_START)
    return start, start == ft.kis_from and start > FULL_START


def estimate_daily_rows(ft: FetchTarget, start: date, end: date) -> int:
    rows = _weekdays(start, end)
    if ft.market == "KR":
        rows += _saturdays_before(start, end, date(2000, 1, 1))
    return rows


def _base_name(ft: FetchTarget, base: date) -> str:
    if ft.market == "KR":
        return f"kis_kr_{ft.ticker}_D_{base:%Y%m%d}_{base:%Y%m%d}_raw"
    return f"kis_us_{ft.ticker}_{ft.kis_excd}_g0_{base:%Y%m%d}_modp0"


def _series_glob(ft: FetchTarget, period: str, start: date) -> str:
    if ft.market == "KR":
        return f"kis_kr_{ft.ticker}_{period}_{start:%Y%m%d}_*_{'adj' if ft.adjusted else 'raw'}.json"
    return f"kis_us_{ft.ticker}_{ft.kis_excd}_g{'0' if period == 'D' else '2'}_*_modp1.json"


def _read_page(path: Path) -> dict | None:
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
        return doc if isinstance(doc.get("response"), dict) else None
    except (OSError, ValueError, AttributeError):
        return None


def series_pages(kis_dir: Path, ft: FetchTarget, period: str, start: date) -> list[Path]:
    return sorted(p for p in kis_dir.glob(_series_glob(ft, period, start)) if _read_page(p) is not None)


def _marker(raw_dir: Path, kind: str, ft: FetchTarget) -> Path:
    return raw_dir / "done" / f"{kind}_{ft.market}_{ft.ticker}.json"


def read_marker(raw_dir: Path, kind: str, ft: FetchTarget) -> dict | None:
    try:
        doc = json.loads(_marker(raw_dir, kind, ft).read_text(encoding="utf-8"))
        return doc if isinstance(doc, dict) else None
    except (OSError, ValueError):
        return None


def _write_marker(raw_dir: Path, kind: str, ft: FetchTarget, doc: dict) -> None:
    write_atomic(_marker(raw_dir, kind, ft), json.dumps(doc, sort_keys=True))


def _failed(raw_dir: Path, kind: str, ft: FetchTarget) -> Path:
    return raw_dir / "failed" / f"{kind}_{ft.market}_{ft.ticker}.json"


def read_failed(raw_dir: Path, kind: str, ft: FetchTarget) -> dict | None:
    try:
        doc = json.loads(_failed(raw_dir, kind, ft).read_text(encoding="utf-8"))
        return doc if isinstance(doc, dict) else None
    except (OSError, ValueError):
        return None


def mark_failed(raw_dir: Path, kind: str, ft: FetchTarget, day: str, reason: str, message: str) -> None:
    """Remember a deterministic failure so the asset is not tried again every night; the owner deletes the file to retry."""
    write_atomic(_failed(raw_dir, kind, ft), json.dumps({"day": day, "reason": reason, "message": message[:300]}, sort_keys=True))


def yf_path(raw_dir: Path, ft: FetchTarget, base: date = BASE_DATE) -> Path:
    return raw_dir / "yf" / f"yf_px_{ft.yf_symbol}_{FULL_START:%Y%m%d}_{base:%Y%m%d}.csv"


def asset_state(raw_dir: Path, kind: str, ft: FetchTarget, base: date = BASE_DATE) -> str:
    """DONE / FAILED / PARTIAL / PENDING, or NO_KIS / NO_YF when the targets file gives that source nothing to fetch."""
    kis_dir = raw_dir / "kis"
    if kind == "yf":
        if ft.yf_symbol is None:
            return "NO_YF"
        if (px_file_rows(yf_path(raw_dir, ft, base)) or 0) > 0:
            return "DONE"
        return "FAILED" if read_failed(raw_dir, kind, ft) is not None else "PENDING"
    if kind == "drip" and ft.kis_from is None:
        return "NO_KIS"
    if read_marker(raw_dir, kind, ft) is not None:
        return "DONE"
    if read_failed(raw_dir, kind, ft) is not None:
        return "FAILED"
    period, start = ("D", drip_range(ft)[0]) if kind == "drip" else ("M", FULL_START)
    return "PARTIAL" if series_pages(kis_dir, ft, period, start) else "PENDING"


def _stale_pages(kis_dir: Path, pages: list[Path], day: str) -> bool:
    return any((_read_page(p) or {}).get("fetched_day") != day for p in pages)


def _move_to_stale(raw_dir: Path, pages: list[Path], day: str) -> None:
    dest = raw_dir / "stale" / day
    dest.mkdir(parents=True, exist_ok=True)
    for p in pages:
        target, i = dest / p.name, 1
        while target.exists():
            target, i = dest / f"{p.name}.{i}", i + 1
        os.replace(p, target)


def estimate_requests(raw_dir: Path, kind: str, ft: FetchTarget, day: str, base: date = BASE_DATE) -> tuple[int, bool]:
    """(requests still needed at most, pages from an earlier KST day must be refetched whole)."""
    kis_dir = raw_dir / "kis"
    if kind == "drip":
        start, _ = drip_range(ft)
        period, full = "D", pages_for(estimate_daily_rows(ft, start, base))
    else:
        period, start = "M", FULL_START
        ma, mb = month_window(FULL_START, base)
        full = pages_for(_months(ma, mb))
    pages = series_pages(kis_dir, ft, period, start)
    stale = bool(pages) and _stale_pages(kis_dir, pages, day)
    need = full if stale else max(0, full - len(pages))
    if _read_page(kis_dir / f"{_base_name(ft, base)}.json") is None:
        need += 1
    return need, stale


# -- batch frame ------------------------------------------------------------------------
@dataclass
class Plan:
    source: str
    day: str
    batch_no: int
    remaining: int
    outside_window: bool
    clock: Clock
    journal: Callable[[], None] = lambda: None

    def window_open(self, ahead_seconds: float = 0.0) -> bool:
        """Same KST day and (unless the owner overrode it) inside the window, also `ahead_seconds` from now."""
        now = kst_now(self.clock)
        if now.strftime("%Y-%m-%d") != self.day:
            return False
        return self.outside_window or (in_window(now) and in_window(now + timedelta(seconds=ahead_seconds)))

    def before_request(self) -> None:
        """Checked before every request leaves: window first, then the durable journal line."""
        if not self.window_open():
            raise WindowClosed("the run window closed", reason="WINDOW_END")
        self.journal()


@dataclass
class BatchResult:
    source: str
    kind: str
    day: str
    batch_no: int
    requests: int
    stop_reason: str
    first_target: str
    last_target: str
    assets_done: int = 0
    assets_left: int = 0
    stopped_on: str = ""
    failed: list[str] = field(default_factory=list)


@dataclass
class _State:
    requests: Callable[[], int] = lambda: 0
    stop_reason: str = "DONE"
    first: str = ""
    last: str = ""
    done: int = 0
    left: int = 0
    stopped_on: str = ""
    failed: list[str] = field(default_factory=list)


def state_dir(repo_root: Path) -> Path:
    """Ledger, token log and lock live here whatever --raw-dir says, so a second raw dir cannot bypass the cap."""
    return Path(repo_root) / "data" / "raw" / "mvp-04"


def _check_raw_dir(raw_dir: Path, repo_root: Path) -> Path:
    raw = Path(raw_dir).resolve()
    data = (Path(repo_root) / "data").resolve()
    if raw != data and data not in raw.parents:
        raise BatchRefused("RAW_DIR", "raw dir must be inside the repository's data/ folder (git-ignored)")
    p0 = data / "raw" / "p0-08"
    if raw == p0 or p0 in raw.parents or (p0.exists() and any(q.exists() and os.path.samefile(q, p0) for q in (raw, *raw.parents))):
        raise BatchRefused("RAW_DIR", "the P0-08 cache is not a batch raw dir (its pages carry no fetch day and would be moved)")
    return raw


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _book_claimed(claimed: Path, ledger: Ledger) -> None:
    """Book the journal of a claimed dead lock as a CRASH ledger row, then delete it."""
    try:
        lines = claimed.read_text(encoding="utf-8").splitlines()
        day, source, _pid = lines[0].split(",")
    except (OSError, ValueError, IndexError):
        raise BatchRefused("LOCK", f"{claimed.name} is unreadable: inspect it by hand") from None
    sent = sum(1 for ln in lines[1:] if ln.strip() == "1")
    ledger.append({"day_kst": day, "source": source, "batch_no": ledger.next_batch_no(day, source), "requests": sent,
                   "stop_reason": "CRASH", "first_target": "", "last_target": ""})
    claimed.unlink(missing_ok=True)


def _claim(src: Path) -> Path | None:
    """Atomic rename to batch.lock.dead.<pid>: only one process wins and books the row."""
    dst = src.with_name(f"batch.lock.dead.{os.getpid()}")
    try:
        os.rename(src, dst)
    except FileNotFoundError:
        return None
    return dst


def _book_dead_claims(lock: Path, ledger: Ledger) -> None:
    """Claims (batch.lock.dead.<pid>) whose claimer died before booking them."""
    for old in sorted(lock.parent.glob("batch.lock.dead.*")):
        try:
            owner = int(old.name.rsplit(".", 1)[1])
        except ValueError:
            continue
        if owner != os.getpid() and not _pid_alive(owner):
            claimed = _claim(old)
            if claimed is not None:
                _book_claimed(claimed, ledger)


def _recover_dead_batch(lock: Path, ledger: Ledger) -> None:
    """A lock whose process is gone: claim it by rename, book its journal, free the lock."""
    try:
        lines = lock.read_text(encoding="utf-8").splitlines()
        day, source, pid = lines[0].split(",")
        pid_n = int(pid)
    except FileNotFoundError:
        return  # another process just recovered it
    except (OSError, ValueError, IndexError):
        raise BatchRefused("LOCK", "batch.lock is unreadable: inspect it by hand") from None
    if _pid_alive(pid_n):
        raise BatchRefused("LOCK", f"another {source} batch is running (pid {pid_n})")
    claimed = _claim(lock)
    if claimed is not None:
        _book_claimed(claimed, ledger)


def _acquire(lock: Path, ledger: Ledger, day: str, source: str) -> int:
    _book_dead_claims(lock, ledger)
    for attempt in (1, 2):
        try:
            fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_APPEND)
            os.write(fd, f"{day},{source},{os.getpid()}\n".encode())
            durable_sync(fd)
            return fd
        except FileExistsError:
            if attempt == 2:
                raise BatchRefused("LOCK", "batch.lock exists and could not be recovered") from None
            _recover_dead_batch(lock, ledger)
    raise AssertionError("unreachable")


class _FailureRun:
    """Failed assets are marked only once a later asset succeeds (or the loop ends); three in a row end the batch unmarked."""

    def __init__(self, raw: Path, kind: str, day: str, st: "_State"):
        self.raw, self.kind, self.day, self.st = raw, kind, day, st
        self.pending: list[tuple[FetchTarget, str, str]] = []

    def add(self, ft: FetchTarget, reason: str, message: str) -> bool:
        """Returns True when the batch must stop (FAILED_RUN)."""
        self.pending.append((ft, reason, message))
        self.st.left -= 1
        if len(self.pending) >= FAILED_RUN_LIMIT:
            self.st.failed += [f"{f.key}:{r}(unmarked)" for f, r, _ in self.pending]
            self.st.stop_reason, self.st.stopped_on = "FAILED_RUN", ft.key
            self.pending = []
            return True
        return False

    def flush(self) -> None:
        for ft, reason, message in self.pending:
            mark_failed(self.raw, self.kind, ft, self.day, reason, message)
            self.st.failed.append(f"{ft.key}:{reason}")
        self.pending = []


def run_batch(source: str, kind: str, *, raw_dir: Path, repo_root: Path, clock: Clock, cap: int, same_day: bool,
              outside_window: bool, body: Callable[[Plan, _State], None]) -> BatchResult:
    if is_git_worktree(repo_root):
        raise BatchRefused("WORKTREE", "refusing to send from a git worktree: the raw cache and ledger would be lost with it")
    raw = _check_raw_dir(raw_dir, repo_root)
    if not 1 <= cap <= HARD_MAX_CAP[source]:
        raise BatchRefused("CAP_FLAG", f"daily cap must be 1..{HARD_MAX_CAP[source]} for {source}, got {cap}")
    now = kst_now(clock)
    day = now.strftime("%Y-%m-%d")
    if not outside_window and not in_window(now):
        raise BatchRefused("WINDOW", f"outside the run window {WINDOW_OPEN:%H:%M}-{WINDOW_CLOSE:%H:%M} KST (now {now:%H:%M})")
    raw.mkdir(parents=True, exist_ok=True)
    state = state_dir(repo_root)
    state.mkdir(parents=True, exist_ok=True)
    lock = state / "batch.lock"
    ledger = Ledger(state / "ledger.csv")
    fd = _acquire(lock, ledger, day, source)
    old_handlers: dict[int, Any] = {}
    journaled = booked = False

    def on_signal(signum: int, frame: Any) -> None:
        raise Terminated(signal.Signals(signum).name)

    if threading.current_thread() is threading.main_thread():
        for sig in (signal.SIGTERM, signal.SIGHUP):
            old_handlers[sig] = signal.signal(sig, on_signal)
    try:
        if not same_day and ledger.sent_batch_on(day, source):
            raise BatchRefused("SAME_DAY", f"a {source} batch already sent requests on {day} (--same-day is the owner's override)")
        remaining = cap - ledger.requests_on(day, source)
        if remaining <= 0:
            raise BatchRefused("CAP_REACHED", f"{source} daily cap {cap} already used on {day}")
        plan = Plan(source, day, ledger.next_batch_no(day, source), remaining, outside_window, clock)

        def journal() -> None:  # one durable line per request, written before it is sent; a killed batch is booked from it
            nonlocal journaled
            journaled = True
            os.write(fd, b"1\n")
            durable_sync(fd)

        plan.journal = journal
        st = _State()
        try:
            body(plan, st)
        except Terminated as exc:
            st.stop_reason = str(exc)
            raise
        except BaseException:
            st.stop_reason = "CRASH"
            raise
        finally:
            ledger.append({"day_kst": day, "source": source, "batch_no": plan.batch_no, "requests": st.requests(),
                           "stop_reason": st.stop_reason, "first_target": st.first, "last_target": st.last})
            booked = True
        return BatchResult(source, kind, day, plan.batch_no, st.requests(), st.stop_reason, st.first, st.last,
                           st.done, st.left, st.stopped_on, st.failed)
    finally:
        for sig, h in old_handlers.items():
            signal.signal(sig, h)
        os.close(fd)
        if booked or not journaled:  # otherwise the journal stays for CRASH recovery at the next start
            lock.unlink(missing_ok=True)


# -- KIS batches (sweep, drip) ------------------------------------------------------------
def _check_asset(client: KisClient, ft: FetchTarget, kind: str, base: date) -> dict:
    tg = ft.as_target()
    if kind == "drip":
        start, lenient = drip_range(ft)
        rows = client.daily_range(ft.market, ft.ticker, start, base, adjusted=ft.adjusted, excd=ft.kis_excd)
        check_edges(rows, tg, start, base, listing_month=lenient)
        close = client.daily_range(ft.market, ft.ticker, base, base, adjusted=False, excd=ft.kis_excd)
        check_edges(close, tg, base, base)
        return {"start": start.isoformat(), "rows": len(rows)}
    ma, mb = month_window(FULL_START, base)
    bars = client.daily_range(ft.market, ft.ticker, ma, mb, adjusted=ft.adjusted, excd=ft.kis_excd, period="M")
    months = [r["date"][:7] for r in bars]
    if bars:
        check_edges(client.daily_range(ft.market, ft.ticker, base, base, adjusted=False, excd=ft.kis_excd), tg, base, base)
    return {"kis_first_month": months[0] if months else "", "kis_last_month": months[-1] if months else "",
            "bars": len(bars), "has_2006_12": "Y" if "2006-12" in months else "N"}


def write_sweep_result(raw_dir: Path, targets: list[FetchTarget]) -> None:
    rows = []
    for ft in targets:
        m = read_marker(raw_dir, "sweep", ft)
        if m is not None:
            rows.append([ft.market, ft.ticker, m["kis_first_month"], m["kis_last_month"], str(m["bars"]), m["has_2006_12"]])
    _write_rows(raw_dir / "sweep" / "sweep_result.csv", SWEEP_HEADER, rows)


def run_kis_batch(kind: str, targets: list[FetchTarget], *, cfg: KisConfig, session: Any, raw_dir: Path, repo_root: Path,
                  clock: Clock | None = None, cap: int = DEFAULT_CAP["KIS"], same_day: bool = False,
                  outside_window: bool = False, base: date = BASE_DATE) -> BatchResult:
    assert kind in ("sweep", "drip")
    clock = clock or Clock()
    raw = Path(raw_dir)

    def body(plan: Plan, st: _State) -> None:
        client = KisClient(cfg, session, raw / "kis", clock)
        client.max_requests = plan.remaining
        client.token_retry = False
        client.token_guard = TokenLog(state_dir(repo_root) / "token_log.csv", plan.day)
        client.stamp_day = lambda: plan.day
        client.on_send = plan.before_request
        st.requests = lambda: client.requests_made
        todo = [t for t in targets if asset_state(raw, kind, t, base) not in ("DONE", "NO_KIS", "FAILED")]
        st.left = len(todo)
        fails = _FailureRun(raw, kind, plan.day, st)
        for ft in todo:
            need, stale = estimate_requests(raw, kind, ft, plan.day, base)
            token = 0 if client._token else 1
            if not plan.window_open((need + token) / KIS_BATCH_RPS + WINDOW_MARGIN_SECONDS):
                st.stop_reason = "WINDOW_END"
                break
            if need + token > plan.remaining - client.requests_made:
                st.stop_reason, st.stopped_on = "BUDGET", ft.key
                break
            if stale:
                period, start = ("D", drip_range(ft)[0]) if kind == "drip" else ("M", FULL_START)
                _move_to_stale(raw, series_pages(raw / "kis", ft, period, start), plan.day)
            st.first = st.first or ft.key
            st.last = ft.key
            try:
                doc = _check_asset(client, ft, kind, base)
            except KisError as exc:
                if exc.reason == "ERROR":  # the answer arrived but failed a data check: mark, do not retry nightly, go on
                    print(f"failed {ft.key}: {exc}")
                    if fails.add(ft, "DATA_CHECK", str(exc)):
                        break
                    continue
                st.stop_reason, st.stopped_on = exc.reason, ft.key
                print(f"stopped on {ft.key}: {exc}")
                break
            fails.flush()
            _write_marker(raw, kind, ft, {**doc, "day": plan.day})
            st.done += 1
            st.left -= 1
        else:
            fails.flush()
            if st.failed:
                st.stop_reason = "DONE_WITH_FAILED"
        fails.flush()
        if kind == "sweep":
            write_sweep_result(raw, targets)

    return run_batch("KIS", kind, raw_dir=raw, repo_root=repo_root, clock=clock, cap=cap, same_day=same_day,
                     outside_window=outside_window, body=body)


# -- yfinance batch ------------------------------------------------------------------------
def run_yf_batch(targets: list[FetchTarget], *, raw_dir: Path, repo_root: Path, clock: Clock | None = None,
                 ticker_factory: Callable[[str], Any] | None = None, cap: int = DEFAULT_CAP["YF"],
                 same_day: bool = False, outside_window: bool = False, base: date = BASE_DATE) -> BatchResult:
    clock = clock or Clock()
    raw = Path(raw_dir)

    def body(plan: Plan, st: _State) -> None:
        yf = YfFetcher(raw / "yf", ticker_factory, sleep=clock.sleep, now=clock.now, interval=YF_BATCH_INTERVAL,
                       max_requests=plan.remaining, on_call=plan.before_request)
        st.requests = lambda: yf.requests_made
        todo = [t for t in targets if asset_state(raw, "yf", t, base) == "PENDING"]
        st.left = len(todo)
        fails = _FailureRun(raw, "yf", plan.day, st)
        for ft in todo:
            if not plan.window_open(YF_BATCH_INTERVAL + WINDOW_MARGIN_SECONDS):
                st.stop_reason = "WINDOW_END"
                break
            if yf.requests_made >= plan.remaining:
                st.stop_reason, st.stopped_on = "BUDGET", ft.key
                break
            st.first = st.first or ft.key
            st.last = ft.key
            try:
                yf.series(ft.yf_symbol, FULL_START, base)
            except WindowClosed:
                st.stop_reason, st.stopped_on = "WINDOW_END", ft.key
                break
            except YfBudgetExhausted:
                st.stop_reason, st.stopped_on = "BUDGET", ft.key
                break
            except EmptySeries as exc:  # a delisted or wrong symbol: mark it, do not call again every night, go on
                print(f"failed {ft.key}: {exc}")
                if fails.add(ft, "YF_EMPTY", str(exc)):
                    break
                continue
            except Exception as exc:  # any other failed answer ends the batch; nothing was cached
                st.stop_reason, st.stopped_on = "YF_ERROR", ft.key
                print(f"stopped on {ft.key}: {type(exc).__name__}")
                break
            fails.flush()
            st.done += 1
            st.left -= 1
        else:
            fails.flush()
            if st.failed:
                st.stop_reason = "DONE_WITH_FAILED"
        fails.flush()

    return run_batch("YF", "yf", raw_dir=raw, repo_root=repo_root, clock=clock, cap=cap, same_day=same_day,
                     outside_window=outside_window, body=body)


# -- status ---------------------------------------------------------------------------------
def batch_status(targets: list[FetchTarget], raw_dir: Path, day: str, base: date = BASE_DATE,
                 repo_root: Path | None = None) -> dict:
    raw = Path(raw_dir)
    out: dict[str, Any] = {}
    failed: list[str] = []
    for kind in ("sweep", "drip", "yf"):
        counts: dict[str, int] = {}
        for ft in targets:
            s = asset_state(raw, kind, ft, base)
            counts[s] = counts.get(s, 0) + 1
            if s == "FAILED":
                failed.append(f"{kind} {ft.key} {(read_failed(raw, kind, ft) or {}).get('reason', '')}")
        out[kind] = counts
    ledger = Ledger((state_dir(repo_root) if repo_root is not None else raw) / "ledger.csv")
    out["requests_today"] = {s: ledger.requests_on(day, s) for s in ("KIS", "YF")}
    out["failed"] = failed
    return out

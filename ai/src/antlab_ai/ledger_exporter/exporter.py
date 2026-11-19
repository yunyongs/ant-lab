"""Collect and render ledger numbers in the Prometheus text format.

Stdlib only. Nothing here writes: the files are opened read-only without following links, no
lock is taken (the mounts are read-only) and a half-written last line is ignored. A ledger that
is unset, missing, not a regular file, too large or malformed exports `antlab_ledger_readable 0`
and none of its other series, so a broken file never looks like zero spend.

Labels come from fixed sets. Free text of a ledger row (purpose, call_id, run_id, suffix of the
caller, targets) is never read into a label; model ids are checked against a pattern and capped.
"""

from __future__ import annotations

import csv
import json
import os
import re
import stat
from collections import Counter
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

KST = timezone(timedelta(hours=9))
MAX_BYTES = 16 * 1024 * 1024  # streamed, but a ledger this large is a fault, not data
MAX_MODELS = 20

# Copies of antlab_ai.openrouter.limiter; a test keeps them equal.
MINUTE_CAP = 18
HARNESS_DAILY_CAP = 40
PRODUCT_DAILY_CAP = {50: 10, 1000: 850}

CALLER_CLASSES = ("product", "harness")
OTHER = "OTHER"
STATUSES = ("OK", "UPSTREAM", "TIMEOUT", "LIMITED", "POLICY_REFUSED", "ERROR")
FETCH_SOURCES = ("KIS", "YF")
# Copies of tools/fetch/batch.py DEFAULT_CAP and HARD_MAX_CAP; a test reads that file and keeps them equal.
DEFAULT_FETCH_CAP = {"KIS": 600, "YF": 50}
HARD_MAX_FETCH_CAP = {"KIS": 1500, "YF": 50}
LEDGER_HEADER = [
    "day_kst",
    "source",
    "batch_no",
    "requests",
    "stop_reason",
    "first_target",
    "last_target",
]
OLLAMA_STOP_USD = Decimal("18")
LEDGERS = ("openrouter", "ollama", "fetch")

_MODEL = re.compile(r"[a-z0-9][a-z0-9._-]{0,40}(?:/[a-z0-9][a-z0-9._:-]{0,40})?")
_DAY = re.compile(r"\d{4}-\d\d-\d\d")


class Unreadable(Exception):
    pass


@dataclass(frozen=True)
class Config:
    openrouter_path: str = ""
    ollama_path: str = ""
    fetch_path: str = ""
    tier: int = 50
    ollama_stop_usd: Decimal = OLLAMA_STOP_USD
    fetch_caps: Mapping[str, int] = field(default_factory=lambda: dict(DEFAULT_FETCH_CAP))

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> Config:
        try:
            tier = int(env.get("ANTLAB_OBS_OPENROUTER_TIER") or "50")
        except ValueError:
            tier = 0
        if tier not in PRODUCT_DAILY_CAP:
            raise ValueError("ANTLAB_OBS_OPENROUTER_TIER must be 50 or 1000")
        caps = {}
        for source in FETCH_SOURCES:
            name = f"ANTLAB_OBS_FETCH_CAP_{source}"
            raw = env.get(name) or ""
            try:
                cap = int(raw) if raw else DEFAULT_FETCH_CAP[source]
            except ValueError:
                cap = 0
            if not 1 <= cap <= HARD_MAX_FETCH_CAP[source]:
                raise ValueError(f"{name} must be 1..{HARD_MAX_FETCH_CAP[source]} or blank")
            caps[source] = cap
        try:
            stop = Decimal(env.get("ANTLAB_OBS_OLLAMA_STOP_USD") or "18")
        except InvalidOperation:
            stop = Decimal(-1)
        if not stop.is_finite() or stop <= 0:
            raise ValueError("ANTLAB_OBS_OLLAMA_STOP_USD must be a positive number or blank")
        return cls(
            openrouter_path=env.get("LEDGER_OPENROUTER_PATH", ""),
            ollama_path=env.get("LEDGER_OLLAMA_PATH", ""),
            fetch_path=env.get("LEDGER_FETCH_PATH", ""),
            tier=tier,
            ollama_stop_usd=stop,
            fetch_caps=caps,
        )


def _lines(path: str) -> Iterator[str]:
    """Complete lines of the file, one at a time. A final line without newline is dropped (mid-append)."""
    if not path:
        raise Unreadable
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
    except OSError as exc:
        raise Unreadable from exc
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise Unreadable
        read = 0
        with os.fdopen(fd, "rb", closefd=False) as handle:
            for raw in handle:
                read += len(raw)
                if read > MAX_BYTES:
                    raise Unreadable
                if not raw.endswith(b"\n"):
                    return
                try:
                    yield raw.decode("utf-8")
                except UnicodeDecodeError as exc:
                    raise Unreadable from exc
    except OSError as exc:
        raise Unreadable from exc
    finally:
        os.close(fd)


def _json_rows(path: str) -> Iterator[dict]:
    for line in _lines(path):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as exc:
            raise Unreadable from exc
        if not isinstance(row, dict):
            raise Unreadable
        yield row


def _money(value: object) -> Decimal:
    if value is None:
        return Decimal(0)
    if isinstance(value, bool) or not isinstance(value, (str, int, float)):
        raise Unreadable
    try:
        amount = Decimal(str(value))
    except InvalidOperation as exc:
        raise Unreadable from exc
    if not amount.is_finite() or amount < 0:
        raise Unreadable
    return amount


def _count(value: object) -> int:
    if value is None:
        return 0
    if type(value) is not int or value < 0:
        raise Unreadable
    return value


def _utc(ts: object) -> datetime:
    if not isinstance(ts, str):
        raise Unreadable
    try:
        moment = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError as exc:
        raise Unreadable from exc
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def caller_class(caller: object) -> str:
    head = caller.split(":", 1)[0] if isinstance(caller, str) else ""
    return head if head in CALLER_CLASSES else OTHER


def model_label(model: object) -> str:
    return model if isinstance(model, str) and _MODEL.fullmatch(model) else OTHER


Series = list[tuple[str, dict[str, str], Decimal | int]]


def openrouter_series(path: str, now: datetime, tier: int) -> Series:
    today = now.astimezone(UTC).date()
    minute_ago = now - timedelta(seconds=60)
    attempts: Counter[tuple[str, str]] = Counter()
    outcomes: Counter[str] = Counter()
    tokens = {"in": 0, "out": 0}
    cost = Decimal(0)
    last_minute = 0
    for row in _json_rows(path):
        moment = _utc(row.get("ts"))
        if row.get("phase") == "attempt":
            if moment > minute_ago:
                last_minute += 1
            if moment.date() == today:
                attempts[(caller_class(row.get("caller")), model_label(row.get("model")))] += 1
        elif row.get("phase") == "outcome":
            if moment.date() == today:
                status = row.get("status")
                outcomes[status if status in STATUSES else OTHER] += 1
                tokens["in"] += _count(row.get("tokens_in"))
                tokens["out"] += _count(row.get("tokens_out"))
                cost += _money(row.get("cost_usd"))
        else:
            raise Unreadable
    by_model: Counter[str] = Counter()
    for (_, model), n in attempts.items():
        by_model[model] += n
    kept = {m for m, _ in sorted(by_model.items(), key=lambda kv: (-kv[1], kv[0]))[:MAX_MODELS]}
    merged: Counter[tuple[str, str]] = Counter()
    for (klass, model), n in attempts.items():
        merged[(klass, model if model in kept else OTHER)] += n
    out: Series = [
        ("antlab_openrouter_attempts_today", {"caller_class": k, "model": m}, n)
        for (k, m), n in sorted(merged.items())
    ]
    out += [
        ("antlab_openrouter_outcomes_today", {"status": s}, outcomes.get(s, 0))
        for s in (*STATUSES, OTHER)
    ]
    out += [("antlab_openrouter_tokens_today", {"direction": d}, n) for d, n in tokens.items()]
    out += [
        ("antlab_openrouter_cost_usd_today", {}, cost),
        ("antlab_openrouter_attempts_last_minute", {}, last_minute),
        ("antlab_openrouter_cap", {"name": "minute"}, MINUTE_CAP),
        ("antlab_openrouter_cap", {"name": "harness_daily"}, HARNESS_DAILY_CAP),
        ("antlab_openrouter_cap", {"name": "product_daily"}, PRODUCT_DAILY_CAP[tier]),
        ("antlab_openrouter_cap", {"name": "account_daily"}, tier),
    ]
    return out


def ollama_series(path: str, now: datetime, stop: Decimal) -> Series:
    """The writer is outside the repository; its timestamps are naive local time, read as KST."""
    today = now.astimezone(KST).date()
    total = Decimal(0)
    today_total = Decimal(0)
    for row in _json_rows(path):
        ts = row.get("ts")
        if not isinstance(ts, str):
            raise Unreadable
        try:
            parsed = datetime.fromisoformat(ts)
        except ValueError as exc:
            raise Unreadable from exc
        parsed = parsed.replace(tzinfo=KST) if parsed.tzinfo is None else parsed.astimezone(KST)
        usd = _money(row.get("usd"))
        total += usd
        if parsed.date() == today:
            today_total += usd
    return [
        ("antlab_ollama_spend_usd", {"window": "total"}, total),
        ("antlab_ollama_spend_usd", {"window": "today"}, today_total),
        ("antlab_ollama_stop_usd", {}, stop),
    ]


def fetch_series(path: str, now: datetime, caps: Mapping[str, int]) -> Series:
    today = now.astimezone(KST).date().isoformat()
    sent = dict.fromkeys(FETCH_SOURCES, 0)
    first = True
    for cells in csv.reader(_lines(path)):
        if first:
            if cells != LEDGER_HEADER:
                raise Unreadable
            first = False
            continue
        if not cells:
            continue
        if len(cells) != 7 or not _DAY.fullmatch(cells[0]) or not cells[3].isdigit():
            raise Unreadable
        if cells[0] == today and cells[1] in sent:
            sent[cells[1]] += int(cells[3])
    if first:
        raise Unreadable
    out: Series = [("antlab_fetch_requests_today", {"source": s}, sent[s]) for s in FETCH_SOURCES]
    out += [("antlab_fetch_daily_cap", {"source": s}, caps[s]) for s in FETCH_SOURCES]
    return out


def collect(config: Config, now: datetime | None = None) -> Series:
    now = now or datetime.now(UTC)
    readers: dict[str, Callable[[], Series]] = {
        "openrouter": lambda: openrouter_series(config.openrouter_path, now, config.tier),
        "ollama": lambda: ollama_series(config.ollama_path, now, config.ollama_stop_usd),
        "fetch": lambda: fetch_series(config.fetch_path, now, config.fetch_caps),
    }
    series: Series = []
    for ledger in LEDGERS:
        try:
            body = readers[ledger]()
            ok = 1
        except (Unreadable, ValueError, OverflowError):
            body, ok = [], 0
        series.append(("antlab_ledger_readable", {"ledger": ledger}, ok))
        series.extend(body)
    return series


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def render(series: Series) -> str:
    """One TYPE line per name with all its samples together, as the text format requires."""
    families: dict[str, list[str]] = {}
    for name, labels, value in series:
        label_text = ",".join(f'{k}="{_escape(v)}"' for k, v in labels.items())
        number = format(value, "f") if isinstance(value, Decimal) else str(value)
        families.setdefault(name, []).append(
            f"{name}{{{label_text}}} {number}" if label_text else f"{name} {number}"
        )
    lines: list[str] = []
    for name, samples in families.items():
        lines.append(f"# TYPE {name} gauge")
        lines.extend(samples)
    return "\n".join(lines) + "\n"


def exposition(config: Config, now: datetime | None = None) -> str:
    return render(collect(config, now))


__all__ = ["Config", "collect", "exposition", "render"]

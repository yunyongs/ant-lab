"""Batch ledger and batch lock: local files, append-only, metadata only.

Each CLI process has two rows: a `reserve` row, written and flushed before the spawn, and a `done`
row after it. The call cap of a batch is the number of its reserve rows, so a crash between the
spawn and the completion cannot hand the allowance back. A reservation without a `done` row is an
attempt that may still be running: the next start refuses until the owner reconciles it.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
from datetime import UTC, datetime
from decimal import ROUND_CEILING, Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Final
from uuid import uuid4

from ..port import SubscriptionRefusal
from .common import MODEL_ID, PATTERN_IDS, AttemptMeta
from .fs import UnsafePath, open_private_dir, open_private_file, write_all
from .usage import UsageSnapshot

BATCH_ID: Final = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
_RID: Final = re.compile(r"[0-9a-f]{32}")
_ROUTES: Final = ("CLAUDE_CLI", "CODEX_CLI")
DONE_STATUSES: Final = frozenset(
    {
        "OK",
        "UPSTREAM",
        "TIMEOUT",
        "LIMITED",
        "POLICY_REFUSED",
        "ERROR",
        # Not model outcomes: no process ran, or the owner closed an unfinished reservation.
        "ABORTED",
        "SPAWN_FAILED",
        "RECONCILED",
    }
)
UNLISTED_PATTERN: Final = "unlisted"
# A local estimate shown in probe reports only; an absurd figure is dropped rather than stored.
MAX_ESTIMATE_USD: Final = Decimal(1000)
_ESTIMATE_STEP: Final = Decimal("0.000001")
MAX_ROW_BYTES: Final = 4096
MAX_COUNT: Final = 10**9
MAX_ERROR_EVENTS: Final = 10_000


class LedgerError(Exception):
    """The ledger cannot be read or written safely. The text is a fixed code."""


def check_batch_id(batch_id: str) -> str:
    if not isinstance(batch_id, str) or not BATCH_ID.fullmatch(batch_id):
        raise ValueError("batch id must be 1 to 64 letters, digits, '-' or '_'")
    return batch_id


def estimate_text(value: Decimal | None) -> str | None:
    """The CLI's own dollar figure as a bounded decimal string, or None when missing or absurd."""
    if value is None or type(value) is not Decimal or not value.is_finite() or value < 0:
        return None
    try:
        rounded = value.quantize(_ESTIMATE_STEP, rounding=ROUND_CEILING)
    except InvalidOperation:
        return None
    if rounded > MAX_ESTIMATE_USD:
        return None
    return format(rounded, "f")


def _count(value: object) -> int | None:
    return value if type(value) is int and 0 <= value <= MAX_COUNT else None


def _validate(rows: list[dict[str, Any]]) -> None:
    """Recovery reads only well-formed events: a reservation, then at most one matching completion.

    Anything else (a legacy row, an unknown event, a missing or malformed id, an orphan or a
    repeated completion) is refused outright; the count is never rebuilt from a guess.
    """
    reserved: dict[str, str] = {}
    closed: set[str] = set()
    for row in rows:
        event, rid = row.get("ev"), row.get("rid")
        if event not in ("reserve", "done") or not isinstance(rid, str) or not _RID.fullmatch(rid):
            raise LedgerError("LEDGER_SCHEMA")
        if event == "reserve":
            batch = row.get("batch")
            if (
                rid in reserved
                or row.get("route") not in _ROUTES
                or not isinstance(batch, str)
                or not BATCH_ID.fullmatch(batch)
            ):
                raise LedgerError("LEDGER_SCHEMA")
            reserved[rid] = batch
        else:
            if rid not in reserved or rid in closed or row.get("batch") != reserved[rid]:
                raise LedgerError("LEDGER_SCHEMA")
            closed.add(rid)


class _Dir:
    """A verified directory descriptor, closed when the block ends."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self.fd = -1

    def __enter__(self) -> int:
        try:
            self.fd = open_private_dir(self._path)
        except UnsafePath:
            raise LedgerError("LEDGER_UNSAFE") from None
        return self.fd

    def __exit__(self, *exc: object) -> None:
        if self.fd >= 0:
            os.close(self.fd)


class BatchLock:
    """One owner-started batch at a time: an exclusive, non-blocking flock held until release."""

    def __init__(self, path: Path) -> None:
        self._path = Path(path)
        self._fd: int | None = None

    def __repr__(self) -> str:
        return f"BatchLock(held={self._fd is not None})"

    @property
    def held(self) -> bool:
        return self._fd is not None

    def acquire(self) -> None:
        if self._fd is not None:
            return
        try:
            with _Dir(self._path.parent) as dir_fd:
                fd = open_private_file(dir_fd, self._path.name, os.O_RDWR)
        except (LedgerError, UnsafePath):
            raise SubscriptionRefusal("LEDGER_UNSAFE") from None
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            raise SubscriptionRefusal("BATCH_RUNNING") from None
        self._fd = fd

    def release(self) -> None:
        if self._fd is not None:
            try:
                fcntl.flock(self._fd, fcntl.LOCK_UN)
            finally:
                os.close(self._fd)
                self._fd = None


class BatchLedger:
    """`calls.jsonl`: append-only rows, mode 0600, flushed to disk before a process is spawned."""

    def __init__(self, path: Path) -> None:
        self._path = Path(path)

    def __repr__(self) -> str:
        return "BatchLedger()"

    def _rows(self) -> list[dict[str, Any]]:
        with _Dir(self._path.parent) as dir_fd:
            try:
                fd = open_private_file(dir_fd, self._path.name, os.O_RDONLY)
            except UnsafePath:
                raise LedgerError("LEDGER_UNSAFE") from None
        rows: list[dict[str, Any]] = []
        with os.fdopen(fd, "rb") as handle:
            for line in handle:
                try:
                    row = json.loads(line)
                except ValueError:
                    raise LedgerError("LEDGER_UNREADABLE") from None
                if not isinstance(row, dict):
                    raise LedgerError("LEDGER_UNREADABLE")
                rows.append(row)
        _validate(rows)
        return rows

    def count(self, batch_id: str) -> int:
        """Spawns reserved for this batch id (the cap counts reservations, finished or not)."""
        check_batch_id(batch_id)
        return sum(
            1 for r in self._rows() if r.get("ev") == "reserve" and r.get("batch") == batch_id
        )

    def incomplete(self) -> list[str]:
        """Reservation ids with no completion row, in any batch: attempts that may still run."""
        rows = self._rows()
        done = {r.get("rid") for r in rows if r.get("ev") == "done"}
        return [
            r["rid"]
            for r in rows
            if r.get("ev") == "reserve" and isinstance(r.get("rid"), str) and r["rid"] not in done
        ]

    def _append(self, row: dict[str, Any]) -> None:
        data = (json.dumps(row, separators=(",", ":")) + "\n").encode("utf-8")
        if len(data) > MAX_ROW_BYTES:
            raise LedgerError("LEDGER_ROW")
        with _Dir(self._path.parent) as dir_fd:
            try:
                fd = open_private_file(dir_fd, self._path.name, os.O_WRONLY | os.O_APPEND)
            except UnsafePath:
                raise LedgerError("LEDGER_UNSAFE") from None
            try:
                fcntl.flock(fd, fcntl.LOCK_EX)
                write_all(fd, data)
                os.fsync(fd)
            except OSError:
                raise LedgerError("LEDGER_WRITE") from None
            finally:
                os.close(fd)

    def reserve(
        self,
        *,
        batch_id: str,
        route: str,
        model_id: str,
        usage: UsageSnapshot | None,
        now: datetime | None = None,
    ) -> str:
        """Write and flush the reservation of one spawn; the returned id closes it later."""
        check_batch_id(batch_id)
        if route not in _ROUTES or not MODEL_ID.fullmatch(model_id):
            raise LedgerError("LEDGER_ROW")
        rid = uuid4().hex
        self._append(
            {
                "ev": "reserve",
                "rid": rid,
                "at": (now or datetime.now(UTC)).astimezone(UTC).isoformat(),
                "batch": batch_id,
                "route": route,
                "model": model_id,
                "weekly_pct": None if usage is None else format(usage.weekly_used_pct, "f"),
                "session_pct": None if usage is None else format(usage.session_used_pct, "f"),
                "usage_as_of": None if usage is None else usage.as_of.isoformat(),
            }
        )
        return rid

    def complete(
        self,
        *,
        rid: str,
        batch_id: str,
        status: str,
        meta: AttemptMeta | None = None,
        tokens_in: int | None = None,
        tokens_out: int | None = None,
        latency_ms: int | None = None,
        now: datetime | None = None,
    ) -> None:
        check_batch_id(batch_id)
        if not isinstance(rid, str) or not _RID.fullmatch(rid) or status not in DONE_STATUSES:
            raise LedgerError("LEDGER_ROW")
        pattern = meta.pattern_id if meta is not None else None
        events = meta.error_events if meta is not None else 0
        if type(events) is not int or not 0 <= events <= MAX_ERROR_EVENTS:
            raise LedgerError("LEDGER_ROW")
        self._append(
            {
                "ev": "done",
                "rid": rid,
                "at": (now or datetime.now(UTC)).astimezone(UTC).isoformat(),
                "batch": batch_id,
                "status": status,
                "pattern": None
                if pattern is None
                else (pattern if pattern in PATTERN_IDS else UNLISTED_PATTERN),
                "tokens_in": _count(tokens_in),
                "tokens_out": _count(tokens_out),
                "latency_ms": _count(latency_ms),
                "estimate_usd": estimate_text(meta.estimate_usd) if meta is not None else None,
                "error_events": events,
            }
        )

    def reconcile(self) -> int:
        """Close every unfinished reservation as RECONCILED, under its own batch id.

        The reservations stay counted against their batch's cap.
        """
        rows = self._rows()
        done = {r.get("rid") for r in rows if r.get("ev") == "done"}
        pending = [r for r in rows if r.get("ev") == "reserve" and r["rid"] not in done]
        for row in pending:
            self.complete(rid=row["rid"], batch_id=row["batch"], status="RECONCILED")
        return len(pending)

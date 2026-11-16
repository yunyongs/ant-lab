"""File-locked append-only OpenRouter call ledger.

Every attempt is one JSON line written before the request is sent; the outcome is a second line.
Lines are never rewritten or removed. The ledger and its lock file must be regular, non-linked
files owned by the current user and closed to group and others; the directory must not be
writable by group or others. Errors name a line number only, never the line content.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import stat
import uuid
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

Guard = Callable[[list[dict], datetime], None]

KINDS = ("chat", "key_info")
STATUSES = ("OK", "UPSTREAM", "TIMEOUT", "LIMITED", "POLICY_REFUSED", "ERROR")
OUTCOME_FIELDS = (
    "http",
    "model_actual",
    "provider",
    "generation_id",
    "tokens_in",
    "tokens_out",
    "cost_usd",
    "latency_ms",
    "retry_after_s",
)
ATTEMPT_KEYS = frozenset(
    ("v", "phase", "call_id", "ts", "caller", "run_id", "model", "kind", "max_tokens", "worst_usd")
)
OUTCOME_KEYS = frozenset(("v", "phase", "call_id", "ts", "status", *OUTCOME_FIELDS))

_TS = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z")
_CALLER = re.compile(r"[a-z][a-z0-9_]{0,31}:[A-Za-z0-9._-]{1,64}")
_TEXT = re.compile(r"[\x21-\x7e]{1,256}")


class LedgerError(ValueError):
    pass


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def _ts(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _is_int(value: object, minimum: int = 0) -> bool:
    return type(value) is int and value >= minimum


def _is_text(value: object) -> bool:
    return isinstance(value, str) and _TEXT.fullmatch(value) is not None


def _is_money(value: object) -> bool:
    if not isinstance(value, str) or not _TEXT.fullmatch(value):
        return False
    try:
        amount = Decimal(value)
    except InvalidOperation:
        return False
    return amount.is_finite() and amount >= 0


def _is_ts(value: object) -> bool:
    if not isinstance(value, str) or not _TS.fullmatch(value):
        return False
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return True


def _is_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def _optional(check: Callable[[object], bool], value: object) -> bool:
    return value is None or check(value)


def _valid_attempt(row: dict) -> bool:
    return (
        set(row) == ATTEMPT_KEYS
        and _is_uuid(row["call_id"])
        and _is_ts(row["ts"])
        and isinstance(row["caller"], str)
        and _CALLER.fullmatch(row["caller"]) is not None
        and _optional(_is_text, row["run_id"])
        and _is_text(row["model"])
        and row["kind"] in KINDS
        and _optional(_is_int, row["max_tokens"])
        and _is_money(row["worst_usd"])
    )


def _valid_outcome(row: dict) -> bool:
    return (
        set(row) == OUTCOME_KEYS
        and _is_uuid(row["call_id"])
        and _is_ts(row["ts"])
        and row["status"] in STATUSES
        and _optional(lambda v: _is_int(v, 100) and v <= 599, row["http"])
        and _optional(_is_text, row["model_actual"])
        and _optional(_is_text, row["provider"])
        and _optional(_is_text, row["generation_id"])
        and _optional(_is_int, row["tokens_in"])
        and _optional(_is_int, row["tokens_out"])
        and _optional(_is_money, row["cost_usd"])
        and _is_int(row["latency_ms"])
        and _optional(_is_int, row["retry_after_s"])
    )


def _check_rows(rows: list[object]) -> list[dict]:
    """Validate shape, types, enums, UTC timestamps and attempt/outcome linkage."""
    attempts: set[str] = set()
    closed: set[str] = set()
    for number, row in enumerate(rows, 1):
        if not isinstance(row, dict) or type(row.get("v")) is not int or row["v"] != 1:
            raise LedgerError(f"invalid ledger line {number}")
        if row.get("phase") == "attempt":
            if not _valid_attempt(row) or row["call_id"] in attempts:
                raise LedgerError(f"invalid ledger line {number}")
            attempts.add(row["call_id"])
        elif row.get("phase") == "outcome":
            call_id = row.get("call_id")
            if not _valid_outcome(row) or call_id not in attempts or call_id in closed:
                raise LedgerError(f"invalid ledger line {number}")
            closed.add(call_id)
        else:
            raise LedgerError(f"invalid ledger line {number}")
    return rows  # type: ignore[return-value]


def _decode(raw: bytes) -> list[dict]:
    rows: list[object] = []
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise LedgerError("malformed ledger line 1") from exc
    for number, line in enumerate(lines, 1):
        try:
            rows.append(json.loads(line))
        except ValueError as exc:
            raise LedgerError(f"malformed ledger line {number}") from exc
    return _check_rows(rows)


def _own_private(info: os.stat_result, what: str) -> None:
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise LedgerError(f"{what} must be a regular, unlinked file")
    if info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise LedgerError(f"{what} must be owned by this user with mode 0600")


def _open_private(path: Path, create: bool, what: str) -> int | None:
    """Open without following links; verify the opened file itself (no check/use gap)."""
    flags = os.O_RDWR | os.O_APPEND | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
    if create:
        flags |= os.O_CREAT
    try:
        fd = os.open(path, flags, 0o600)
    except FileNotFoundError:
        if create:
            raise
        return None
    except OSError as exc:
        raise LedgerError(f"{what} cannot be opened safely") from exc
    try:
        info = os.fstat(fd)
        if (
            stat.S_ISREG(info.st_mode)
            and info.st_uid == os.geteuid()
            and (info.st_mode & 0o600) != 0o600
        ):
            os.fchmod(fd, 0o600)
            info = os.fstat(fd)
        _own_private(info, what)
    except BaseException:
        os.close(fd)
        raise
    return fd


def _check_directory(directory: Path) -> None:
    info = os.stat(directory)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise LedgerError("ledger directory must be owned by this user and closed to others")


def _ensure_directory(directory: Path) -> None:
    if not directory.exists():
        directory.parent.mkdir(parents=True, exist_ok=True)
        try:
            directory.mkdir(mode=0o700)
        except FileExistsError:
            pass
        else:
            os.chmod(directory, 0o700)
    _check_directory(directory)


class _Open:
    """Descriptor pair held under the lock: the ledger fd, or None when it does not exist yet."""

    def __init__(self, path: Path, fd: int | None):
        self.path, self.fd = path, fd

    def read(self) -> list[dict]:
        if self.fd is None:
            return []
        os.lseek(self.fd, 0, os.SEEK_SET)
        chunks = []
        while chunk := os.read(self.fd, 1 << 20):
            chunks.append(chunk)
        return _decode(b"".join(chunks))

    def append(self, row: dict) -> None:
        if self.fd is None:
            self.fd = _open_private(self.path, True, "ledger")
        data = (json.dumps(row, separators=(",", ":")) + "\n").encode()
        view = memoryview(data)
        while view:
            view = view[os.write(self.fd, view) :]
        os.fsync(self.fd)


@contextmanager
def _locked(path: Path, create: bool) -> Iterator[_Open | None]:
    """Hold the exclusive lock; yields None when `create` is False and nothing exists yet."""
    directory = path.parent
    if create:
        _ensure_directory(directory)
    elif not directory.exists():
        yield None
        return
    else:
        _check_directory(directory)
    lock_fd = _open_private(path.with_name(path.name + ".lock"), True, "ledger lock")
    assert lock_fd is not None
    held = None
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        held = _Open(path, _open_private(path, False, "ledger"))
        yield held
    finally:
        if held is not None and held.fd is not None:
            os.close(held.fd)
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


class Ledger:
    def __init__(self, path: str | Path, clock: Callable[[], datetime] = now_utc):
        self.path, self.clock = Path(path), clock
        if not self.path.is_absolute():
            raise ValueError("ledger path must be absolute")

    def rows(self) -> list[dict]:
        with _locked(self.path, create=False) as held:
            return [] if held is None else held.read()

    def reserve(
        self,
        caller: str,
        model: str,
        kind: str = "chat",
        max_tokens: int | None = None,
        worst_usd: str = "0",
        run_id: str | None = None,
        guard: Guard | None = None,
    ) -> str:
        """Append an attempt row.

        The clock is read once, after the lock is held; `guard` sees all rows and that same
        instant, and raises to refuse. The row is stamped with the instant the guard saw.
        """
        row = {
            "v": 1,
            "phase": "attempt",
            "call_id": str(uuid.uuid4()),
            "ts": "",
            "caller": caller,
            "run_id": run_id,
            "model": model,
            "kind": kind,
            "max_tokens": max_tokens,
            "worst_usd": worst_usd,
        }
        with _locked(self.path, create=True) as held:
            assert held is not None
            rows = held.read()
            moment = self.clock().astimezone(timezone.utc)
            row["ts"] = _ts(moment)
            if not _valid_attempt(row):
                raise LedgerError("invalid attempt fields")
            if guard is not None:
                guard(rows, moment)
            held.append(row)
        return row["call_id"]

    def outcome(self, call_id: str, status: str, **fields: object) -> None:
        unknown = set(fields) - set(OUTCOME_FIELDS)
        if unknown:
            raise TypeError("unexpected outcome fields")
        row: dict = {
            "v": 1,
            "phase": "outcome",
            "call_id": call_id,
            "ts": "",
            "status": status,
            "http": None,
            "model_actual": None,
            "provider": None,
            "generation_id": None,
            "tokens_in": None,
            "tokens_out": None,
            "cost_usd": None,
            "latency_ms": 0,
            "retry_after_s": None,
        }
        row.update(fields)
        with _locked(self.path, create=True) as held:
            assert held is not None
            rows = held.read()
            row["ts"] = _ts(self.clock())
            _check_rows([*rows, row])
            held.append(row)

"""Local append-only journal of one agent season, so a restarted runner resumes without a second application.

One JSON object per line, never rewritten. The directory is private to the owner (0700) and the
file too (0600); a second runner on the same season is refused by an exclusive file lock. The
journal is a local file, not the database: only the API writes the database.
"""

from __future__ import annotations

import fcntl
import json
import os
import stat
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Final

from antlab_ai.runner.records import check_record

VERSION: Final = 1
KINDS: Final = ("attempt", "flushed", "decided", "advanced", "pause", "resume")
PAUSE_REASONS: Final = (
    "LIMITED_DAILY",
    "CREDIT",
    "POLICY",
    "CONFIG",
    "LOCAL_CAP",
    "BUDGET",
    "NO_RESPONSE_STREAK",
    "ENGINE_ERROR",
    "USAGE_GUARD",
    "SUBSCRIPTION_REFUSED",
)
_DIR_MODE: Final = 0o700
_FILE_MODE: Final = 0o600


class JournalError(Exception):
    """The journal cannot be used. The text never holds journal content."""


class JournalCorrupt(JournalError):
    """The file holds a row the runner did not write (a torn line, a gap, a wrong shape)."""


class JournalLocked(JournalError):
    """Another runner holds this season."""


@dataclass
class TurnState:
    attempts: list[dict[str, Any]] = field(default_factory=list)
    retry_after: list[int | None] = field(default_factory=list)
    flushed: int = 0
    decided: dict[str, Any] | None = None
    advanced: bool = False


@dataclass
class JournalState:
    turns: dict[int, TurnState] = field(default_factory=dict)
    attempt_total: int = 0
    no_response_streak: int = 0
    pauses: list[tuple[int | None, str]] = field(default_factory=list)
    resumes: int = 0

    def turn(self, turn_no: int) -> TurnState:
        return self.turns.setdefault(turn_no, TurnState())

    def unflushed(self, turn_no: int) -> list[dict[str, Any]]:
        """Attempts of the turn the API does not hold yet."""
        state = self.turns.get(turn_no)
        return [] if state is None else state.attempts[state.flushed :]


def _turn_no(row: dict[str, Any]) -> int:
    value = row.get("turnNo")
    if type(value) is not int or value < 0:
        raise JournalCorrupt("a row has no valid turn number")
    return value


def _apply(state: JournalState, row: dict[str, Any]) -> None:
    """Fold one row into the state; the same code reads old rows and checks new ones."""
    if row.get("v") != VERSION or row.get("kind") not in KINDS:
        raise JournalCorrupt("a row has an unknown version or kind")
    kind = row["kind"]
    if kind == "resume":
        state.resumes += 1
        state.no_response_streak = 0
        return
    if kind == "pause":
        reason = row.get("reason")
        turn = row.get("turnNo")
        if reason not in PAUSE_REASONS or (
            turn is not None and (type(turn) is not int or turn < 0)
        ):
            raise JournalCorrupt("a pause row is malformed")
        state.pauses.append((turn, reason))
        return
    turn_state = state.turn(_turn_no(row))
    if turn_state.decided is not None and kind in ("attempt", "flushed", "decided"):
        raise JournalCorrupt("a row follows the final decision of its turn")
    if kind == "attempt":
        record = row.get("record")
        retry = row.get("retryAfter")
        if not isinstance(record, dict) or (retry is not None and type(retry) is not int):
            raise JournalCorrupt("an attempt row is malformed")
        if record.get("attemptNo") != len(turn_state.attempts) + 1:
            raise JournalCorrupt("attempt numbers do not follow one another")
        turn_state.attempts.append(record)
        turn_state.retry_after.append(retry)
        state.attempt_total += 1
    elif kind == "flushed":
        up_to = row.get("upTo")
        if type(up_to) is not int or not turn_state.flushed <= up_to <= len(turn_state.attempts):
            raise JournalCorrupt("a flushed row is outside the attempts of its turn")
        turn_state.flushed = up_to
    elif kind == "decided":
        result = row.get("result")
        if not isinstance(result, dict) or result.get("outcome") not in (
            "DECISION",
            "INVALID",
            "NO_RESPONSE",
        ):
            raise JournalCorrupt("a decided row is malformed")
        turn_state.decided = result
        turn_state.flushed = len(turn_state.attempts)
        state.no_response_streak = (
            state.no_response_streak + 1 if result["outcome"] == "NO_RESPONSE" else 0
        )
    else:  # advanced
        if turn_state.decided is None or turn_state.advanced:
            raise JournalCorrupt("an advance row has no decision to follow, or repeats")
        turn_state.advanced = True


def _check_dir(path: Path) -> None:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise JournalError("the state directory is a symbolic link or not a directory")
    if info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise JournalError("the state directory must belong to the owner and be mode 0700")


def _make_dir(path: Path) -> None:
    missing = []
    probe = path
    while not probe.exists() and not probe.is_symlink():
        missing.append(probe)
        probe = probe.parent
    for part in reversed(missing):
        part.mkdir(mode=_DIR_MODE)
        part.chmod(_DIR_MODE)


class Journal:
    """Open with ``Journal.open``; use as a context manager so the lock is released."""

    def __init__(self, fd: int, path: Path, state: JournalState) -> None:
        self._fd = fd
        self.path = path
        self.state = state

    @classmethod
    def open(cls, state_dir: Path, agent_id: int, season_id: int) -> Journal:
        for value in (agent_id, season_id):
            if type(value) is not int or value < 1:
                raise JournalError("agent and season ids must be positive integers")
        _make_dir(state_dir)
        _check_dir(state_dir)
        path = state_dir / f"agent-{agent_id}-season-{season_id}.jsonl"
        try:
            fd = os.open(
                path,
                os.O_RDWR | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW | os.O_CLOEXEC,
                _FILE_MODE,
            )
        except OSError:
            raise JournalError("the journal file cannot be opened") from None
        try:
            info = os.fstat(fd)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid != os.geteuid()
                or info.st_mode & 0o077
            ):
                raise JournalError("the journal must be a regular file of the owner, mode 0600")
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise JournalLocked("another runner holds this season") from None
            return cls(fd, path, cls._load(fd))
        except BaseException:
            os.close(fd)
            raise

    @staticmethod
    def _load(fd: int) -> JournalState:
        size = os.fstat(fd).st_size
        data = os.pread(fd, size, 0) if size else b""
        state = JournalState()
        if not data:
            return state
        if not data.endswith(b"\n"):
            raise JournalCorrupt("the last row is incomplete")
        for line in data.split(b"\n")[:-1]:
            try:
                row = json.loads(line)
            except ValueError:
                raise JournalCorrupt("a row is not JSON") from None
            if not isinstance(row, dict):
                raise JournalCorrupt("a row is not an object")
            _apply(state, row)
        return state

    def close(self) -> None:
        if self._fd >= 0:
            os.close(self._fd)  # also drops the lock
            self._fd = -1

    def __enter__(self) -> Journal:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"Journal({self.path.name!r})"

    # ---- appends --------------------------------------------------------------------------

    def _append(self, row: dict[str, Any]) -> None:
        if self._fd < 0:
            raise JournalError("the journal is closed")
        line = (
            json.dumps({"v": VERSION, **row}, ensure_ascii=False, separators=(",", ":")) + "\n"
        ).encode()
        # Fold into a scratch copy first: a row the state would refuse is never written.
        _apply(_copy(self.state), json.loads(line))
        if os.write(self._fd, line) != len(line):
            raise JournalError("the journal row was only partly written")
        os.fsync(self._fd)
        _apply(self.state, json.loads(line))

    def append_attempt(
        self, turn_no: int, record: dict[str, Any], retry_after_s: int | None = None
    ) -> None:
        check_record(record)
        self._append(
            {"kind": "attempt", "turnNo": turn_no, "record": record, "retryAfter": retry_after_s}
        )

    def append_flushed(self, turn_no: int, up_to: int) -> None:
        self._append({"kind": "flushed", "turnNo": turn_no, "upTo": up_to})

    def append_decided(self, turn_no: int, result: dict[str, Any]) -> None:
        self._append({"kind": "decided", "turnNo": turn_no, "result": result})

    def append_advanced(self, turn_no: int) -> None:
        self._append({"kind": "advanced", "turnNo": turn_no})

    def append_pause(self, reason: str, turn_no: int | None = None) -> None:
        self._append({"kind": "pause", "turnNo": turn_no, "reason": reason})

    def append_resume(self) -> None:
        self._append({"kind": "resume"})


def _copy(state: JournalState) -> JournalState:
    """Shallow-enough copy for a dry run of ``_apply`` (rows only append or flip fields)."""
    return JournalState(
        turns={
            n: TurnState(list(t.attempts), list(t.retry_after), t.flushed, t.decided, t.advanced)
            for n, t in state.turns.items()
        },
        attempt_total=state.attempt_total,
        no_response_streak=state.no_response_streak,
        pauses=list(state.pauses),
        resumes=state.resumes,
    )

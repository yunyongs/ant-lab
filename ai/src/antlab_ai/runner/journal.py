"""Local append-only journal of one agent season, so a restarted runner resumes without a second application.

One JSON object per line, never rewritten. The directory is private to the owner (0700) and the
file too (0600); a second runner on the same season is refused by an exclusive file lock. The
journal is a local file, not the database: only the API writes the database.
A memory reply that the API refused after it was accepted locally keeps its text in its row (a restart must not call
the model again); the file is private to the owner and no command prints record texts.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import stat
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Final

from antlab_ai.runner.records import check_record

VERSION: Final = 1
KINDS: Final = (
    "attempt",
    "flushed",
    "decided",
    "advanced",
    "pause",
    "resume",
    "mattempt",
    "mbase",
    "mreject",
    "mstop",
    "mdone",
)
MEMORY_STEPS: Final = ("REFLECTION", "PLAYBOOK")
MEMORY_OUTCOMES: Final = ("ACCEPTED", "DISCARDED", "UNKNOWN")
_SHA256 = re.compile(r"[0-9a-f]{64}")
_FIXED_WORD = re.compile(r"[A-Z][A-Z_]{0,39}")
_FIXED_POINTER = re.compile(r"(?:/[A-Za-z0-9_]{1,40}){0,6}(?:#s[1-9][0-9]{0,3})?|/")
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


def _steps_of(factory: Callable[[], Any]) -> Any:
    return field(default_factory=lambda: {step: factory() for step in MEMORY_STEPS})


@dataclass
class MemoryState:
    """The memory step of this season: model attempts per call kind, in order.

    ``violations`` holds fixed pointer/code pairs per attempt (never reply text). A rejected
    reply keeps its record; only its status and violations change. ``barrier`` is the number of
    attempts a stop had already handled.
    """

    base: int | None = None
    based: bool = False
    attempts: dict[str, list[dict[str, Any]]] = _steps_of(list)
    retry_after: dict[str, list[int | None]] = _steps_of(list)
    violations: dict[str, list[list[tuple[str, str]]]] = _steps_of(list)
    barrier: dict[str, int] = _steps_of(int)
    sent: int = 0
    last_stop: str | None = None
    done: dict[str, Any] | None = None


@dataclass
class JournalState:
    turns: dict[int, TurnState] = field(default_factory=dict)
    memory: MemoryState = field(default_factory=MemoryState)
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


def _pairs(value: Any) -> list[tuple[str, str]]:
    if not isinstance(value, list) or len(value) > 20:
        raise JournalCorrupt("a violation list is malformed")
    pairs = []
    for item in value:
        if (
            not isinstance(item, list)
            or len(item) != 2
            or not all(isinstance(part, str) for part in item)
            or not _FIXED_POINTER.fullmatch(item[0])
            or not re.fullmatch(r"[A-Za-z_]{1,40}", item[1])
        ):
            raise JournalCorrupt("a violation is not a fixed pointer and code")
        pairs.append((item[0], item[1]))
    return pairs


def _step(row: dict[str, Any]) -> str:
    step = row.get("step")
    if step not in MEMORY_STEPS:
        raise JournalCorrupt("a memory row has an unknown step")
    return step


def _clear(memory: MemoryState, step: str) -> None:
    memory.attempts[step] = []
    memory.retry_after[step] = []
    memory.violations[step] = []
    memory.barrier[step] = 0


def _apply_memory(state: JournalState, row: dict[str, Any]) -> None:
    memory = state.memory
    kind = row["kind"]
    if memory.done is not None:
        raise JournalCorrupt("a memory row follows the outcome")
    if kind == "mbase":
        base = row.get("base")
        if base is not None and (type(base) is not int or base < 1):
            raise JournalCorrupt("a memory base row is malformed")
        version, sha = row.get("personaVersion"), row.get("personaSha256")
        if (version is not None and (type(version) is not int or version < 1)) or (
            sha is not None and not (isinstance(sha, str) and _SHA256.fullmatch(sha))
        ):
            raise JournalCorrupt("a memory base row is malformed")
        if memory.based and memory.base != base:
            # The playbook was written on an older note; the reflection does not depend on it.
            _clear(memory, "PLAYBOOK")
        memory.base, memory.based = base, True
    elif kind == "mattempt":
        step = _step(row)
        record = row.get("record")
        retry = row.get("retryAfter")
        if (
            not isinstance(record, dict)
            or record.get("kind") != step
            or (retry is not None and type(retry) is not int)
        ):
            raise JournalCorrupt("a memory attempt row is malformed")
        if record.get("attemptNo") != len(memory.attempts[step]) + 1:
            raise JournalCorrupt("memory attempt numbers do not follow one another")
        memory.attempts[step].append(record)
        memory.retry_after[step].append(retry)
        memory.violations[step].append(_pairs(row.get("violations")))
        memory.sent += 1
    elif kind == "mreject":
        step = _step(row)
        attempts = memory.attempts[step]
        if not attempts or attempts[-1].get("status") != "OK":
            raise JournalCorrupt("a memory reject row has no accepted reply to reject")
        attempts[-1] = {**attempts[-1], "status": "INVALID"}
        memory.violations[step][-1] = _pairs(row.get("violations"))
        if step == "REFLECTION":
            _clear(memory, "PLAYBOOK")
    elif kind == "mstop":
        reason = row.get("reason")
        if not isinstance(reason, str) or not _FIXED_WORD.fullmatch(reason):
            raise JournalCorrupt("a memory stop row is malformed")
        memory.last_stop = reason
        for step in MEMORY_STEPS:
            memory.barrier[step] = len(memory.attempts[step])
    else:  # mdone
        version = row.get("version")
        if row.get("outcome") not in MEMORY_OUTCOMES or (
            version is not None and (type(version) is not int or version < 1)
        ):
            raise JournalCorrupt("a memory outcome row is malformed")
        memory.done = {"outcome": row["outcome"], "version": version}


def _apply(state: JournalState, row: dict[str, Any]) -> None:
    """Fold one row into the state; the same code reads old rows and checks new ones."""
    if row.get("v") != VERSION or row.get("kind") not in KINDS:
        raise JournalCorrupt("a row has an unknown version or kind")
    kind = row["kind"]
    if kind in ("mattempt", "mbase", "mreject", "mstop", "mdone"):
        _apply_memory(state, row)
        return
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

    # ---- memory step rows ------------------------------------------------------------------

    def append_memory_base(
        self,
        base: int | None,
        persona_version: int | None = None,
        persona_sha256: str | None = None,
    ) -> None:
        row: dict[str, Any] = {"kind": "mbase", "base": base}
        if persona_version is not None and persona_sha256 is not None:
            row["personaVersion"] = persona_version
            row["personaSha256"] = persona_sha256
        self._append(row)

    def append_memory_attempt(
        self,
        step: str,
        record: dict[str, Any],
        violations: Sequence[tuple[str, str]] = (),
        retry_after_s: int | None = None,
    ) -> None:
        check_record(record)
        self._append(
            {
                "kind": "mattempt",
                "step": step,
                "record": record,
                "violations": [list(pair) for pair in violations],
                "retryAfter": retry_after_s,
            }
        )

    def append_memory_reject(self, step: str, violations: Sequence[tuple[str, str]]) -> None:
        self._append(
            {"kind": "mreject", "step": step, "violations": [list(pair) for pair in violations]}
        )

    def append_memory_stop(self, reason: str) -> None:
        self._append({"kind": "mstop", "reason": reason})

    def append_memory_done(self, outcome: str, version: int | None) -> None:
        self._append({"kind": "mdone", "outcome": outcome, "version": version})


def _copy_memory(memory: MemoryState) -> MemoryState:
    return MemoryState(
        base=memory.base,
        based=memory.based,
        attempts={k: list(v) for k, v in memory.attempts.items()},
        retry_after={k: list(v) for k, v in memory.retry_after.items()},
        violations={k: list(v) for k, v in memory.violations.items()},
        barrier=dict(memory.barrier),
        sent=memory.sent,
        last_stop=memory.last_stop,
        done=memory.done,
    )


def _copy(state: JournalState) -> JournalState:
    """Shallow-enough copy for a dry run of ``_apply`` (rows only append or flip fields)."""
    return JournalState(
        memory=_copy_memory(state.memory),
        turns={
            n: TurnState(list(t.attempts), list(t.retry_after), t.flushed, t.decided, t.advanced)
            for n, t in state.turns.items()
        },
        attempt_total=state.attempt_total,
        no_response_streak=state.no_response_streak,
        pauses=list(state.pauses),
        resumes=state.resumes,
    )

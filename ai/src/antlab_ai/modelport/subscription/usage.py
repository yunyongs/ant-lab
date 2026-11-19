"""Subscription usage reading: a small contract the owner's meter fills, failing closed.

The meter is outside this repository. `CommandUsageReader` runs the command the owner configured
and expects one `subscription-usage/1` JSON object on stdout. Anything else is "unknown".
"""

from __future__ import annotations

import json
import os
import tempfile
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Final, Protocol

from .common import ENV_ALLOWLIST
from .process import CliResult, run_cli

SCHEMA: Final = "subscription-usage/1"
ROUTE_KEYS: Final = {"CLAUDE_CLI": "claude", "CODEX_CLI": "codex"}
COMMAND_DEADLINE_S: Final = 30
STDOUT_CAP: Final = 64 * 1024
STDERR_CAP: Final = 16 * 1024
# A meter and this process share a machine; a little skew is tolerated, a future reading is not.
FUTURE_SKEW_S: Final = 5
HUNDRED: Final = Decimal(100)

Runner = Callable[..., CliResult]


class UsageUnavailable(Exception):
    """The reading is missing, malformed, out of range or too old. The text is a fixed code."""

    def __init__(self) -> None:
        super().__init__("USAGE_UNKNOWN")


@dataclass(frozen=True)
class UsageSnapshot:
    route: str
    weekly_used_pct: Decimal
    session_used_pct: Decimal
    as_of: datetime
    weekly_resets_at: datetime | None = None
    session_resets_at: datetime | None = None

    def __post_init__(self) -> None:
        for value in (self.weekly_used_pct, self.session_used_pct):
            if type(value) is not Decimal or not value.is_finite() or not 0 <= value <= HUNDRED:
                raise ValueError("a usage percentage must be a Decimal between 0 and 100")
        if self.as_of.tzinfo is None:
            raise ValueError("as_of must be timezone-aware")


class UsageReader(Protocol):
    def read(self, route: str) -> UsageSnapshot:
        """The current snapshot, or raise UsageUnavailable."""
        ...


def _percent(value: object) -> Decimal:
    # Decimal strings only: a JSON number would have passed through a binary float.
    if not isinstance(value, str):
        raise UsageUnavailable
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        raise UsageUnavailable from None
    if not parsed.is_finite() or not 0 <= parsed <= HUNDRED:
        raise UsageUnavailable
    return parsed


def _time(value: object) -> datetime:
    if not isinstance(value, str):
        raise UsageUnavailable
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        raise UsageUnavailable from None
    if parsed.tzinfo is None:
        raise UsageUnavailable
    return parsed.astimezone(UTC)


def parse_usage(raw: bytes, route: str, *, now: datetime, max_age_s: int) -> UsageSnapshot:
    """Parse the meter's output for one route; raise UsageUnavailable for anything not clean."""
    key = ROUTE_KEYS.get(route)
    if key is None:
        raise UsageUnavailable
    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise UsageUnavailable from None
    if not isinstance(document, dict) or document.get("schema") != SCHEMA:
        raise UsageUnavailable
    entry = document.get(key)
    if not isinstance(entry, dict):
        raise UsageUnavailable
    weekly = _percent(entry.get("weekly_used_percent"))
    session = _percent(entry.get("session_used_percent"))
    as_of = _time(entry.get("as_of"))
    now_utc = now.astimezone(UTC)
    if as_of > now_utc + timedelta(seconds=FUTURE_SKEW_S):
        raise UsageUnavailable
    if now_utc - as_of > timedelta(seconds=max_age_s):
        raise UsageUnavailable
    resets: dict[str, datetime | None] = {}
    for name in ("weekly_resets_at", "session_resets_at"):
        value = entry.get(name)
        resets[name] = None if value is None else _time(value)
    return UsageSnapshot(
        route=route,
        weekly_used_pct=weekly,
        session_used_pct=session,
        as_of=as_of,
        weekly_resets_at=resets["weekly_resets_at"],
        session_resets_at=resets["session_resets_at"],
    )


def _utcnow() -> datetime:
    return datetime.now(UTC)


class CommandUsageReader:
    """Runs the owner's usage command (argv, no shell) and parses its output."""

    def __init__(
        self,
        argv: Sequence[str],
        *,
        max_age_s: int,
        clock: Callable[[], datetime] = _utcnow,
        runner: Runner = run_cli,
        source_env: Mapping[str, str] | None = None,
        cwd: Path | None = None,
    ) -> None:
        if not argv or not all(isinstance(a, str) and a for a in argv):
            raise ValueError("the usage command must be a non-empty argv")
        self._argv = tuple(argv)
        self._max_age_s = max_age_s
        self._clock = clock
        self._runner = runner
        self._source_env = source_env
        self._cwd = cwd

    def __repr__(self) -> str:
        return "CommandUsageReader()"

    def _env(self) -> dict[str, str]:
        source = os.environ if self._source_env is None else self._source_env
        return {name: source[name] for name in ENV_ALLOWLIST if name in source}

    def read(self, route: str) -> UsageSnapshot:
        try:
            result = self._runner(
                list(self._argv),
                stdin_bytes=b"",
                env=self._env(),
                cwd=self._cwd or Path(tempfile.gettempdir()),
                deadline_s=COMMAND_DEADLINE_S,
                stdout_cap=STDOUT_CAP,
                stderr_cap=STDERR_CAP,
            )
        except OSError:
            raise UsageUnavailable from None
        if result.exit_code != 0 or result.timed_out or result.over_cap or result.pipes_open:
            raise UsageUnavailable
        return parse_usage(result.stdout, route, now=self._clock(), max_age_s=self._max_age_s)

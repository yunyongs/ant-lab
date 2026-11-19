"""Pieces shared by both CLI adapters: config, the gate interface, environment, flattening, patterns."""

from __future__ import annotations

import json
import math
import os
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path
from types import MappingProxyType
from typing import Protocol

from ..port import LIMITED, POLICY_REFUSED, UPSTREAM, Message

FLAT_FORMAT = "cli-flat/1"
DEADLINE_CAP_S = 600
STDOUT_CAP = 2 * 1024 * 1024
STDERR_CAP = 64 * 1024
LAST_MESSAGE_CAP = STDOUT_CAP
MAX_PROVIDER_LEN = 100
RETRY_AFTER_MAX_S = 60

MODEL_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/\[\]-]{0,99}")
_VERSION = re.compile(r"[\x21-\x7e]{1,80}")
ENV_ALLOWLIST = ("PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR")
# Passed only when the owner set them in CliConfig.extra_env.
OPTIONAL_ENV = ("CODEX_HOME", "CLAUDE_CONFIG_DIR")


class SubscriptionGate(Protocol):
    """What an adapter asks before every spawn; it raises SubscriptionRefusal to refuse."""

    def check_attempt(self, route: str, model_id: str) -> None: ...


def _git_root(start: Path) -> Path | None:
    for parent in (start, *start.parents):
        if (parent / ".git").exists():
            return parent
    return None


def ensure_outside_repo(path: Path) -> Path:
    """Resolve symlinks and refuse a path inside the repository the code or the process runs from."""
    resolved = path.resolve()
    for origin in (Path(__file__).resolve(), Path.cwd().resolve()):
        root = _git_root(origin)
        if root is not None and resolved.is_relative_to(root):
            raise ValueError("work_root must be outside the repository")
    return resolved


def require_gate(gate: object) -> None:
    if gate is None or not callable(getattr(gate, "check_attempt", None)):
        raise ValueError("a subscription adapter needs a gate")


@dataclass(frozen=True)
class CliConfig:
    binary: str
    version: str
    work_root: Path
    extra_env: Mapping[str, str] = field(default_factory=dict)
    # Where allowlisted names are copied from; the process environment unless a test says otherwise.
    source_env: Mapping[str, str] | None = None

    def __post_init__(self) -> None:
        if not os.path.isabs(self.binary):
            raise ValueError("binary must be an absolute path")
        if not _VERSION.fullmatch(self.version):
            raise ValueError("version must be short printable text")
        if not set(self.extra_env) <= set(OPTIONAL_ENV):
            raise ValueError("extra_env may only hold the CLI config-directory names")
        # Copies, so the caller's dicts cannot change what the child receives later.
        object.__setattr__(self, "extra_env", MappingProxyType(dict(self.extra_env)))
        if self.source_env is not None:
            object.__setattr__(self, "source_env", MappingProxyType(dict(self.source_env)))
        object.__setattr__(self, "work_root", Path(self.work_root))
        ensure_outside_repo(self.work_root)

    def __repr__(self) -> str:
        return f"CliConfig(binary={self.binary!r}, version={self.version!r})"


def build_env(config: CliConfig) -> dict[str, str]:
    """Only allowlisted names reach the CLI; nothing is inherited wholesale."""
    if not set(config.extra_env) <= set(OPTIONAL_ENV):
        raise ValueError("extra_env may only hold the CLI config-directory names")
    source = os.environ if config.source_env is None else config.source_env
    env = {name: source[name] for name in ENV_ALLOWLIST if name in source}
    env.update(config.extra_env)
    env["NO_COLOR"] = "1"
    env["TERM"] = "dumb"
    return env


def deadline_seconds(timeout_ms: int) -> int:
    return min(math.ceil(timeout_ms / 1000), DEADLINE_CAP_S)


def check_model_id(model_id: str) -> None:
    # The first character rules out anything that could be read as a flag.
    if not MODEL_ID.fullmatch(model_id):
        raise ValueError("model is not allowed")


def provider_text(prefix: str, version: str) -> str:
    return f"{prefix}/{version}"[:MAX_PROVIDER_LEN]


def _block(message: Message) -> str:
    return f"### {message.role.upper()}\n{message.content}\n"


def flatten(messages: Sequence[Message], *, system_flag: bool) -> tuple[str | None, str]:
    """(system text for --system-prompt or None, stdin text); format cli-flat/1."""
    system: str | None = None
    rest: list[Message] = []
    for message in messages:
        if system_flag and system is None and message.role == "system":
            system = message.content
        else:
            rest.append(message)
    return system, "".join(_block(m) for m in rest)


def request_text(route: str, flags: Sequence[str], system: str | None, stdin_text: str) -> str:
    """The stored request: exactly what was sent, with no binary path, work directory or environment."""
    return json.dumps(
        {
            "format": FLAT_FORMAT,
            "route": route,
            "argv": list(flags),
            "system": system,
            "stdin": stdin_text,
        }
    )


@dataclass(frozen=True)
class Mapped:
    """Pure result of a mapper; the adapter adds provider, latency and request text."""

    status: str
    http_status: int | None = None
    text: str | None = None
    model_actual: str | None = None
    tokens_in: int | None = None
    tokens_out: int | None = None
    retry_after_s: int | None = None
    pattern_id: str | None = None
    estimate_usd: Decimal | None = None
    # Codex `error` events seen before a completed turn; a count only, never their text.
    error_events: int = 0

    def __repr__(self) -> str:
        # The reply text and model name are never shown.
        size = None if self.text is None else len(self.text)
        return (
            f"Mapped(status={self.status!r}, pattern_id={self.pattern_id!r}, "
            f"text_len={size}, http_status={self.http_status})"
        )


@dataclass(frozen=True)
class AttemptMeta:
    """What PR C may keep about an attempt (ledger metadata); it never holds reply or prompt text."""

    status: str
    pattern_id: str | None
    estimate_usd: Decimal | None
    error_events: int

    @classmethod
    def of(cls, mapped: Mapped) -> AttemptMeta:
        return cls(mapped.status, mapped.pattern_id, mapped.estimate_usd, mapped.error_events)


# (id, status or None for a configuration error, regex on lower-cased text). First match wins,
# so explicit limit, policy and configuration wording outranks the broad transient wording.
_PATTERNS: tuple[tuple[str, str | None, re.Pattern[str]], ...] = tuple(
    (pid, status, re.compile(regex))
    for pid, status, regex in (
        (
            "limit_usage",
            LIMITED,
            r"usage limit|message limit|limit (reached|exceeded)|(reached|hit|exceeded) your .{0,40}limit",
        ),
        (
            "limit_quota",
            LIMITED,
            r"quota exceeded|exceeded your .{0,20}quota|insufficient_quota|out of messages",
        ),
        ("limit_rate", LIMITED, r"rate.?limit|too many requests|\b429\b"),
        ("limit_retry", LIMITED, r"try again (in|at|after) "),
        (
            "policy",
            POLICY_REFUSED,
            r"content[ _]policy|usage polic(y|ies)|safety (system|filter)",
        ),
        (
            "auth",
            None,
            r"not logged in|please log ?in|unauthorized|\b40[13]\b|invalid (api key|credentials|token)|token refresh|expired",
        ),
        (
            "model",
            None,
            r"unknown model|invalid model|model .{0,60}(not found|not supported)|does not exist",
        ),
        ("usage", None, r"unexpected argument|unrecognized|invalid (value|option)|usage:"),
        ("upstream_overloaded", UPSTREAM, r"overloaded|service unavailable|bad gateway"),
        ("upstream_5xx", UPSTREAM, r"\b50[0-4]\b|server error"),
        (
            "upstream_network",
            UPSTREAM,
            r"stream disconnected|connection (reset|refused|closed)|network error|econn|"
            r"temporarily unavailable",
        ),
    )
)

_RETRY_AFTER = re.compile(r"(?:retry[- ]after[: ]+|try again in )(\d{1,4})\s*s(?:ec|econds?)?\b")


def classify(text: str) -> tuple[str | None, str | None]:
    """(pattern id, status) for an error text; (None, None) when nothing is recognised."""
    lowered = text.lower()
    for pid, status, regex in _PATTERNS:
        if regex.search(lowered):
            return pid, status
    return None, None


def retry_after(text: str) -> int | None:
    match = _RETRY_AFTER.search(text.lower())
    if match is None:
        return None
    seconds = int(match.group(1))
    return seconds if 0 < seconds <= RETRY_AFTER_MAX_S else None


def count(value: object) -> int | None:
    return value if type(value) is int and value >= 0 else None


def api_status(value: object) -> int | None:
    """An upstream HTTP status worth recording; 402 is never reported (the runner reads it as credit)."""
    if type(value) is int and 100 <= value <= 599 and value != 402:
        return value
    return None

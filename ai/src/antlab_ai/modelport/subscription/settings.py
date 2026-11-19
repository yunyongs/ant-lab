"""Subscription settings read from the environment only; the route stays off unless every name is set."""

from __future__ import annotations

import os
import shlex
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

from .common import ensure_outside_repo

ENABLED = "ANTLAB_MODEL_SUBSCRIPTION_ENABLED"
ROUTES = "ANTLAB_MODEL_SUBSCRIPTION_ROUTES"
OWNER_MEMBER_ID = "ANTLAB_SUBSCRIPTION_OWNER_MEMBER_ID"
USAGE_CMD = "ANTLAB_SUBSCRIPTION_USAGE_CMD"
MAX_CALLS = "ANTLAB_SUBSCRIPTION_MAX_CALLS_PER_BATCH"
USAGE_MAX_AGE = "ANTLAB_SUBSCRIPTION_USAGE_MAX_AGE_S"
WORK_ROOT = "ANTLAB_SUBSCRIPTION_WORK_ROOT"
CODEX_HOME = "ANTLAB_SUBSCRIPTION_CODEX_HOME"
CLAUDE_CONFIG_DIR = "ANTLAB_SUBSCRIPTION_CLAUDE_CONFIG_DIR"

SETTING_NAMES = (
    ENABLED,
    ROUTES,
    OWNER_MEMBER_ID,
    USAGE_CMD,
    MAX_CALLS,
    USAGE_MAX_AGE,
    WORK_ROOT,
    CODEX_HOME,
    CLAUDE_CONFIG_DIR,
)

SUBSCRIPTION_ROUTES = ("CLAUDE_CLI", "CODEX_CLI")
DEFAULT_MAX_CALLS = 15
HARD_MAX_CALLS = 60
DEFAULT_USAGE_MAX_AGE_S = 900
MAX_USAGE_MAX_AGE_S = 86_400
_MAX_MEMBER_ID = 2**63 - 1


class SubscriptionSettingsError(ValueError):
    """A subscription setting is unusable. The text names the setting and never holds a value."""


def default_work_root() -> Path:
    return Path.home() / ".local" / "state" / "antlab" / "subscription" / "work"


@dataclass(frozen=True, repr=False)
class SubscriptionSettings:
    enabled: bool = False
    routes: frozenset[str] = frozenset()
    owner_member_id: int | None = None
    usage_cmd: tuple[str, ...] = ()
    max_calls: int = DEFAULT_MAX_CALLS
    usage_max_age_s: int = DEFAULT_USAGE_MAX_AGE_S
    work_root: Path = field(default_factory=default_work_root)
    codex_home: str | None = None
    claude_config_dir: str | None = None

    def __repr__(self) -> str:
        return (
            f"SubscriptionSettings(enabled={self.enabled}, routes={sorted(self.routes)}, "
            f"max_calls={self.max_calls})"
        )

    def is_on(self, route: str) -> bool:
        return self.enabled and route in self.routes

    def extra_env(self, route: str) -> dict[str, str]:
        """The two config-directory names a CLI may receive, only when the owner set them."""
        if route == "CODEX_CLI" and self.codex_home is not None:
            return {"CODEX_HOME": self.codex_home}
        if route == "CLAUDE_CLI" and self.claude_config_dir is not None:
            return {"CLAUDE_CONFIG_DIR": self.claude_config_dir}
        return {}

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> SubscriptionSettings:
        env = os.environ if environ is None else environ

        def get(name: str) -> str | None:
            value = env.get(name)
            return value.strip() if value is not None and value.strip() else None

        raw_enabled = (get(ENABLED) or "false").lower()
        if raw_enabled not in ("true", "false"):
            raise SubscriptionSettingsError(f"{ENABLED} must be true or false")
        enabled = raw_enabled == "true"

        routes: set[str] = set()
        for part in (get(ROUTES) or "").split(","):
            name = part.strip()
            if not name:
                continue
            if name not in SUBSCRIPTION_ROUTES:
                raise SubscriptionSettingsError(f"{ROUTES} holds an unknown route")
            routes.add(name)

        max_calls = _integer(get(MAX_CALLS), MAX_CALLS, DEFAULT_MAX_CALLS, 1, HARD_MAX_CALLS)
        max_age = _integer(
            get(USAGE_MAX_AGE), USAGE_MAX_AGE, DEFAULT_USAGE_MAX_AGE_S, 1, MAX_USAGE_MAX_AGE_S
        )
        owner = _integer(get(OWNER_MEMBER_ID), OWNER_MEMBER_ID, None, 1, _MAX_MEMBER_ID)

        usage_cmd: tuple[str, ...] = ()
        raw_cmd = get(USAGE_CMD)
        if raw_cmd is not None:
            try:
                usage_cmd = tuple(shlex.split(raw_cmd))
            except ValueError:
                raise SubscriptionSettingsError(f"{USAGE_CMD} cannot be split") from None

        work_raw = get(WORK_ROOT)
        work_root = Path(work_raw).expanduser() if work_raw else default_work_root()
        if not work_root.is_absolute():
            raise SubscriptionSettingsError(f"{WORK_ROOT} must be an absolute path")
        try:
            ensure_outside_repo(work_root)
        except ValueError:
            raise SubscriptionSettingsError(f"{WORK_ROOT} must be outside the repository") from None

        for name, value in (
            (CODEX_HOME, get(CODEX_HOME)),
            (CLAUDE_CONFIG_DIR, get(CLAUDE_CONFIG_DIR)),
        ):
            if value is not None and not Path(value).is_absolute():
                raise SubscriptionSettingsError(f"{name} must be an absolute path")

        if enabled and routes:
            if owner is None:
                raise SubscriptionSettingsError(f"{OWNER_MEMBER_ID} is required")
            if not usage_cmd:
                raise SubscriptionSettingsError(f"{USAGE_CMD} is required")
        return cls(
            enabled=enabled,
            routes=frozenset(routes),
            owner_member_id=owner,
            usage_cmd=usage_cmd,
            max_calls=max_calls,
            usage_max_age_s=max_age,
            work_root=work_root,
            codex_home=get(CODEX_HOME),
            claude_config_dir=get(CLAUDE_CONFIG_DIR),
        )


def _integer(raw: str | None, name: str, default: int | None, low: int, high: int) -> int | None:
    if raw is None:
        return default
    if not raw.isascii() or not raw.isdigit():
        raise SubscriptionSettingsError(f"{name} must be a whole number")
    value = int(raw)
    if not low <= value <= high:
        raise SubscriptionSettingsError(f"{name} must be between {low} and {high}")
    return value

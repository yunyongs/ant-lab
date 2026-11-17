"""Runner settings read from the environment; only the names below are ever read."""

from __future__ import annotations

import os
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

API_URL = "ANTLAB_API_URL"
RUNNER_TOKEN = "ANTLAB_AGENT_RUNNER_TOKEN"
STATE_DIR = "ANTLAB_RUNNER_STATE_DIR"

# Same minimum as the API side (AgentRunnerProperties.MIN_TOKEN_LENGTH).
MIN_TOKEN_LENGTH = 32
DEFAULT_STATE_DIR = "data/ai/runner"

# Printable ASCII only: a token with a space or control character could break the header line.
_TOKEN = re.compile(rf"[\x21-\x7e]{{{MIN_TOKEN_LENGTH},512}}")


class SettingsError(ValueError):
    """A required runner setting is missing or unusable. The text never holds a value."""


def validate_api_url(raw: str) -> str:
    """Return the base URL without a trailing slash, or raise SettingsError."""
    try:
        parts = urlsplit(raw)
        host = parts.hostname
        parts.port  # noqa: B018 - raises ValueError for a malformed port
    except ValueError:
        raise SettingsError(f"{API_URL} is not a valid URL") from None
    if parts.scheme not in ("http", "https") or not host:
        raise SettingsError(f"{API_URL} must be an http or https URL")
    if parts.username is not None or parts.password is not None:
        raise SettingsError(f"{API_URL} must not carry credentials")
    if parts.query or parts.fragment:
        raise SettingsError(f"{API_URL} must not carry a query or fragment")
    return raw.rstrip("/")


@dataclass(frozen=True, repr=False)
class RunnerSettings:
    api_url: str
    runner_token: str
    state_dir: Path

    def __post_init__(self) -> None:
        validate_api_url(self.api_url)
        if not isinstance(self.runner_token, str) or not _TOKEN.fullmatch(self.runner_token):
            raise SettingsError(
                f"{RUNNER_TOKEN} must be {MIN_TOKEN_LENGTH} or more printable characters"
            )

    def __repr__(self) -> str:
        return f"RunnerSettings(api_url={self.api_url!r}, runner_token=set, state_dir={str(self.state_dir)!r})"

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> RunnerSettings:
        env = os.environ if environ is None else environ

        def get(name: str) -> str | None:
            value = env.get(name)
            return value.strip() if value is not None and value.strip() else None

        url = get(API_URL)
        if url is None:
            raise SettingsError(f"{API_URL} is required")
        token = get(RUNNER_TOKEN)
        if token is None:
            raise SettingsError(f"{RUNNER_TOKEN} is required")
        return cls(validate_api_url(url), token, Path(get(STATE_DIR) or DEFAULT_STATE_DIR))

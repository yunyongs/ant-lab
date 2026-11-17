"""Model-port settings read from the environment; only the names below are ever read."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import urlsplit

OLLAMA_BASE_URL = "OLLAMA_BASE_URL"
OLLAMA_API_KEY = "OLLAMA_API_KEY"
STRUCTURED_OUTPUT = "ANTLAB_MODEL_STRUCTURED_OUTPUT"

LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost", "host.docker.internal"})


def validate_base_url(raw: str) -> str:
    """Return the URL without a trailing slash, or raise ValueError (no credentials in text)."""
    try:
        parts = urlsplit(raw)
        host = parts.hostname
        parts.port  # noqa: B018 - raises ValueError for a malformed port
    except ValueError:
        raise ValueError("OLLAMA_BASE_URL is not a valid URL") from None
    if parts.scheme not in ("http", "https") or not host:
        raise ValueError("OLLAMA_BASE_URL must be an http or https URL")
    if parts.username is not None or parts.password is not None:
        raise ValueError("OLLAMA_BASE_URL must not carry credentials")
    if parts.query or parts.fragment:
        raise ValueError("OLLAMA_BASE_URL must not carry a query or fragment")
    return raw.rstrip("/")


def is_loopback(base_url: str) -> bool:
    return (urlsplit(base_url).hostname or "").lower() in LOOPBACK_HOSTS


@dataclass(frozen=True, repr=False)
class ModelPortSettings:
    ollama_base_url: str | None = None
    ollama_api_key: str | None = None
    structured_output: bool = False

    def __repr__(self) -> str:
        key = "set" if self.ollama_api_key else "unset"
        return (
            f"ModelPortSettings(ollama_base_url={self.ollama_base_url!r}, "
            f"ollama_api_key={key}, structured_output={self.structured_output})"
        )

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> ModelPortSettings:
        env = os.environ if environ is None else environ

        def get(name: str) -> str | None:
            value = env.get(name)
            return value.strip() if value is not None and value.strip() else None

        base = get(OLLAMA_BASE_URL)
        raw_flag = (get(STRUCTURED_OUTPUT) or "false").lower()
        if raw_flag not in ("true", "false"):
            raise ValueError("ANTLAB_MODEL_STRUCTURED_OUTPUT must be true or false")
        return cls(
            None if base is None else validate_base_url(base),
            get(OLLAMA_API_KEY),
            raw_flag == "true",
        )

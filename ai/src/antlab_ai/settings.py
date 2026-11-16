"""Environment-backed settings with secret-safe representations."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _dotenv(path: str | None) -> dict[str, str]:
    if not path:
        return {}
    values: dict[str, str] = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip("\"'")
    return values


@dataclass(frozen=True, repr=False)
class Settings:
    api_key: str | None
    ledger_path: Path | None
    tier: int = 50

    def __repr__(self) -> str:
        key = "set" if self.api_key else "unset"
        return f"Settings(api_key={key}, ledger_path={self.ledger_path!r}, tier={self.tier})"

    @classmethod
    def from_env(cls, env_file: str | None = None, live: bool = False) -> Settings:
        file_values = _dotenv(env_file)

        def get(name: str, default: str | None = None) -> str | None:
            return os.environ.get(name, file_values.get(name, default))

        raw_tier = get("ANTLAB_OPENROUTER_TIER", "50")
        if raw_tier not in ("50", "1000"):
            raise ValueError("ANTLAB_OPENROUTER_TIER must be 50 or 1000")
        raw_path = get("ANTLAB_OPENROUTER_LEDGER")
        path = Path(raw_path) if raw_path else None
        if live and not get("OPENROUTER_API_KEY"):
            raise ValueError("OPENROUTER_API_KEY is required for live mode")
        if live and (path is None or not path.is_absolute()):
            raise ValueError("ANTLAB_OPENROUTER_LEDGER must be an absolute path for live mode")
        return cls(get("OPENROUTER_API_KEY"), path, int(raw_tier))

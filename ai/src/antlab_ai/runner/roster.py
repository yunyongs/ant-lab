"""Roster file of a league entry: which persona on which model and route takes a seat."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from antlab_ai.runner.config import KNOWN_ROUTES, OPENROUTER
from antlab_ai.runner.persona import KEY_MAX, KEY_PATTERN

MAX_ENTRIES: Final = 20
MAX_FILE_BYTES: Final = 16 * 1024
MODEL_MAX: Final = 200
_ENTRY_KEYS: Final = frozenset({"persona", "model_id", "route"})


class RosterError(ValueError):
    """A refused roster. The text is fixed and holds no value from the file."""


@dataclass(frozen=True)
class RosterEntry:
    persona: str
    model_id: str
    route: str


def _text(raw: Any, name: str) -> str:
    if not isinstance(raw, str) or not raw:
        raise RosterError(f"roster entry {name} must be a non-empty string")
    return raw


def _entry(raw: Any) -> RosterEntry:
    if not isinstance(raw, dict) or not set(raw) <= _ENTRY_KEYS:
        raise RosterError("roster entry has an unknown key")
    persona = _text(raw.get("persona"), "persona")
    if not KEY_PATTERN.fullmatch(persona) or len(persona) > KEY_MAX:
        raise RosterError("roster persona must be a persona key")
    model_id = _text(raw.get("model_id"), "model_id")
    if len(model_id) > MODEL_MAX or model_id != model_id.strip() or not model_id.isprintable():
        raise RosterError("roster model_id is not a valid model name")
    route = raw.get("route", OPENROUTER)
    if route not in KNOWN_ROUTES:
        raise RosterError("roster route is not a known route")
    return RosterEntry(persona, model_id, route)


def parse_roster(data: bytes) -> list[RosterEntry]:
    if len(data) > MAX_FILE_BYTES:
        raise RosterError("the roster file is too large")
    try:
        document = tomllib.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError):
        raise RosterError("the roster file cannot be read as TOML") from None
    if set(document) != {"entry"} or not isinstance(document["entry"], list):
        raise RosterError("the roster must hold only [[entry]] tables")
    raw_entries = document["entry"]
    if not 1 <= len(raw_entries) <= MAX_ENTRIES:
        raise RosterError("the roster needs 1 to 20 entries")
    entries = [_entry(raw) for raw in raw_entries]
    if len(set(entries)) != len(entries):
        raise RosterError("the roster repeats an entry")
    return entries


def load_roster(path: Path) -> list[RosterEntry]:
    try:
        data = path.read_bytes()
    except OSError:
        raise RosterError("the roster file cannot be read") from None
    return parse_roster(data)

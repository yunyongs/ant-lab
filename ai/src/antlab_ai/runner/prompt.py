"""Prompt building: a fixed system message and the TurnView as the user message, nothing added or removed."""

from __future__ import annotations

import json
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any

from antlab_ai.runner.decision import (
    KEYWORDS,
    MAX_TEXT_BYTES,
    MAX_VIOLATIONS,
    POINTER_PATTERN,
    SCHEMA_PATH,
    Violation,
)
from antlab_ai.runner.persona import Persona

PROMPT_TEMPLATE_VERSION = "td1-p1"
TEMPLATE_PATH = Path(__file__).resolve().parent / "prompts" / "turn-decision-1.md"

# The provider body adds the model id and options around the messages; keep that much of the 256 KiB free.
REQUEST_HEADROOM = 4096

# Names the turn screen must never carry (compared without case and underscores).
FORBIDDEN_KEYS = frozenset(
    {"seed", "monthblockid", "yearmonth", "engineversion", "seasonpath", "returnkind"}
)
_YEAR_MONTH = re.compile(r"\d{4}-\d{2}")
_PLACEHOLDER = re.compile(r"\{\{(PERSONA|SCHEMA)\}\}")


class PromptError(ValueError):
    pass


class LookAheadGuard(PromptError):
    """The turn screen holds a date, seed or month-block marker the agent must not see."""


@dataclass(frozen=True)
class PromptMessage:
    role: str
    content: str


def _guard(node: Any, path: str = "") -> None:
    if isinstance(node, Mapping):
        for key, value in node.items():
            if str(key).replace("_", "").lower() in FORBIDDEN_KEYS:
                raise LookAheadGuard(f"forbidden key at {path or '/'}")
            _guard(value, f"{path}/{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            _guard(value, f"{path}/{index}")
    elif isinstance(node, str):
        if _YEAR_MONTH.match(node):
            raise LookAheadGuard(f"year-month value at {path or '/'}")
    elif isinstance(node, float | Decimal):
        raise PromptError(f"decimal values are strings in the turn screen; number at {path or '/'}")


def canonical_json(turn_view: Mapping[str, Any]) -> str:
    _guard(turn_view)
    return json.dumps(turn_view, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def system_message(persona: Persona) -> str:
    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    schema = SCHEMA_PATH.read_text(encoding="utf-8")
    values = {"PERSONA": persona.body, "SCHEMA": schema}
    # One pass, so placeholder text inside the persona body is never substituted again.
    return _PLACEHOLDER.sub(lambda m: values[m.group(1)], template)


def _size(messages: Iterable[PromptMessage]) -> int:
    return len(json.dumps([m.__dict__ for m in messages], ensure_ascii=False).encode("utf-8"))


def build_messages(persona: Persona, turn_view: Mapping[str, Any]) -> tuple[PromptMessage, ...]:
    messages = (
        PromptMessage("system", system_message(persona)),
        PromptMessage("user", canonical_json(turn_view)),
    )
    if _size(messages) > MAX_TEXT_BYTES - REQUEST_HEADROOM:
        raise PromptError("prompt is too large to store as a call record")
    return messages


def reask_messages(
    messages: tuple[PromptMessage, ...], violations: Iterable[Violation]
) -> tuple[PromptMessage, ...]:
    """The same messages plus one note. The bad reply is never echoed; the note holds pointers and keywords only."""
    pairs = list(violations)[:MAX_VIOLATIONS]
    for pointer, keyword in pairs:
        if not POINTER_PATTERN.fullmatch(pointer):
            raise PromptError("violation pointer is outside the fixed vocabulary")
        if keyword not in KEYWORDS:
            raise PromptError("violation keyword is outside the fixed vocabulary")
    lines = "\n".join(f"- {pointer}: {keyword}" for pointer, keyword in pairs)
    note = (
        "Your previous answer was not accepted. Where it failed (location: rule):\n"
        f"{lines}\n"
        "Answer the same turn again with exactly one JSON object that follows the schema, and nothing else."
    )
    return (*messages, PromptMessage("user", note))

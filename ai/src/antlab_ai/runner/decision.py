"""Verdict on a model reply: does it parse as one ``turn-decision/1`` object the API will accept.

The Java check of the API is the reference. The shared corpus under
``src/test/resources/agent/turn-decision`` states the verdict of each case and the tests read it.
The runner never edits a reply: it only says ``Parsed`` or ``Rejected``.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from decimal import Decimal
from functools import cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

SCHEMA_PATH = Path(__file__).resolve().parents[4] / "docs" / "api" / "turn-decision-1.schema.json"

MAX_TEXT_BYTES = 262_144
MAX_VIOLATIONS = 20
REPLY_POINTER = "/reply"

# Ported verbatim from AgentDecisionService.NO_KEYS. re.ASCII matches Java's default \s and \b.
NO_KEYS = re.compile(
    r"(?<![A-Za-z0-9_-])sk-(?:or-v1-[A-Za-z0-9]{16,}|ant-[A-Za-z0-9_-]{20,}"
    r"|proj-[A-Za-z0-9_-]{20,}|[A-Za-z0-9]{20,})"
    r"|(?i:\bbearer\s+(?=[A-Za-z0-9._~+/=-]*\d)[A-Za-z0-9._~+/=-]{16,})",
    re.ASCII,
)

POINTER_PATTERN = re.compile(r"(/[A-Za-z0-9_]{1,40}){1,6}")
# The only keywords a violation may carry: schema validator keywords plus the runner's own checks.
KEYWORDS = frozenset(
    {
        "type", "required", "additionalProperties", "enum", "const", "pattern", "maxLength",
        "maxItems", "minimum", "maximum", "anyOf",
        "empty", "bom", "extract", "json", "nul", "size", "secret",
    }
)  # fmt: skip

_FENCE = re.compile(r"\A```(?:json)?[ \t]*\r?\n(.*)\r?\n```\Z", re.DOTALL)
_JSON_WS = " \t\r\n"
_POINTER_DEPTH = 6

Violation = tuple[str, str]


@dataclass(frozen=True)
class Parsed:
    decision: dict[str, Any]


@dataclass(frozen=True)
class Rejected:
    """Pointer and keyword pairs only; nothing of the reply text is kept."""

    violations: tuple[Violation, ...]


def _refuse_constant(name: str) -> Any:
    raise ValueError(f"constant {name} is not JSON")


@cache
def _validator() -> Draft202012Validator:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return Draft202012Validator(schema)


@cache
def _known_names() -> frozenset[str]:
    names: set[str] = set()

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            props = node.get("properties")
            if isinstance(props, dict):
                names.update(props)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(json.loads(SCHEMA_PATH.read_text(encoding="utf-8")))
    return frozenset(names)


def pointer_of(path: Any) -> str:
    """JSON pointer of an instance path. Only schema property names and indexes survive.

    Keys the schema does not name (items of ``theses`` are free-form) become ``_`` so a pointer can
    never carry reply text. The depth is capped at the Spring pointer limit.
    """
    known = _known_names()
    parts: list[str] = []
    for part in path:
        if isinstance(part, int):
            parts.append(str(part))
        else:
            parts.append(part if part in known else "_")
    if not parts:
        return REPLY_POINTER
    return "/" + "/".join(parts[:_POINTER_DEPTH])


def _extract(text: str | None) -> str | Rejected:
    if text is None or not text.strip(_JSON_WS):
        return Rejected(((REPLY_POINTER, "empty"),))
    if text.startswith("\ufeff"):
        return Rejected(((REPLY_POINTER, "bom"),))
    trimmed = text.strip(_JSON_WS)
    if trimmed.startswith("```"):
        match = _FENCE.match(trimmed)
        if match is None or "```" in match.group(1):
            return Rejected(((REPLY_POINTER, "extract"),))
        return match.group(1)
    return trimmed


def _walk_strings(node: Any, path: tuple[Any, ...]):
    """Yield (path, text) for every key and string value."""
    if isinstance(node, str):
        yield path, node
    elif isinstance(node, dict):
        for key, value in node.items():
            yield path, key
            yield from _walk_strings(value, (*path, key))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _walk_strings(value, (*path, index))


def serialize(decision: Any) -> str:
    return json.dumps(decision, ensure_ascii=False, separators=(",", ":"), default=str)


def _unsendable(decision: dict[str, Any]) -> list[Violation]:
    found: list[Violation] = []
    serialized = serialize(decision)
    if len(serialized.encode("utf-8")) > MAX_TEXT_BYTES:
        found.append((REPLY_POINTER, "size"))
    for path, text in _walk_strings(decision, ()):
        if "\x00" in text:
            found.append((pointer_of(path), "nul"))
    if NO_KEYS.search(serialized):
        found.append((REPLY_POINTER, "secret"))
    return found


def _normalize(found: list[Violation]) -> tuple[Violation, ...]:
    return tuple(sorted(set(found)))[:MAX_VIOLATIONS]


def parse_reply(text: str | None) -> Parsed | Rejected:
    extracted = _extract(text)
    if isinstance(extracted, Rejected):
        return extracted
    try:
        value = json.loads(extracted, parse_float=Decimal, parse_constant=_refuse_constant)
    except (ValueError, RecursionError):
        return Rejected(((REPLY_POINTER, "json"),))
    errors = [
        (pointer_of(e.absolute_path), str(e.validator)) for e in _validator().iter_errors(value)
    ]
    if errors:
        return Rejected(_normalize(errors))
    unsendable = _unsendable(value)
    if unsendable:
        return Rejected(_normalize(unsendable))
    return Parsed(value)


def is_storable(text: str | None) -> bool:
    """True when the API would store this text as a call record (no NUL, no key shape, within 256 KiB)."""
    if text is None:
        return True
    return (
        "\x00" not in text
        and len(text.encode("utf-8")) <= MAX_TEXT_BYTES
        and NO_KEYS.search(text) is None
    )

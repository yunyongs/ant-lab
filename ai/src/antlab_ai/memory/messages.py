"""Prompts of the memory step: the reflection and the operating-note rewrite, and the fixed re-ask note."""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from antlab_ai.memory.replies import NOTE_SCHEMA_PATH, REFLECTION_SCHEMA_PATH
from antlab_ai.modelport.port import Message
from antlab_ai.runner.persona import Persona
from antlab_ai.runner.prompt import (
    PROMPTS_DIR,
    PromptError,
    canonical_json,
    fill_template,
    require_storable,
)

REFLECTION_PROMPT_ID = "rf1-p1"
NOTE_PROMPT_ID = "on1-p1"
REFLECTION_TEMPLATE = PROMPTS_DIR / "reflection-1.md"
NOTE_TEMPLATE = PROMPTS_DIR / "operating-note-1.md"
MAX_VIOLATIONS = 20
_POINTER = re.compile(r"(?:/[A-Za-z0-9_]{1,40}){0,8}(?:#s[1-9][0-9]{0,3})?|/")
_RULE = re.compile(r"[A-Za-z_]{1,40}")

# One fixed hint per rule that can fail; the note never holds anything of the bad reply.
HINTS = {
    "YEAR": "Do not write a year, a decade or a two-digit year.",
    "YEAR_MONTH": "Do not write a month together with a year.",
    "EVENT_TERM": "Do not name events, crises, eras or people.",
    "FINGERPRINT": "Do not write several return numbers or percentages.",
    "TURN_RETURN": "Do not tie a turn or a month of play to a return number.",
    "PRICE": "Do not write prices or amounts of money.",
    "OVERSIZE": "Write less: shorter lines and fewer of them.",
}
_SHAPE_HINT = "Follow the schema exactly: its keys, its line lengths and its item counts."


def reflection_messages(persona: Persona, view: Mapping[str, Any]) -> tuple[Message, ...]:
    """The review of the season as the user message. The base note is left out: the reflection
    must not depend on it, so a changed base never makes the reflection stale."""
    review = {k: view[k] for k in ("pathLength", "report", "score", "turns")}
    messages = (
        Message("system", fill_template(REFLECTION_TEMPLATE, persona, REFLECTION_SCHEMA_PATH)),
        Message("user", canonical_json(review)),
    )
    require_storable(messages)
    return messages


def playbook_messages(
    persona: Persona, base_rules: Sequence[str], reflection: Mapping[str, Any]
) -> tuple[Message, ...]:
    body = {"currentRules": list(base_rules), "reflection": dict(reflection)}
    messages = (
        Message("system", fill_template(NOTE_TEMPLATE, persona, NOTE_SCHEMA_PATH)),
        Message("user", canonical_json(body)),
    )
    require_storable(messages)
    return messages


def reask_messages(
    messages: tuple[Message, ...], violations: Iterable[tuple[str, str]]
) -> tuple[Message, ...]:
    """The same messages plus one note with pointers, rule names and fixed hints; never the bad text."""
    pairs = list(violations)[:MAX_VIOLATIONS]
    for pointer, rule in pairs:
        if not _POINTER.fullmatch(pointer) or not _RULE.fullmatch(rule):
            raise PromptError("violation is outside the fixed vocabulary")
    lines = "\n".join(f"- {pointer}: {rule}" for pointer, rule in pairs)
    hints = [HINTS[rule] for rule in dict.fromkeys(rule for _, rule in pairs) if rule in HINTS]
    if any(rule not in HINTS for _, rule in pairs):
        hints.append(_SHAPE_HINT)
    note = (
        "Your previous answer was not accepted. Where it failed (location: rule):\n"
        f"{lines}\n" + "\n".join(hints) + "\n"
        "Answer again with exactly one JSON object that follows the schema, and nothing else."
    )
    return (*messages, Message("user", note))

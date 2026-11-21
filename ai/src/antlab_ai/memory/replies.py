"""Verdict on a model reply of the memory step: shape, encoding and the Python leak pre-check.

The Java side stays authoritative; a miss here comes back from the API as a 422 and is handled like
a hit. A verdict holds fixed pointers and codes only, never the reply text.
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from decimal import Decimal
from functools import cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

from antlab_ai.memory.filter import Terms, check_note, check_reflection, load_terms
from antlab_ai.runner.decision import extract_json_text, is_storable

REPO_ROOT = Path(__file__).resolve().parents[4]
REFLECTION_SCHEMA_PATH = REPO_ROOT / "docs/api/season-reflection-1.schema.json"
NOTE_SCHEMA_PATH = REPO_ROOT / "docs/api/operating-note-1.schema.json"
SCHEMA_CODE = "SCHEMA"
OVERSIZE = "OVERSIZE"
# The discard codes a failed reply can carry, in the contract's order (BUDGET is the owner's choice).
FILTER_DISCARD_CODES = (
    "YEAR",
    "YEAR_MONTH",
    "EVENT_TERM",
    "FINGERPRINT",
    "TURN_RETURN",
    "PRICE",
    OVERSIZE,
)
ROOT_POINTER = "/"
MAX_VIOLATIONS = 20
_NAMES = frozenset({"schema", "good", "mistakes", "ruleCandidates", "type", "text", "rules"})
_LONE_SURROGATE = re.compile("[\ud800-\udfff]")

Pairs = tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class Verdict:
    """``doc`` is the NFC document when the reply is acceptable; otherwise ``violations`` says where."""

    doc: dict[str, Any] | None
    violations: Pairs

    @property
    def ok(self) -> bool:
        return self.doc is not None


@cache
def default_terms() -> Terms:
    return load_terms()


@cache
def _validator(path: Path) -> Draft202012Validator:
    return Draft202012Validator(json.loads(path.read_text(encoding="utf-8")))


def _refuse_constant(name: str) -> Any:
    raise ValueError(f"constant {name} is not JSON")


class _DuplicateKey(ValueError):
    pass


def _no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    """A repeated key would hide the first value from the filter while its text is still sent."""
    keys = [key for key, _ in pairs]
    if len(set(keys)) != len(keys):
        raise _DuplicateKey("repeated key")
    return dict(pairs)


def nfc_tree(node: Any) -> Any:
    if isinstance(node, str):
        return unicodedata.normalize("NFC", node)
    if isinstance(node, dict):
        return {unicodedata.normalize("NFC", k): nfc_tree(v) for k, v in node.items()}
    if isinstance(node, list):
        return [nfc_tree(v) for v in node]
    return node


def _pointer(path: Any) -> str:
    parts = [str(p) if isinstance(p, int) else (p if p in _NAMES else "_") for p in path]
    return "/" + "/".join(parts[:6]) if parts else ROOT_POINTER


def _encoding_pairs(node: Any, path: tuple[Any, ...] = ()) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    if isinstance(node, str):
        if "\x00" in node or _LONE_SURROGATE.search(node):
            found.append((_pointer(path), "encoding"))
    elif isinstance(node, dict):
        for key, value in node.items():
            if "\x00" in key or _LONE_SURROGATE.search(key):
                found.append((_pointer(path), "encoding"))
            found.extend(_encoding_pairs(value, (*path, key)))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            found.extend(_encoding_pairs(value, (*path, index)))
    return found


def _pairs(found: list[tuple[str, str]]) -> Pairs:
    return tuple(sorted(set(found)))[:MAX_VIOLATIONS]


def _judge(text: str | None, schema_path: Path, terms: Terms, note: bool) -> Verdict:
    if text is None or not is_storable(text):
        return Verdict(None, ((ROOT_POINTER, "json"),))
    extracted = extract_json_text(text)
    if extracted is None:
        return Verdict(None, ((ROOT_POINTER, "empty"),))
    try:
        value = json.loads(
            extracted,
            parse_float=Decimal,
            parse_constant=_refuse_constant,
            object_pairs_hook=_no_duplicates,
        )
    except _DuplicateKey:
        return Verdict(None, ((ROOT_POINTER, "duplicate"),))
    except (ValueError, RecursionError):
        return Verdict(None, ((ROOT_POINTER, "json"),))
    if not isinstance(value, dict):
        return Verdict(None, ((ROOT_POINTER, "type"),))
    errors = _encoding_pairs(value)
    value = nfc_tree(value)
    errors += [
        (_pointer(e.absolute_path), str(e.validator))
        for e in _validator(schema_path).iter_errors(value)
    ]
    if errors:
        return Verdict(None, _pairs(errors))
    verdict = (check_note if note else check_reflection)(value, terms)
    leaks = [(hit.pointer, hit.code) for hit in verdict.hits]
    if verdict.oversize:
        leaks.append((ROOT_POINTER, OVERSIZE))
    if leaks:
        return Verdict(None, _pairs(leaks))
    return Verdict(value, ())


def judge_reflection(text: str | None, terms: Terms | None = None) -> Verdict:
    return _judge(text, REFLECTION_SCHEMA_PATH, terms or default_terms(), note=False)


def judge_note(text: str | None, terms: Terms | None = None) -> Verdict:
    return _judge(text, NOTE_SCHEMA_PATH, terms or default_terms(), note=True)


def discard_codes(violations: Pairs) -> list[str]:
    """Fixed discard codes of the last failure, in the contract's order; shape failures are SCHEMA."""
    codes = {code if code in FILTER_DISCARD_CODES else SCHEMA_CODE for _, code in violations}
    codes = codes or {SCHEMA_CODE}
    return [code for code in (*FILTER_DISCARD_CODES, SCHEMA_CODE) if code in codes]

"""Persona files: YAML front matter plus a Korean body, read as text and converted explicitly."""

from __future__ import annotations

import csv
import hashlib
import re
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[4]
PERSONAS_DIR = REPO_ROOT / "personas"
EVENTS_DIR = REPO_ROOT / "events"
MASTERS_DIR = REPO_ROOT / "masters"
LOCK_NAME = "personas.lock"

FIELDS = (
    "key",
    "version",
    "display_name",
    "style_master",
    "default_risk_limit",
    "default_formation",
)
RISK_LIMIT_PATTERN = re.compile(r"-0\.[1-4]0?")
KEY_PATTERN = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
KEY_MAX = 64
DISPLAY_NAME_MAX = 50
VERSION_PATTERN = re.compile(r"[1-9][0-9]{0,8}")
INT_PATTERN = re.compile(r"(0|[1-9][0-9]{0,2})")
SECTIONS = ("## 철학", "## 행동 규칙", "## 하지 않는 것")
PHILOSOPHY_MAX = 300
RULES_RANGE = (5, 10)
AVOID_RANGE = (1, 10)
BODY_MAX_BYTES = 4000
EVENT_TITLE_MIN = 4
MASTER_DENY_LIST = (
    "buffett", "lynch", "graham", "soros", "dalio", "munger", "templeton", "fisher", "marks", "greenblatt",
    "버핏", "린치", "그레이엄", "소로스", "달리오", "멍거", "템플턴", "피셔", "마크스", "그린블랫",
)  # fmt: skip

_DATE_IN_BODY = re.compile(r"(?<!\d)(19|20)\d{2}[-/.]?(0[1-9]|1[0-2])(?!\d)|YYYY-MM")
_YEAR_IN_BODY = re.compile(r"(?<!\d)(19|20)\d{2}(?!\d)")
LOCK_ROW = re.compile(r"([a-z0-9]+(?:-[a-z0-9]+)*) ([1-9][0-9]{0,8}) ([0-9a-f]{64})")


class PersonaError(ValueError):
    pass


class _StrictLoader(yaml.BaseLoader):
    """Every scalar stays a string; a repeated key is an error."""

    def construct_mapping(self, node, deep=False):
        seen: set[str] = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=True)
            if key in seen:
                raise PersonaError("repeated front matter key")
            seen.add(key)
        return super().construct_mapping(node, deep)


@dataclass(frozen=True)
class Persona:
    key: str
    version: int
    display_name: str
    style_master: str | None
    default_risk_limit: Decimal
    default_formation: tuple[int, int, int, int]
    body: str
    sha256: str


def _split(text: str) -> tuple[str, str]:
    if not text.startswith("---\n"):
        raise PersonaError("front matter must start the file")
    end = text.find("\n---\n", 3)
    if end < 0:
        raise PersonaError("front matter is not closed")
    return text[4 : end + 1], text[end + 5 :]


def _front(raw: str) -> dict[str, object]:
    try:
        data = yaml.load(raw, Loader=_StrictLoader)  # noqa: S506 - BaseLoader subclass, strings only
    except (yaml.YAMLError, TypeError) as exc:
        raise PersonaError("front matter is not valid YAML") from exc
    if not isinstance(data, dict) or set(data) != set(FIELDS):
        raise PersonaError(f"front matter keys must be exactly {', '.join(FIELDS)}")
    return data


def _scalar(data: dict[str, object], name: str) -> str:
    value = data[name]
    if not isinstance(value, str):
        raise PersonaError(f"{name} must be a scalar")
    return value


def _formation(data: dict[str, object]) -> tuple[int, int, int, int]:
    items = data["default_formation"]
    if not isinstance(items, list) or len(items) != 4:
        raise PersonaError("default_formation must hold four integers")
    if not all(isinstance(i, str) and INT_PATTERN.fullmatch(i) for i in items):
        raise PersonaError("default_formation items must be whole numbers")
    shares = tuple(int(i) for i in items)
    if sum(shares) != 100 or shares[0] < 1:
        raise PersonaError("default_formation must add up to 100 with a goalkeeper of at least 1")
    return shares  # type: ignore[return-value]


def _risk_limit(raw: str) -> Decimal:
    if not RISK_LIMIT_PATTERN.fullmatch(raw):
        raise PersonaError("default_risk_limit must be -0.10, -0.20, -0.30 or -0.40")
    return Decimal(raw)


def _event_titles(events_dir: Path) -> list[str]:
    tags = events_dir / "tags.csv"
    if not tags.is_file():
        return []
    with tags.open(encoding="utf-8", newline="") as handle:
        return [
            title
            for row in csv.DictReader(handle)
            if len(title := (row.get("title") or "").strip()) >= EVENT_TITLE_MIN
        ]


def _sections(body: str) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in body.splitlines():
        if line.startswith("## "):
            if line in sections:
                raise PersonaError("persona sections must appear once")
            current = line
            sections[current] = []
        elif line.strip():
            if current is None:
                raise PersonaError("persona text must start with the first section")
            sections[current].append(line)
    if tuple(sections) != SECTIONS:
        raise PersonaError("persona sections must be exactly the three, in order")
    return sections


def _list_lines(lines: list[str], bounds: tuple[int, int], what: str) -> None:
    if not all(line.startswith("- ") for line in lines) or not bounds[0] <= len(lines) <= bounds[1]:
        raise PersonaError(f"{what} must be {bounds[0]} to {bounds[1]} list lines")


def _checked_body(body: str, events_dir: Path) -> str:
    # The persona text must not carry dates: the agent has no calendar (PLAN 4.16).
    if _DATE_IN_BODY.search(body) or _YEAR_IN_BODY.search(body):
        raise PersonaError("persona body must not contain a year or year-month")
    body = body.strip("\n")
    if len(body.encode("utf-8")) > BODY_MAX_BYTES:
        raise PersonaError(f"persona body must be at most {BODY_MAX_BYTES} bytes")
    sections = _sections(body)
    philosophy = " ".join(sections[SECTIONS[0]])
    if not 0 < len(philosophy) <= PHILOSOPHY_MAX:
        raise PersonaError(f"philosophy must be 1 to {PHILOSOPHY_MAX} characters")
    _list_lines(sections[SECTIONS[1]], RULES_RANGE, "rules")
    _list_lines(sections[SECTIONS[2]], AVOID_RANGE, "avoid list")
    if any(title in body for title in _event_titles(events_dir)):
        raise PersonaError("persona body must not contain an event title")
    return body


_DENY_PATTERN = re.compile(
    "|".join(
        rf"(?<![a-z]){re.escape(name)}(?![a-z])" if name.isascii() else re.escape(name)
        for name in MASTER_DENY_LIST
    )
)


def _denied_name(text: str) -> bool:
    return _DENY_PATTERN.search(text.lower()) is not None


def _check_style_master(style_master: str | None, masters_dir: Path) -> None:
    if not masters_dir.is_dir():
        if style_master is not None:
            raise PersonaError("style_master must be null until the masters folder exists")
        return
    if style_master is not None and not any(
        path.stem == style_master for path in masters_dir.iterdir()
    ):
        raise PersonaError("style_master must name an existing master")


def parse_persona(
    content: bytes, *, events_dir: Path = EVENTS_DIR, masters_dir: Path = MASTERS_DIR
) -> Persona:
    """Parse persona file bytes. The hash is the SHA-256 of exactly these bytes."""
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise PersonaError("persona file is not UTF-8") from exc
    if text.startswith("﻿") or "\r" in text or "\x00" in text:
        raise PersonaError("persona file must use LF line ends and no BOM or NUL")
    raw_front, body = _split(text)
    data = _front(raw_front)

    key = _scalar(data, "key")
    if not KEY_PATTERN.fullmatch(key) or len(key) > KEY_MAX:
        raise PersonaError("key must be lowercase words joined by hyphens")
    version_text = _scalar(data, "version")
    if not VERSION_PATTERN.fullmatch(version_text):
        raise PersonaError("version must be a whole number of at least 1")
    display_name = _scalar(data, "display_name")
    if (
        not display_name
        or len(display_name) > DISPLAY_NAME_MAX
        or display_name != display_name.strip()
        or not display_name.isprintable()
    ):
        raise PersonaError("display_name must be 1-50 characters without edge spaces")
    master_text = _scalar(data, "style_master")
    style_master = None if master_text == "null" else master_text
    if style_master is not None and not KEY_PATTERN.fullmatch(style_master):
        raise PersonaError("style_master must be null or a lowercase key")
    _check_style_master(style_master, masters_dir)
    if _DATE_IN_BODY.search(display_name) or _YEAR_IN_BODY.search(display_name):
        raise PersonaError("display_name must not contain a year or year-month")
    if _denied_name(display_name) or _denied_name(body):
        raise PersonaError("persona text must not contain a real master name")

    return Persona(
        key=key,
        version=int(version_text),
        display_name=display_name,
        style_master=style_master,
        default_risk_limit=_risk_limit(_scalar(data, "default_risk_limit")),
        default_formation=_formation(data),
        body=_checked_body(body, events_dir),
        sha256=hashlib.sha256(content).hexdigest(),
    )


def load_persona(key: str, directory: Path = PERSONAS_DIR) -> Persona:
    if not KEY_PATTERN.fullmatch(key) or len(key) > KEY_MAX:
        raise PersonaError("persona key must be lowercase words joined by hyphens")
    path = directory / f"{key}.md"
    if path.is_symlink() or not path.is_file():
        raise PersonaError("persona file not found")
    persona = parse_persona(path.read_bytes())
    if persona.key != key:
        raise PersonaError("persona file name and key differ")
    return persona


@dataclass(frozen=True)
class LockRow:
    key: str
    version: int
    sha256: str


def read_lock(directory: Path = PERSONAS_DIR) -> list[LockRow]:
    path = directory / LOCK_NAME
    if path.is_symlink() or not path.is_file():
        raise PersonaError("personas.lock not found")
    text = path.read_bytes().decode("utf-8")
    if "\r" in text or (text and not text.endswith("\n")):
        raise PersonaError("personas.lock must use LF line ends and end with a newline")
    rows: list[LockRow] = []
    for line in text.splitlines():
        match = LOCK_ROW.fullmatch(line)
        if match is None:
            raise PersonaError("personas.lock row must be: key version sha256")
        rows.append(LockRow(match[1], int(match[2]), match[3]))
    return rows


def verify_lock(directory: Path = PERSONAS_DIR) -> None:
    """Released versions are append-only; the newest row of a key must equal its file."""
    rows = read_lock(directory)
    if rows != sorted(rows, key=lambda r: (r.key, r.version)):
        raise PersonaError("personas.lock rows must be sorted by key and version")
    versions: dict[str, list[int]] = {}
    for row in rows:
        versions.setdefault(row.key, []).append(row.version)
    for key, found in versions.items():
        if found != list(range(1, len(found) + 1)):
            raise PersonaError(f"personas.lock versions of {key} must be 1..n without gaps")
    files = sorted(path.stem for path in directory.glob("*.md"))
    if files != sorted(versions):
        raise PersonaError("personas.lock keys must equal the persona files")
    newest = {row.key: row for row in rows}
    for key in files:
        persona = load_persona(key, directory)
        row = newest[key]
        if (persona.version, persona.sha256) != (row.version, row.sha256):
            raise PersonaError(
                f"{key} differs from its newest lock row: bump version and add a row"
            )

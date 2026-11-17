"""Persona files: YAML front matter plus a Korean body, read as text and converted explicitly."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

import yaml

PERSONAS_DIR = Path(__file__).resolve().parents[4] / "personas"

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
    except yaml.YAMLError as exc:
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


def parse_persona(content: bytes) -> Persona:
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
    ):
        raise PersonaError("display_name must be 1-50 characters without edge spaces")
    master_text = _scalar(data, "style_master")
    style_master = None if master_text == "null" else master_text
    if style_master is not None and not KEY_PATTERN.fullmatch(style_master):
        raise PersonaError("style_master must be null or a lowercase key")

    return Persona(
        key=key,
        version=int(version_text),
        display_name=display_name,
        style_master=style_master,
        default_risk_limit=_risk_limit(_scalar(data, "default_risk_limit")),
        default_formation=_formation(data),
        body=body.strip("\n"),
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

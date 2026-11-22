"""The template pool of `feed/`: which templates a candidate may use (Java `FeedTemplateCatalog`).

Only what the generator needs is read: active templates, their placeholders and the variable
types behind the r = 0 shape rule. Text rendering stays in Java. The sha256 of all five files
travels with the vectors, so a changed catalog fails the parity tests instead of drifting.
"""

import csv
import hashlib
import io
import re
from collections.abc import Collection
from dataclasses import dataclass
from pathlib import Path

from .feedhash import byte_key

FILES = ("denylist.csv", "kinds.csv", "notices.csv", "templates.csv", "variables.csv")
TEMPLATES_HEADER = [
    "template_id",
    "kind",
    "status",
    "target",
    "direction",
    "strength",
    "event_category",
    "event_type",
    "outcome",
    "category_hidden",
    "text",
]
VARIABLES_HEADER = ["name", "type", "source", "turn_offset", "kinds", "max_len"]

_PLACEHOLDER = re.compile(r"\{([a-z][a-z0-9_]*)\}")


class CatalogError(ValueError):
    pass


def default_directory() -> Path:
    """The tracked `feed/` of the repository checkout this package sits in."""
    return Path(__file__).resolve().parents[5] / "feed"


def placeholders_of(text: str) -> tuple[str, ...]:
    """Distinct slot names in order of first appearance."""
    return tuple(dict.fromkeys(_PLACEHOLDER.findall(text)))


@dataclass(frozen=True, slots=True)
class Template:
    id: str
    kind: str
    active: bool
    target: str
    direction: str | None
    event_category: str | None
    event_type: str | None
    outcome: str | None
    category_hidden: bool
    text: str
    placeholders: tuple[str, ...]


def _read_csv(raw: bytes, name: str, header: list[str]) -> list[list[str | None]]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        raise CatalogError(f"feed/{name}: not UTF-8") from e
    if text.startswith("﻿"):
        raise CatalogError(f"feed/{name}: byte order mark")
    try:
        records = list(
            csv.reader(io.StringIO(text, newline=""), quoting=csv.QUOTE_NOTNULL, strict=True)
        )
    except csv.Error as e:
        raise CatalogError(f"feed/{name}: {e}") from e
    if not records or records[0] != header:
        raise CatalogError(f"feed/{name}: header does not match {header}")
    for i, record in enumerate(records[1:], start=2):
        if len(record) != len(header):
            raise CatalogError(f"feed/{name}: record {i} has {len(record)} cells")
    return records[1:]


def _required(value: str | None, name: str, column: str) -> str:
    if value is None or not value.strip():
        raise CatalogError(f"feed/{name}: empty {column}")
    return value


class Catalog:
    def __init__(self, files: dict[str, bytes]) -> None:
        missing = [f for f in FILES if f not in files]
        if missing:
            raise CatalogError(f"feed files missing: {missing}")
        self.sha256 = {f: hashlib.sha256(files[f]).hexdigest() for f in FILES}
        self._variable_types = {
            _required(row[0], "variables.csv", "name"): _required(row[1], "variables.csv", "type")
            for row in _read_csv(files["variables.csv"], "variables.csv", VARIABLES_HEADER)
        }
        templates: list[Template] = []
        for row in _read_csv(files["templates.csv"], "templates.csv", TEMPLATES_HEADER):
            templates.append(self._template(row))
        active = sorted((t for t in templates if t.active), key=lambda t: byte_key(t.id))
        self._by_kind_target: dict[tuple[str, str], list[Template]] = {}
        for t in active:
            self._by_kind_target.setdefault((t.kind, t.target), []).append(t)
        self._event_shapes = frozenset(self.shape(t.text) for t in active if t.kind == "EVENT")

    @classmethod
    def load(cls, directory: Path | None = None) -> "Catalog":
        base = default_directory() if directory is None else directory
        return cls({f: (base / f).read_bytes() for f in FILES})

    @staticmethod
    def _template(row: list[str | None]) -> Template:
        status = _required(row[2], "templates.csv", "status")
        if status not in ("ACTIVE", "RETIRED"):
            raise CatalogError(f"feed/templates.csv: status {status}")
        hidden = _required(row[9], "templates.csv", "category_hidden")
        if hidden not in ("true", "false"):
            raise CatalogError(f"feed/templates.csv: category_hidden {hidden}")
        text = _required(row[10], "templates.csv", "text")
        return Template(
            id=_required(row[0], "templates.csv", "template_id"),
            kind=_required(row[1], "templates.csv", "kind"),
            active=status == "ACTIVE",
            target=_required(row[3], "templates.csv", "target"),
            direction=row[4],
            event_category=row[6],
            event_type=row[7],
            outcome=row[8],
            category_hidden=hidden == "true",
            text=text,
            placeholders=placeholders_of(text),
        )

    def shape(self, text: str) -> str:
        """The sentence with each slot replaced by its variable type; an unknown slot is `?`."""

        def replace(m: re.Match[str]) -> str:
            return "{" + self._variable_types.get(m.group(1), "?") + "}"

        return _PLACEHOLDER.sub(replace, text)

    def pool(
        self,
        kind: str,
        target: str,
        direction: str | None,
        r: int | None,
        keys: Collection[str],
        hidden: bool,
        category: str | None,
    ) -> list[Template]:
        """Templates a candidate may use, in template-id byte order (SQL `tf_template_pool`)."""
        out: list[Template] = []
        for t in self._by_kind_target.get((kind, target), []):
            if kind == "EVENT":
                attributes = (
                    t.category_hidden == hidden
                    and (hidden or (t.event_category is not None and t.event_category == category))
                    and t.event_type is None
                    and t.outcome is None
                )
            else:
                attributes = t.direction == direction
            if (
                attributes
                and all(p in keys for p in t.placeholders)
                and (r is None or r != 0 or self.shape(t.text) in self._event_shapes)
            ):
                out.append(t)
        return out

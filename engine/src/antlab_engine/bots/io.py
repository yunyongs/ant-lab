"""Files of a bot batch: `bot-run/1` JSONL and a manifest under `out/bots/<run>/`.

Records carry no dates, month ids or names. Everything is written with sorted keys and compact
separators so the same batch gives the same bytes.
"""

import hashlib
import json
from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any

from antlab_engine.sim.decisions import FormationUnit, LineupUnit, OrderUnit, RejectedUnit, Unit
from antlab_engine.sim.model import SourceKind

from .world import canonical

DECISIONS_SCHEMA = "sim-decisions/1"
RUN_SCHEMA = "bot-run/1"


def unit_to_doc(unit: Unit) -> dict[str, Any]:
    if isinstance(unit, OrderUnit):
        order = unit.order
        doc: dict[str, Any] = {
            "kind": "ORDER",
            "asset": order.asset,
            "side": order.side.value,
            "quantity": order.quantity,
        }
        if order.position is not None:
            doc["position"] = order.position.value
        source: dict[str, Any] = {"kind": order.source.kind.value}
        if order.source.kind in (SourceKind.FEED, SourceKind.ALERT):
            source["feedTurn"] = order.source.feed_turn
            source["feedSeq"] = order.source.feed_seq
        if order.source.kind is not SourceKind.DIRECT:
            doc["source"] = source
        return doc
    if isinstance(unit, FormationUnit):
        doc = {
            "kind": "FORMATION",
            "goalkeeper": unit.goalkeeper,
            "defender": unit.defender,
            "midfielder": unit.midfielder,
            "forward": unit.forward,
        }
        if unit.reason is not None:
            doc["reason"] = unit.reason
        return doc
    if isinstance(unit, LineupUnit):
        return {"kind": "LINEUP", "asset": unit.asset, "position": unit.position.value}
    if isinstance(unit, RejectedUnit):
        raise ValueError("a rejected unit has no sim-decisions form")
    raise TypeError(f"unknown unit {unit!r}")


def decisions_doc(units_by_turn: Sequence[tuple[int, Sequence[Unit]]]) -> dict[str, Any]:
    """`sim-decisions/1` of the turns that carry at least one unit."""
    return {
        "schema": DECISIONS_SCHEMA,
        "turns": [
            {"turn": turn, "units": [unit_to_doc(u) for u in units]}
            for turn, units in units_by_turn
            if units
        ],
    }


def decisions_sha256(doc: dict[str, Any]) -> str:
    return hashlib.sha256(canonical(doc).encode()).hexdigest()


def record_line(record: dict[str, Any]) -> str:
    return canonical(record)


def write_batch(
    out_dir: Path, records: Iterable[dict[str, Any]], manifest: dict[str, Any]
) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    runs = out_dir / "runs.jsonl"
    with runs.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(record_line(record) + "\n")
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(canonical(manifest) + "\n", encoding="utf-8", newline="\n")
    return runs, manifest_path


def read_runs(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]

"""`result.json` (`validation-run/1`) and `summary.md` of a score-validation run.

The one I/O module of the package: it writes two files under a directory the caller passes.
Decimal numbers are strings everywhere; only counts, sizes and flags are JSON numbers or booleans.
The summary is a fixed template over `result.json`, with no number that is not in the result.
"""

import json
import os
from collections.abc import Mapping, Sequence
from pathlib import Path

from .checks import INTERVAL_RULE
from .overlap import render_summary as render_overlap_summary
from .overlap import validate_overlap

SCHEMA = "validation-run/1"
STATUS_PROVISIONAL = "provisional"
STATUS_FINAL = "final"
STATUSES = (STATUS_PROVISIONAL, STATUS_FINAL)
MANIFEST_KEYS = (
    "masterSeed",
    "worldSeeds",
    "botVersions",
    "catalogSha256",
    "scoreVersion",
    "engineVersion",
    "gitCommit",
)
CELL_KEYS = ("cell", "worlds", "formulas", "search", "calibration", "correlation")
CHECK_IDS = ("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8")
FORMULA_KEYS = ("name", "formula", "role", "worlds", "weights", "checks", "careers")
MAX_SUMMARY_LINES = 60
MAX_SUMMARY_CELLS = 5
RESULT_FILE = "result.json"
SUMMARY_FILE = "summary.md"
OVERLAP_FILE = "overlap.json"
OVERLAP_SUMMARY_FILE = "overlap_summary.md"


class OutputError(ValueError):
    """A result that does not match `validation-run/1`."""


def build_result(
    manifest: Mapping[str, object],
    cells: Sequence[tuple[Mapping[str, object], Mapping[str, object]]],
    *,
    status: str,
    profile: str | None = None,
) -> dict[str, object]:
    """Assemble and validate a result from (cell descriptor, `analyze_cell` output) pairs."""
    result: dict[str, object] = {
        "schema": SCHEMA,
        "status": status,
        "profile": profile,
        "manifest": dict(manifest),
        "intervalRule": dict(INTERVAL_RULE),
        "cells": [{"cell": dict(descriptor), **analysis} for descriptor, analysis in cells],
    }
    validate_result(result)
    return result


def _json_leaves(value: object, path: str) -> None:
    """Only str, int, bool, None, list and dict: decimals are strings, never floats or numpy."""
    if isinstance(value, float):
        raise OutputError(f"{path}: numbers must be strings, got a float")
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise OutputError(f"{path}: object keys must be strings")
            _json_leaves(item, f"{path}.{key}")
    elif isinstance(value, list):
        for position, item in enumerate(value):
            _json_leaves(item, f"{path}[{position}]")
    elif value is not None and type(value) not in (str, int, bool):
        raise OutputError(f"{path}: {type(value).__name__} is not a JSON value")


def _require(mapping: object, keys: Sequence[str], where: str) -> Mapping[str, object]:
    if not isinstance(mapping, Mapping):
        raise OutputError(f"{where} must be an object")
    missing = [k for k in keys if k not in mapping]
    if missing:
        raise OutputError(f"{where} lacks {', '.join(missing)}")
    return mapping


def validate_result(result: Mapping[str, object]) -> None:
    _require(result, ("schema", "status", "manifest", "cells"), "result")
    if result["schema"] != SCHEMA:
        raise OutputError(f"schema must be {SCHEMA}")
    if result["status"] not in STATUSES:
        raise OutputError("status must be provisional or final")
    manifest = _require(result["manifest"], MANIFEST_KEYS, "manifest")
    seeds = manifest["worldSeeds"]
    if not isinstance(manifest["masterSeed"], str) or not (
        isinstance(seeds, list) and all(isinstance(x, str) for x in seeds)
    ):
        raise OutputError("seeds are 64-bit and must be strings")
    cells = result["cells"]
    if not isinstance(cells, list) or not cells:
        raise OutputError("cells must be a non-empty list")
    for position, cell in enumerate(cells):
        _require(cell, CELL_KEYS, f"cells[{position}]")
        formulas = cell["formulas"]
        if not isinstance(formulas, list) or not formulas:
            raise OutputError(f"cells[{position}].formulas must be a non-empty list")
        for formula in formulas:
            _require(formula, FORMULA_KEYS, "formula entry")
            _require(formula["checks"], CHECK_IDS, f"{formula['name']}.checks")
        _require(cell["search"], ("s63", "s"), f"cells[{position}].search")
    _json_leaves(result, "result")


# ---------------------------------------------------------------------------- summary


def _mean_text(value: object) -> str:
    return "n/a" if value is None else str(value)


def _flag(check: Mapping[str, object]) -> str:
    passed = check["passed"]
    return "n/a" if passed is None else ("pass" if passed else "fail")


def _weights(weights: Mapping[str, str]) -> str:
    return ", ".join(f"{name} {value}" for name, value in weights.items())


def _formula_line(entry: Mapping[str, object]) -> str:
    checks = entry["checks"]
    flags = " ".join(f"{cid} {_flag(checks[cid])}" for cid in CHECK_IDS)
    notes = ""
    if checks["C7"].get("flagged"):
        notes += "; C7 flagged: the news bot fails the result-only rule"
    if checks["C8"].get("flagged"):
        notes += "; C8 flagged: recommend a minimum interval between formation changes"
    scope = " [in-sample: all worlds]" if entry["role"] == "proposal" else ""
    return f"- {entry['name']}{scope} ({_weights(entry['weights'])}): {flags}{notes}"


def _search_line(name: str, search: Mapping[str, object]) -> str:
    passing = search["screenPassing"]
    proposal = search["proposal"]
    chosen = "none" if proposal is None else _weights(proposal["weights"])
    held = search["heldOut"]
    failed = ""
    if held is not None:
        failed = (
            f"; held-out failed {', '.join(held['failed'])}"
            if held["failed"]
            else "; held-out all passed"
        )
    return (
        f"- {name} search: {search['outcome']}; screened grid points "
        f"train {passing['train']}, test {passing['test']}; proposal {chosen}{failed}"
    )


def _profile_text(result: Mapping[str, object]) -> str:
    profile = result.get("profile")
    return "" if profile is None else f" (profile {profile})"


def render_summary(result: Mapping[str, object]) -> str:
    """Fixed-template summary, at most 60 lines, every number taken from `result`."""
    validate_result(result)
    cells = result["cells"]
    if len(cells) > MAX_SUMMARY_CELLS:
        raise OutputError(f"a summary holds at most {MAX_SUMMARY_CELLS} cells")
    manifest = result["manifest"]
    lines = [
        "# Score-weight validation",
        "",
        f"Status: {result['status']}{_profile_text(result)}. "
        f"Score version {manifest['scoreVersion']}, "
        f"engine {manifest['engineVersion']}, commit {manifest['gitCommit']}, "
        f"master seed {manifest['masterSeed']}.",
        "Weights are proposals only until the real-history rerun; nothing here changes the score.",
        "In S the percentile parts average the middle, so its C1 mostly tests the process part.",
        "",
    ]
    for cell in cells:
        worlds = cell["worlds"]
        descriptor = cell["cell"]
        lines.append(
            f"## Cell {descriptor.get('id', '?')}: {worlds['total']} worlds "
            f"({worlds['train']} train, {worlds['test']} test)"
        )
        by_name = {entry["name"]: entry for entry in cell["formulas"]}
        for name in ("s63-draft", "s63-proposal", "s-draft", "s-proposal"):
            if name in by_name:
                lines.append(_formula_line(by_name[name]))
        lines.append(_search_line("s63", cell["search"]["s63"]))
        lines.append(_search_line("S", cell["search"]["s"]))
        draft = by_name["s-draft"]["checks"]
        lines.append(
            f"- S draft means: random {draft['C1']['mean']}, "
            f"disciplined minus random {_mean_text(draft['C3'].get('mean'))}, "
            f"news minus random {_mean_text(draft['C4'].get('mean'))}"
        )
        calibration = cell["calibration"]
        if calibration["available"]:
            ks = calibration["ks"]
            lines.append(
                f"- Static calibration: mean {calibration['mean']}, KS {ks['statistic']} "
                f"(critical {ks['critical']}), {'pass' if calibration['passed'] else 'fail'}"
            )
        else:
            lines.append("- Static calibration: not available")
        rho = cell["correlation"]["draft"]
        lines.append(
            f"- Rank correlation s63 vs S (draft): {_mean_text(rho['rho'])} over {rho['n']} bots"
        )
        lines.append("")
    while lines and lines[-1] == "":
        lines.pop()
    if len(lines) > MAX_SUMMARY_LINES:
        raise OutputError("the summary would exceed 60 lines")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------- files


def write_run(directory: Path, result: Mapping[str, object]) -> tuple[Path, Path]:
    """Write `result.json` and `summary.md` under `directory` (created when missing)."""
    summary = render_summary(result)
    directory.mkdir(parents=True, exist_ok=True)
    result_path = directory / RESULT_FILE
    summary_path = directory / SUMMARY_FILE
    result_path.write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n"
    )
    summary_path.write_text(summary, encoding="utf-8", newline="\n")
    return result_path, summary_path


def _replace_text(path: Path, text: str) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def write_overlap(directory: Path, doc: Mapping[str, object]) -> tuple[Path, Path]:
    """Write `overlap.json` and `overlap_summary.md` under `directory`, each replaced atomically."""
    validate_overlap(doc)
    summary = render_overlap_summary(doc)
    directory.mkdir(parents=True, exist_ok=True)
    result_path = directory / OVERLAP_FILE
    summary_path = directory / OVERLAP_SUMMARY_FILE
    _replace_text(result_path, json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    _replace_text(summary_path, summary)
    return result_path, summary_path

"""Markdown of the memory comparison the API returns (two versions of one agent on the same seeds).

It reads the stored answer only and adds no figure of its own: no seed, date or league number is
in the answer, so none can be in the report.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

ITEMS = (
    "risk",
    "discipline",
    "diversification",
    "turnover",
    "consistency",
    "formation",
    "role",
    "noise",
    "nudge",
)
_DASH = "-"


def _text(value: Any) -> str:
    return _DASH if value is None else str(value)


def _version(side: Mapping[str, Any]) -> str:
    return _DASH if side["memoryVersion"] is None else f"v{side['memoryVersion']}"


def _score(side: Mapping[str, Any], name: str) -> str:
    score = side["score"]
    return _DASH if score is None else _text(score[name])


def _table(header: Sequence[str], rows: Sequence[Sequence[str]]) -> list[str]:
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return lines


def render_comparison(data: Mapping[str, Any]) -> str:
    summary = data["summary"]
    groups: Sequence[Mapping[str, Any]] = data["groups"]
    lines = [
        f"# Memory comparison: version {data['versionA']} (A) and version {data['versionB']} (B)",
        "",
        "Version 0 means no memory. Rows are closed same-seed groups; seeds, dates and months are not shown.",
        "",
    ]
    if summary["anecdotal"]:
        lines += ["> Fewer than 5 groups: anecdotal, not a measurement.", ""]
    lines += [
        f"- groups: {summary['groups']}",
        f"- B above A (valuation multiple): {summary['bWins']}",
    ]
    mean = summary["meanDiff"]
    if mean is not None:
        lines += [
            f"- mean difference B minus A: valuation multiple {_text(mean['valuationMultiple'])}, "
            f"score {_text(mean['score'])}, result {_text(mean['resultScore'])}, "
            f"process {_text(mean['processScore'])}",
        ]
    lines.append("")
    if not groups:
        lines.append("No closed group has a finished season for both versions.")
        return "\n".join(lines) + "\n"
    header = ["group", "side", "memory", "persona", "multiple", "score", "result", "process"]
    header += ["failed turns", "seed met before"]
    rows = []
    for group in groups:
        for name in ("a", "b"):
            side = group[name]
            rows.append(
                [
                    group["groupLabel"],
                    name.upper(),
                    _version(side),
                    f"p{side['personaVersion']}",
                    _text(side["valuationMultiple"]),
                    _score(side, "score"),
                    _score(side, "resultScore"),
                    _score(side, "processScore"),
                    str(side["failedTurns"]),
                    "yes" if side["experiencedSeed"] else "no",
                ]
            )
    lines += _table(header, rows)
    changed = [
        g["groupLabel"] for g in groups if g["a"]["personaVersion"] != g["b"]["personaVersion"]
    ]
    if changed:
        lines += ["", f"The persona version differs between the sides in: {', '.join(changed)}."]
    if mean is not None:
        lines += ["", "Mean difference of the process items (B minus A, can be negative):", ""]
        items = mean["items"]
        lines += _table(list(ITEMS), [[_text(items[name]) for name in ITEMS]])
    return "\n".join(lines) + "\n"

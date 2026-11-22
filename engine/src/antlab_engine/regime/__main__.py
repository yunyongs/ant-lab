"""Regime reports.

`coverage` prints counts and dates, never values. `transitions` prints counts of the stored month
labels (transition matrix, labelled months, run lengths), never a month or a label per month.
"""

import argparse
import json
import sys
from collections.abc import Callable, Sequence
from datetime import date
from typing import Any

from antlab_engine.bootstrap import transitions
from antlab_engine.bootstrap.simple import replay_upper_bound
from antlab_engine.engine_errors import EngineError
from antlab_engine.history import queries
from antlab_engine.history.reader import read_snapshot
from antlab_engine.regime.adapter import SOURCE_TO_CODE, SourceSeries
from antlab_engine.regime.model import SERIES_SPECS
from antlab_engine.regime.pg_source import SOURCE_CODES
from antlab_engine.settings import DatabaseSettings, SettingsError, replay_start_from_env

MonthLabels = Sequence[tuple[date, str | None]]

# Gap thresholds in days between consecutive reference dates, by series frequency.
GAP_DAYS = {"DAILY": 7, "WEEKLY": 14, "MONTHLY": 35}


def _iso(value: Any) -> str | None:
    return None if value is None else value.isoformat()


def report(rows: list[tuple]) -> dict[str, Any]:
    """One entry per known series; a series with no catalog row or no observation is listed."""
    found: dict[str, dict[str, Any]] = {}
    for (
        code,
        frequency,
        revisable,
        observations,
        reference_dates,
        first,
        last,
        gaps,
        longest,
        max_vintage,
        later,
        rule_dated,
    ) in rows:
        library_revisable = SERIES_SPECS[SOURCE_TO_CODE[SourceSeries(code)]].revisable
        found[code] = {
            "frequency": frequency,
            "observations": observations,
            "referenceDates": reference_dates,
            "first": _iso(first),
            "last": _iso(last),
            "gaps": gaps,
            "longestGapDays": longest,
            "maxVintage": max_vintage,
            "vintagesBeyondFirst": later,
            "ruleDatedRows": rule_dated,
            "unexpectedVintages": (not library_revisable) and later > 0,
            "revisableInCatalog": revisable,
        }
    missing = [c for c in SOURCE_CODES if c not in found]
    empty = [c for c, entry in found.items() if entry["observations"] == 0]
    return {"series": found, "notInCatalog": missing, "noObservations": empty}


def _mean(months: int, runs: int) -> str | None:
    """Mean run length with two decimals, rounded half up in integers."""
    if runs == 0:
        return None
    hundredths = (months * 200 + runs) // (2 * runs)
    return f"{hundredths // 100}.{hundredths % 100:02d}"


def transitions_report(rows: MonthLabels, base_date: date, replay_start: date) -> dict[str, Any]:
    """Counts only. Raises RegimeLabelsMissing when the labels cannot condition a path."""
    months = [m for m, _ in rows]
    labels = {m: label for m, label in rows if label is not None}
    counts = transitions.transition_counts(months, labels, base_date, replay_start)
    transitions.require_enough_labels(counts)
    avail = transitions.replayable_indexes(months, base_date, replay_start)
    stats = transitions.run_stats(avail, transitions.label_indexes(labels, months))
    return {
        "labelledMonths": counts.labelled_months,
        "pairs": counts.pairs,
        "pools": list(transitions.POOLS),
        "counts": [list(row) for row in counts.counts],
        "rowTotals": [counts.row_total(i) for i in range(4)],
        "runs": {p: stats[i][0] for i, p in enumerate(transitions.POOLS)},
        "monthsPerPool": {p: stats[i][1] for i, p in enumerate(transitions.POOLS)},
        "meanRunLength": {
            p: _mean(stats[i][1], stats[i][0]) for i, p in enumerate(transitions.POOLS)
        },
    }


def read_month_labels(base_date: date) -> MonthLabels:
    upper = replay_upper_bound(base_date)
    with read_snapshot(DatabaseSettings.from_env()) as conn:
        rows = conn.execute(queries.MONTHS_SQL, {"upper": upper}).fetchall()
    return [(row[1], row[2]) for row in rows]


def _transitions(base_date: date, source: Callable[[date], MonthLabels] | None) -> int:
    try:
        rows = (source or read_month_labels)(base_date)
        report_ = transitions_report(rows, base_date, replay_start_from_env())
    except (SettingsError, EngineError) as exc:
        print(f"error: {getattr(exc, 'code', 'SETTINGS')}: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report_, indent=2, sort_keys=True))
    return 0


def main(
    argv: list[str] | None = None,
    *,
    month_source: Callable[[date], MonthLabels] | None = None,
) -> int:
    parser = argparse.ArgumentParser(prog="python -m antlab_engine.regime")
    parser.add_argument("command", choices=["coverage", "transitions"])
    parser.add_argument(
        "--base-date", type=date.fromisoformat, help="transitions: season base date"
    )
    args = parser.parse_args(argv)
    if args.command == "transitions":
        if args.base_date is None:
            parser.error("transitions needs --base-date YYYY-MM-DD")
        return _transitions(args.base_date, month_source)
    try:
        with read_snapshot(DatabaseSettings.from_env()) as conn:
            rows = conn.execute(
                queries.REGIME_COVERAGE_SQL,
                {
                    "codes": list(SOURCE_CODES),
                    "daily": GAP_DAYS["DAILY"],
                    "weekly": GAP_DAYS["WEEKLY"],
                    "monthly": GAP_DAYS["MONTHLY"],
                },
            ).fetchall()
    except (SettingsError, EngineError) as exc:
        print(f"error: {getattr(exc, 'code', 'SETTINGS')}: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report(rows), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

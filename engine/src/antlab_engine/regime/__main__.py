"""Coverage report: python -m antlab_engine.regime coverage (counts and dates, never values)."""

import argparse
import json
import sys
from typing import Any

from antlab_engine.engine_errors import EngineError
from antlab_engine.history import queries
from antlab_engine.history.reader import read_snapshot
from antlab_engine.regime.adapter import SOURCE_TO_CODE, SourceSeries
from antlab_engine.regime.model import SERIES_SPECS
from antlab_engine.regime.pg_source import SOURCE_CODES
from antlab_engine.settings import DatabaseSettings, SettingsError

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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m antlab_engine.regime")
    parser.add_argument("command", choices=["coverage"])
    parser.parse_args(argv)
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

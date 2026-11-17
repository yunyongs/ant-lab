"""Smoke check: python -m antlab_engine.history --base-date YYYY-MM-DD (counts only, no dates)."""

import argparse
import json
import sys
from datetime import date

from antlab_engine.engine_errors import EngineError
from antlab_engine.settings import DatabaseSettings, SettingsError

from .reader import PostgresHistoryReader


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m antlab_engine.history")
    parser.add_argument("--base-date", required=True, type=date.fromisoformat)
    args = parser.parse_args(argv)
    try:
        snapshot = PostgresHistoryReader(DatabaseSettings.from_env()).read(args.base_date)
    except (SettingsError, EngineError) as exc:
        print(f"error: {getattr(exc, 'code', 'SETTINGS')}: {exc}", file=sys.stderr)
        return 1
    print(
        json.dumps(
            {
                "role": snapshot.session.role,
                "readOnly": snapshot.session.read_only,
                "isolation": snapshot.session.isolation,
                "months": len(snapshot.months),
                "poolAssets": len(snapshot.pool),
                "returns": len(snapshot.returns),
                "fx": len(snapshot.fx),
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

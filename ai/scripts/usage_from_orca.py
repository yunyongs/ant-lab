#!/usr/bin/env python3
"""Print `subscription-usage/1` JSON from `orca account list --json` (stdlib only).

The agent runner does not know this meter: it runs whatever command ANTLAB_SUBSCRIPTION_USAGE_CMD
names and expects this schema on stdout. A route whose reading is missing or not "ok" is left out,
which the runner treats as "usage unknown" and refuses to start or continue.

    ANTLAB_SUBSCRIPTION_USAGE_CMD="python3 /absolute/path/to/ai/scripts/usage_from_orca.py"
"""

from __future__ import annotations

import json
import subprocess
import sys
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation

SCHEMA = "subscription-usage/1"
ROUTES = {"claude": "claude", "codex": "codex"}
ORCA_COMMAND = ["orca", "account", "list", "--json"]
ORCA_TIMEOUT_S = 30


SESSION_MINUTES = 300
WEEKLY_MINUTES = 10080


def _percent(window: object, minutes: int) -> str | None:
    if not isinstance(window, dict):
        return None
    length = window.get("windowMinutes")
    # A window of the wrong or unknown length is some other limit, never the one asked for.
    if isinstance(length, bool) or not isinstance(length, int | float) or length != minutes:
        return None
    value = window.get("usedPercent")
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    try:
        parsed = Decimal(str(value))
    except InvalidOperation:
        return None
    if not parsed.is_finite() or not 0 <= parsed <= 100:
        return None
    return format(parsed, "f")


def _iso(milliseconds: object) -> str | None:
    if isinstance(milliseconds, bool) or not isinstance(milliseconds, int | float):
        return None
    try:
        return datetime.fromtimestamp(milliseconds / 1000, UTC).isoformat().replace("+00:00", "Z")
    except (OverflowError, OSError, ValueError):
        return None


def convert(orca: object) -> dict:
    """The schema document for an `orca account list --json` result; unreadable routes are omitted."""
    document: dict = {"schema": SCHEMA}
    limits = {}
    if isinstance(orca, dict):
        result = orca.get("result")
        if isinstance(result, dict) and isinstance(result.get("rateLimits"), dict):
            limits = result["rateLimits"]
    for name, key in ROUTES.items():
        entry = limits.get(name)
        if not isinstance(entry, dict) or entry.get("status") not in ("ok", "log"):
            continue
        weekly = _percent(entry.get("weekly"), WEEKLY_MINUTES)
        session = _percent(entry.get("session"), SESSION_MINUTES)
        # No timestamp means unknown age: never stamp it with the current clock.
        as_of = _iso(entry.get("updatedAt"))
        if weekly is None or session is None or as_of is None:
            continue
        reading = {
            "weekly_used_percent": weekly,
            "session_used_percent": session,
            "as_of": as_of,
        }
        for field, window in (("weekly_resets_at", "weekly"), ("session_resets_at", "session")):
            resets = _iso(entry[window].get("resetsAt"))
            if resets is not None:
                reading[field] = resets
        document[key] = reading
    return document


def main() -> int:
    try:
        completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
            ORCA_COMMAND,
            capture_output=True,
            text=True,
            timeout=ORCA_TIMEOUT_S,
            check=False,
        )
        orca = json.loads(completed.stdout) if completed.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired, ValueError):
        orca = None
    json.dump(convert(orca), sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

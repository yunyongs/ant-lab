"""Simple bootstrap month selection `p0-boot6-v1`, a port of antlab.engine.SimpleBootstrap.

Pure functions: no database, settings or clock. If any constant, random-number formula or
candidate rule below changes, the same seed yields a different path, so ENGINE_VERSION must change.
Months are handled as integer indexes (year * 12 + month - 1), which keeps month arithmetic exact.
"""

from collections.abc import Sequence
from datetime import date, datetime

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.engine_errors import InvalidArgument, PathTooLong

BLOCK_LENGTH = 6
ALLOWED_LENGTHS = (12, 24, 60)
MAX_LENGTH = 60
REPLAY_START = date(2007, 1, 1)
ENGINE_VERSION = "p0-boot6-v1"


def _is_date(value: object) -> bool:
    return isinstance(value, date) and not isinstance(value, datetime)


def _index(d: date) -> int:
    return d.year * 12 + d.month - 1


def _first_of_month(index: int) -> date:
    year, month0 = divmod(index, 12)
    return date(year, month0 + 1, 1)


def _require_int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{name} must be an int")
    return value


def replay_upper_bound(base_date: date) -> date:
    """Exclusive upper bound of the replay range: January 1st of the base date's year."""
    if not _is_date(base_date):
        raise TypeError("base_date must be a date")
    return date(base_date.year, 1, 1)


def _present(months: Sequence[date], base_date: date) -> list[int]:
    """Month indexes inside the replay range, ascending; input order does not matter."""
    upper = replay_upper_bound(base_date)
    seen: set[int] = set()
    for start in months:
        if not _is_date(start):
            raise TypeError("months must contain date values")
        if start.day != 1:
            raise ValueError("invalid month list: a month does not start on day 1")
        if start < REPLAY_START or start >= upper:
            continue
        index = _index(start)
        if index in seen:
            raise ValueError("invalid month list: the same month appears twice")
        seen.add(index)
    return sorted(seen)


def _overlaps(start: int, used: set[int]) -> bool:
    return any(start + i in used for i in range(BLOCK_LENGTH))


def generate_months(months: Sequence[date], base_date: date, length: int, seed: int) -> list[date]:
    """First-of-month dates of the path in turn order."""
    _require_int(length, "length")
    rng = SplitMix64(seed)
    present = _present(months, base_date)
    if length > MAX_LENGTH:
        raise PathTooLong(f"Path length {length} turns cannot be built (allowed: 12, 24, 60)")
    if length not in ALLOWED_LENGTHS:
        raise InvalidArgument("Path length must be one of 12, 24, 60")

    present_set = set(present)
    candidates = [s for s in present if all(s + i in present_set for i in range(1, BLOCK_LENGTH))]

    used: set[int] = set()
    previous_end: int | None = None
    path: list[int] = []
    for slot in range(length // BLOCK_LENGTH):
        calendar_month = ((slot * BLOCK_LENGTH) % 12) + 1
        choices = [
            s
            for s in candidates
            if s % 12 + 1 == calendar_month
            and not _overlaps(s, used)
            and (previous_end is None or s != previous_end + 1)
        ]
        if not choices:
            raise PathTooLong(f"The current replay range cannot build a path of {length} turns")
        pick = choices[rng.next_int(len(choices))]
        for i in range(BLOCK_LENGTH):
            path.append(pick + i)
            used.add(pick + i)
        previous_end = pick + BLOCK_LENGTH - 1
    return [_first_of_month(i) for i in path]

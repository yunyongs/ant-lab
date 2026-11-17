"""Synthetic month list shared with the Java tests (SyntheticMonths). Not real history."""

from datetime import date

BASE_DATE = date(2026, 1, 2)


def month_range(first: date, last: date) -> list[date]:
    """First-of-month dates from `first` to `last`, both included."""
    result = []
    year, month = first.year, first.month
    while (year, month) <= (last.year, last.month):
        result.append(date(year, month, 1))
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return result


def sample_shape() -> list[date]:
    """2006-12 (below range) + 2007-01..2012-04 + 2023-01..2026-01 (2026-01 is above range)."""
    return [
        *month_range(date(2006, 12, 1), date(2006, 12, 1)),
        *month_range(date(2007, 1, 1), date(2012, 4, 1)),
        *month_range(date(2023, 1, 1), date(2026, 1, 1)),
    ]


EXT348_REPLAY_START = date(1997, 1, 1)


def ext348() -> list[date]:
    """1996-12 (below range) + 1997-01..2025-12 (348 months) + 2026-01 (above range)."""
    return month_range(date(1996, 12, 1), date(2026, 1, 1))


def gappy() -> list[date]:
    """2007-01..2009-12 without 2008-03, 2008-11 and 2009-06."""
    holes = {date(2008, 3, 1), date(2008, 11, 1), date(2009, 6, 1)}
    return [m for m in month_range(date(2007, 1, 1), date(2009, 12, 1)) if m not in holes]


def tiny() -> list[date]:
    """Exactly 12 months, 2010-01..2010-12."""
    return month_range(date(2010, 1, 1), date(2010, 12, 1))


def two_januaries() -> list[date]:
    """2010-01..2010-06 and 2011-01..2011-06."""
    return [
        *month_range(date(2010, 1, 1), date(2010, 6, 1)),
        *month_range(date(2011, 1, 1), date(2011, 6, 1)),
    ]


def half_recent() -> list[date]:
    """1997-01..2010-12: about half of ext348 and of sample_shape's replayable months."""
    return month_range(date(1997, 1, 1), date(2010, 12, 1))


def history(name: str) -> tuple[list[date], date]:
    """Named synthetic history and its replay start (ext348 starts in 1997, the rest in 2007)."""
    shapes = {
        "sample": (sample_shape(), date(2007, 1, 1)),
        "ext348": (ext348(), EXT348_REPLAY_START),
        "gappy": (gappy(), date(2007, 1, 1)),
        "tiny": (tiny(), date(2007, 1, 1)),
        "two_januaries": (two_januaries(), date(2007, 1, 1)),
    }
    return shapes[name]


def run(year: int, month: int, count: int) -> list[date]:
    """`count` consecutive months starting at year-month."""
    first = year * 12 + month - 1
    result = []
    for i in range(count):
        y, m0 = divmod(first + i, 12)
        result.append(date(y, m0 + 1, 1))
    return result

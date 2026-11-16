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


def run(year: int, month: int, count: int) -> list[date]:
    """`count` consecutive months starting at year-month."""
    first = year * 12 + month - 1
    result = []
    for i in range(count):
        y, m0 = divmod(first + i, 12)
        result.append(date(y, m0 + 1, 1))
    return result

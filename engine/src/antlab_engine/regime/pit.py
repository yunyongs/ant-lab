"""Point-in-time view: the only way the library reads observations.

`PointInTime.snapshot` returns what was known at a date; no method returns a later row, so a
caller cannot use the future by accident.
"""

from collections.abc import Iterable
from datetime import date, timedelta

import numpy as np

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.model import (
    SERIES_SPECS,
    Frequency,
    Observation,
    SeriesCode,
    SeriesView,
    is_plain_date,
)


def month_last_day(day: date) -> date:
    first_of_next = date(day.year + day.month // 12, day.month % 12 + 1, 1)
    return first_of_next - timedelta(days=1)


def add_months(day: date, months: int) -> date:
    """Same day of the month `months` later, clamped to that month's last day."""
    year, month = divmod(day.year * 12 + day.month - 1 + months, 12)
    first = date(year, month + 1, 1)
    return first.replace(day=min(day.day, month_last_day(first).day))


def years_before(day: date, years: int) -> date:
    """The same calendar day `years` earlier (29 February becomes 28 February)."""
    return add_months(day, -12 * years)


def effective_available(obs: Observation) -> date:
    """First usable date: the stored `available_on`, but never before the publication floor."""
    spec = SERIES_SPECS[obs.series]
    if spec.frequency is Frequency.MONTHLY:
        lag = 1 if spec.kr_lag_days is None else spec.kr_lag_days
        floor = month_last_day(obs.ref_date) + timedelta(days=lag)
    else:
        floor = obs.ref_date
    return max(obs.available_on, floor)


class PointInTime:
    __slots__ = ("_rows",)

    def __init__(self, observations: Iterable[Observation]) -> None:
        rows: dict[SeriesCode, list[tuple[date, date, float]]] = {c: [] for c in SeriesCode}
        seen: set[tuple] = set()
        for obs in observations:
            if not isinstance(obs, Observation):
                raise InvalidArgument("PointInTime takes Observation objects")
            available = effective_available(obs)
            key = (obs.series, obs.ref_date)
            if SERIES_SPECS[obs.series].revisable:
                key = (*key, available)
            if key in seen:
                raise InvalidArgument(f"duplicate observation in {obs.series}")
            seen.add(key)
            rows[obs.series].append((obs.ref_date, available, float(obs.value)))
        for series_rows in rows.values():
            series_rows.sort()
        self._rows = rows

    def snapshot(self, series: SeriesCode, as_of: date) -> SeriesView:
        """The series as known on `as_of`: latest vintage per reference date, sorted by date."""
        if not is_plain_date(as_of):
            raise InvalidArgument("as_of must be a date")
        dates: list[date] = []
        values: list[float] = []
        first_seen: list[date] = []
        for ref_date, available, value in self._rows[series]:
            if available > as_of:
                continue
            if dates and dates[-1] == ref_date:
                values[-1] = value  # a later vintage of the same reference date
            else:
                dates.append(ref_date)
                values.append(value)
                first_seen.append(available)
        ordinals = np.array([d.toordinal() for d in dates], dtype=np.int64)
        array = np.array(values, dtype=np.float64)
        ordinals.setflags(write=False)
        array.setflags(write=False)
        # first arrival of the newest reference date: neither a revision nor a late back-fill
        # of an older date makes a dead feed look fresh
        last = first_seen[-1] if first_seen else None
        return SeriesView(series, tuple(dates), ordinals, array, last)

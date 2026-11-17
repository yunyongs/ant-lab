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
    take_dates,
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


class _Rows:
    """One series as sorted arrays: rows by (ref_date, availability), one run per reference date."""

    __slots__ = ("available", "dates", "ordinals", "run", "values")

    def __init__(self, rows: list[tuple[date, date, date, float]]) -> None:
        rows.sort()
        self.dates: tuple[date, ...] = tuple(dict.fromkeys(r[0] for r in rows))
        self.ordinals = np.array([d.toordinal() for d in self.dates], dtype=np.int64)
        position = {d: i for i, d in enumerate(self.dates)}
        self.run = np.array([position[r[0]] for r in rows], dtype=np.int64)
        self.available = np.array([r[1].toordinal() for r in rows], dtype=np.int64)
        self.values = np.array([r[3] for r in rows], dtype=np.float64)


class PointInTime:
    __slots__ = ("_rows",)

    def __init__(self, observations: Iterable[Observation]) -> None:
        rows: dict[SeriesCode, list[tuple[date, date, date, float]]] = {c: [] for c in SeriesCode}
        seen: set[tuple] = set()
        for obs in observations:
            if not isinstance(obs, Observation):
                raise InvalidArgument("PointInTime takes Observation objects")
            available = effective_available(obs)
            key = (obs.series, obs.ref_date)
            if SERIES_SPECS[obs.series].revisable:
                key = (*key, obs.available_on)
            if key in seen:
                raise InvalidArgument(f"duplicate observation in {obs.series}")
            seen.add(key)
            rows[obs.series].append((obs.ref_date, available, obs.available_on, float(obs.value)))
        self._rows = {code: _Rows(series_rows) for code, series_rows in rows.items()}

    def snapshot(self, series: SeriesCode, as_of: date) -> SeriesView:
        """The series as known on `as_of`: latest vintage per reference date, sorted by date."""
        if not is_plain_date(as_of):
            raise InvalidArgument("as_of must be a date")
        data = self._rows[series]
        visible = np.flatnonzero(data.available <= as_of.toordinal())
        if len(visible) == 0:
            empty = np.empty(0, dtype=np.int64)
            return SeriesView(series, (), empty, np.empty(0, dtype=np.float64), None)
        run = data.run[visible]
        newest = np.empty(len(run), dtype=bool)  # last (latest) vintage of each reference date
        newest[-1] = True
        np.not_equal(run[1:], run[:-1], out=newest[:-1])
        oldest = np.empty(len(run), dtype=bool)  # first (earliest) vintage of each reference date
        oldest[0] = True
        oldest[1:] = newest[:-1]
        chosen = run[newest]
        ordinals = data.ordinals[chosen]
        values = data.values[visible[newest]]
        ordinals.setflags(write=False)
        values.setflags(write=False)
        # first arrival of the newest reference date: neither a revision nor a late back-fill
        # of an older date makes a dead feed look fresh
        last = date.fromordinal(int(data.available[visible[oldest][-1]]))
        return SeriesView(series, take_dates(data.dates, chosen), ordinals, values, last)

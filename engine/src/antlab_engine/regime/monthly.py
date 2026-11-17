"""Month labels: the regime of each requested month from the weekly driver (B4, B5).

A month takes the last Saturday step inside it. The schedule always starts on `SCHEDULE_START`
and ends on the last day of the last requested month, so the result for one month does not
depend on which other months are asked for (steps only look backwards).
"""

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from enum import StrEnum
from types import MappingProxyType

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.evaluate import (
    AxisDisplay,
    RegimeSnapshot,
    evaluate,
    month_end_sample,
    weekly_schedule,
)
from antlab_engine.regime.model import Axis, Market, Observation, Phase, Pool, is_plain_date
from antlab_engine.regime.params import DEFAULT_PARAMS, RegimeParams
from antlab_engine.regime.pit import month_last_day

LABEL_VERSION = "m2-rl-v1"  # bump on any change of schedule start, sampling, floors or link
SCHEDULE_START = date(1994, 1, 1)  # a Saturday; 36 months of warm-up before 1997-01
FIRST_YEAR, LAST_YEAR = 1900, 2100
INDICATOR_KEYS: tuple[str, ...] = (*(str(i) for i in range(1, 15)), "14US", "14KR")
ALL_INDICATORS: tuple[str, ...] = tuple(str(i) for i in range(1, 15))


class MonthStatus(StrEnum):
    EVALUATED = "EVALUATED"
    CARRIED = "CARRIED"  # the sample step reused the previous smoothed R or G
    NO_DATA = "NO_DATA"  # no step in the month, or no smoothed R or G yet


@dataclass(frozen=True, slots=True, eq=False)
class MonthRegime:
    """The regime of one month. Probabilities, label, phase and the RATE and GROWTH axes exist
    only when `status` is EVALUATED; FX and SENTIMENT stay as computed."""

    month: date = field(repr=False)
    status: MonthStatus
    axes: Mapping[Axis, AxisDisplay | None]
    p: tuple[float, float, float, float] | None
    label: Pool | None
    phase: Phase | None
    indicator_z: Mapping[str, float | None]  # always the 16 keys of INDICATOR_KEYS
    quality_ok: bool
    stale: tuple[str, ...]
    unavailable: tuple[str, ...]

    def __post_init__(self) -> None:
        present = (self.p is not None, self.label is not None, self.phase is not None)
        if any(present) != all(present):
            raise InvalidArgument("probabilities, label and phase come together")
        if present[0] != (self.status is MonthStatus.EVALUATED):
            raise InvalidArgument("only an evaluated month has probabilities")


def _check_months(months: Sequence[date]) -> None:
    previous: date | None = None
    for month in months:
        if not is_plain_date(month) or month.day != 1:
            raise InvalidArgument("months must be first-of-month dates")
        if not FIRST_YEAR <= month.year <= LAST_YEAR:
            raise InvalidArgument("a month is outside the supported years")
        if previous is not None and month <= previous:
            raise InvalidArgument("months must be strictly increasing")
        previous = month


def _month(month: date, snap: RegimeSnapshot | None) -> MonthRegime:
    if snap is None:
        return MonthRegime(
            month,
            MonthStatus.NO_DATA,
            MappingProxyType(dict.fromkeys(Axis)),
            None,
            None,
            None,
            MappingProxyType(dict.fromkeys(INDICATOR_KEYS)),
            False,
            (),
            ALL_INDICATORS,
        )
    if snap.r is None or snap.g is None:
        status = MonthStatus.NO_DATA
    elif snap.carried:
        status = MonthStatus.CARRIED
    else:
        status = MonthStatus.EVALUATED
    full = status is MonthStatus.EVALUATED
    axes = {
        axis: (snap.display[axis] if full or axis in (Axis.FX, Axis.SENTIMENT) else None)
        for axis in Axis
    }
    return MonthRegime(
        month,
        status,
        MappingProxyType(axes),
        snap.p if full else None,
        snap.label if full else None,
        snap.phase if full else None,
        MappingProxyType({key: snap.indicator_z[key] for key in INDICATOR_KEYS}),
        snap.quality_ok,
        snap.stale,
        snap.unavailable,
    )


def label_months(
    observations: Iterable[Observation],
    months: Sequence[date],
    *,
    calendars: Mapping[Market, frozenset[date]] | None = None,
    params: RegimeParams = DEFAULT_PARAMS,
) -> tuple[MonthRegime, ...]:
    """One `MonthRegime` per requested month, in order. Build observations with the adapter."""
    months = tuple(months)
    _check_months(months)
    if not months:
        return ()
    schedule = weekly_schedule(SCHEDULE_START, month_last_day(months[-1]))
    snapshots = evaluate(observations, schedule, calendars=calendars, params=params)
    return tuple(_month(m, month_end_sample(snapshots, m.year, m.month)) for m in months)

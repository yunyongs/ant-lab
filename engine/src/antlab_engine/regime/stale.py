"""Stale rule (MACRO_ETF §2.4 step 3); a stale indicator is excluded, not decayed.

Daily series are stale after more than 2 business days (weekdays minus the holidays passed in),
weekly and weekly-published series after more than 14 days, monthly series after more than 2
calendar months. Age counts from the newest arrival of any reference date (`last_available_on`).
"""

from collections.abc import Mapping
from datetime import date, timedelta
from enum import StrEnum

from antlab_engine.regime.catalog import INDICATOR_IDS, INDICATOR_INPUTS
from antlab_engine.regime.model import SERIES_SPECS, Frequency, Market, SeriesCode, SeriesView
from antlab_engine.regime.pit import add_months

MAX_BUSINESS_DAYS = 2
MAX_WEEKLY_DAYS = 14
MAX_MONTHLY_MONTHS = 2

Calendars = Mapping[Market, frozenset[date]]


class SeriesState(StrEnum):
    FRESH = "FRESH"
    STALE = "STALE"
    MISSING = "MISSING"


def exceeds_business_days(last: date, as_of: date, holidays: frozenset[date], limit: int) -> bool:
    """True when more than `limit` business days lie in (last, as_of]."""
    count = 0
    day = last
    while day < as_of:
        day += timedelta(days=1)
        if day.weekday() < 5 and day not in holidays:
            count += 1
            if count > limit:
                return True
    return False


def series_state(view: SeriesView, as_of: date, calendars: Calendars | None = None) -> SeriesState:
    last = view.last_available_on
    if last is None:
        return SeriesState.MISSING
    spec = SERIES_SPECS[view.series]
    if spec.stale_exempt:
        return SeriesState.FRESH
    if spec.frequency is Frequency.MONTHLY:
        old = as_of > add_months(last, MAX_MONTHLY_MONTHS)
    elif spec.frequency is Frequency.WEEKLY or spec.weekly_publication:
        old = (as_of - last).days > MAX_WEEKLY_DAYS
    else:
        holidays = (calendars or {}).get(spec.market, frozenset())
        old = exceeds_business_days(last, as_of, holidays, MAX_BUSINESS_DAYS)
    return SeriesState.STALE if old else SeriesState.FRESH


def series_states(
    views: Mapping[SeriesCode, SeriesView], as_of: date, calendars: Calendars | None = None
) -> dict[SeriesCode, SeriesState]:
    return {code: series_state(view, as_of, calendars) for code, view in views.items()}


def stale_indicators(
    views: Mapping[SeriesCode, SeriesView], as_of: date, calendars: Calendars | None = None
) -> frozenset[int]:
    """Indicator ids with at least one stale input series (a missing series is not stale).

    Indicator 14 counts once, for the quality flag. A term is excluded by its own inputs only
    (`term_signals`), so a Korean data gap does not remove the US credit term 14US.
    """
    states = series_states(views, as_of, calendars)
    return frozenset(
        i
        for i in INDICATOR_IDS
        if any(states[code] is SeriesState.STALE for code in INDICATOR_INPUTS[i])
    )

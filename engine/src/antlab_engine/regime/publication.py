"""Publication floors: the first date a reference-dated value may be used (MACRO_ETF §2.3, §4.2).

All dates are source-local calendar dates. The weekly driver evaluates on Saturdays (KST
morning), so a step sees everything dated up to and including the Friday before it. A floor
never moves a value earlier than its reference date; a stored publication date that is later
still wins (`adapter.to_observations`).

US calendars are optional: with weekly Saturday steps a missing holiday cannot move a US daily
value into an earlier step, because the next weekday after a Friday is already past Saturday.
"""

from collections.abc import Callable, Mapping
from datetime import date, timedelta
from types import MappingProxyType

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.model import SERIES_SPECS, Market, SeriesCode, is_plain_date
from antlab_engine.regime.pit import month_last_day

Calendars = Mapping[Market, frozenset[date]]

SATURDAY = 5
MONDAY_OFFSET_DAYS = 7  # next Monday strictly after the reference date
ICSA_LAG_DAYS = 5
CPI_LAG_DAYS = 10
SAHM_LAG_DAYS = 7


def _is_business_day(day: date, holidays: frozenset[date]) -> bool:
    return day.weekday() < SATURDAY and day not in holidays


def _on_or_after(day: date, holidays: frozenset[date]) -> date:
    while not _is_business_day(day, holidays):
        day += timedelta(days=1)
    return day


def _us_holidays(calendars: Calendars | None) -> frozenset[date]:
    return (calendars or {}).get(Market.US, frozenset())


def _next_us_business_day(ref: date, calendars: Calendars | None) -> date:
    return _on_or_after(ref + timedelta(days=1), _us_holidays(calendars))


def _following_monday(ref: date, calendars: Calendars | None) -> date:
    monday = ref + timedelta(days=MONDAY_OFFSET_DAYS - ref.weekday())
    return _on_or_after(monday, _us_holidays(calendars))


def _claims(ref: date, calendars: Calendars | None) -> date:
    if ref.weekday() != SATURDAY:
        raise InvalidArgument("a weekly jobless-claims reference date must be a Saturday")
    return _on_or_after(ref + timedelta(days=ICSA_LAG_DAYS), _us_holidays(calendars))


def _month_end_plus(days: int) -> Callable[[date, Calendars | None], date]:
    return lambda ref, _calendars: month_last_day(ref) + timedelta(days=days)


def _same_day(ref: date, _calendars: Calendars | None) -> date:
    return ref


_RULES: dict[SeriesCode, Callable[[date, Calendars | None], date]] = {
    SeriesCode.US_DGS2: _next_us_business_day,
    SeriesCode.US_T10Y3M: _next_us_business_day,
    SeriesCode.US_BAA10Y: _next_us_business_day,
    SeriesCode.US_VIX: _next_us_business_day,
    SeriesCode.US_DTWEXBGS: _following_monday,
    SeriesCode.US_ICSA: _claims,
    SeriesCode.US_CPILFESL: _month_end_plus(CPI_LAG_DAYS),
    SeriesCode.US_SAHM: _month_end_plus(SAHM_LAG_DAYS),
    SeriesCode.KR_CPI: _month_end_plus(SERIES_SPECS[SeriesCode.KR_CPI].kr_lag_days or 0),
    SeriesCode.KR_EXPORTS: _month_end_plus(SERIES_SPECS[SeriesCode.KR_EXPORTS].kr_lag_days or 0),
    SeriesCode.KR_CLI: _month_end_plus(SERIES_SPECS[SeriesCode.KR_CLI].kr_lag_days or 0),
    SeriesCode.KR_KTB3Y: _same_day,
    SeriesCode.KR_BASE_RATE: _same_day,
    SeriesCode.KR_USDKRW: _same_day,
    SeriesCode.KR_CORP_AA: _same_day,
}
FLOOR_RULES: Mapping[SeriesCode, Callable[[date, Calendars | None], date]] = MappingProxyType(
    _RULES
)


def publication_floor(
    series: SeriesCode, ref_date: date, calendars: Calendars | None = None
) -> date:
    """First usable date of a value of `series` dated `ref_date`; never before `ref_date`."""
    if not isinstance(series, SeriesCode):
        raise InvalidArgument("a publication floor needs a SeriesCode")
    if not is_plain_date(ref_date):
        raise InvalidArgument("ref_date must be a date")
    return FLOOR_RULES[series](ref_date, calendars)

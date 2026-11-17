"""Publication floors (TA1): first usable date of each series, by hand-computed dates."""

from datetime import date, timedelta

import pytest

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import SeriesCode, publication_floor
from antlab_engine.regime.model import SERIES_SPECS, Market
from antlab_engine.regime.publication import FLOOR_RULES

S = SeriesCode
D = date
NONE: dict = {}


def us(*days: date) -> dict:
    return {Market.US: frozenset(days)}


US_DAILY = (S.US_DGS2, S.US_T10Y3M, S.US_BAA10Y, S.US_VIX)

GOLDEN = [
    # next US business day after the reference date, all four daily series alike
    *[(code, D(2024, 3, 8), NONE, D(2024, 3, 11)) for code in US_DAILY],  # Friday -> Monday
    *[(code, D(2024, 3, 7), NONE, D(2024, 3, 8)) for code in US_DAILY],  # Thursday -> Friday
    *[(code, D(2024, 3, 8), us(D(2024, 3, 11)), D(2024, 3, 12)) for code in US_DAILY],
    (S.US_DGS2, D(2024, 5, 24), us(D(2024, 5, 27)), D(2024, 5, 28)),  # before Memorial Day
    (S.US_DGS2, D(2024, 4, 30), NONE, D(2024, 5, 1)),  # month end
    (S.US_DGS2, D(2024, 12, 31), NONE, D(2025, 1, 1)),  # December
    (S.US_DGS2, D(2024, 12, 31), us(D(2025, 1, 1)), D(2025, 1, 2)),
    (S.US_DGS2, D(2024, 2, 29), NONE, D(2024, 3, 1)),  # 29 February
    # broad dollar index: the Monday that follows, then a business day on or after it
    (S.US_DTWEXBGS, D(2024, 3, 8), NONE, D(2024, 3, 11)),  # Friday
    (S.US_DTWEXBGS, D(2024, 3, 5), NONE, D(2024, 3, 11)),  # Tuesday: not the next weekday
    (S.US_DTWEXBGS, D(2024, 3, 4), NONE, D(2024, 3, 11)),  # Monday: the Monday after
    (S.US_DTWEXBGS, D(2024, 3, 10), NONE, D(2024, 3, 11)),  # Sunday
    (S.US_DTWEXBGS, D(2024, 3, 8), us(D(2024, 3, 11)), D(2024, 3, 12)),
    # jobless claims: week-ending Saturday + 5 days, rolled to a business day
    (S.US_ICSA, D(2024, 3, 9), NONE, D(2024, 3, 14)),
    (S.US_ICSA, D(2024, 11, 23), us(D(2024, 11, 28)), D(2024, 11, 29)),  # Thanksgiving
    (S.US_ICSA, D(2024, 2, 24), NONE, D(2024, 2, 29)),
    # core CPI: month end + 10 days; Sahm indicator: month end + 7 days
    (S.US_CPILFESL, D(2024, 2, 1), NONE, D(2024, 3, 10)),
    (S.US_CPILFESL, D(2023, 2, 1), NONE, D(2023, 3, 10)),
    (S.US_CPILFESL, D(2024, 12, 1), NONE, D(2025, 1, 10)),
    (S.US_SAHM, D(2024, 2, 1), NONE, D(2024, 3, 7)),
    (S.US_SAHM, D(2024, 12, 1), NONE, D(2025, 1, 7)),
    # Korean monthly series: month end + 7 / 15 / 35 days
    (S.KR_CPI, D(2024, 2, 1), NONE, D(2024, 3, 7)),
    (S.KR_EXPORTS, D(2024, 2, 1), NONE, D(2024, 3, 15)),
    (S.KR_CLI, D(2024, 2, 1), NONE, D(2024, 4, 4)),
    # Korean daily series: usable the same day
    *[
        (code, D(2024, 3, 8), us(D(2024, 3, 8)), D(2024, 3, 8))
        for code in (S.KR_KTB3Y, S.KR_BASE_RATE, S.KR_USDKRW, S.KR_CORP_AA)
    ],
]


@pytest.mark.parametrize(("series", "ref", "calendars", "expected"), GOLDEN)
def test_floor_table(series, ref, calendars, expected):
    assert publication_floor(series, ref, calendars) == expected


def test_every_series_has_one_rule():
    assert set(FLOOR_RULES) == set(SeriesCode)


def test_the_calendar_argument_is_optional_and_us_only():
    friday = D(2024, 3, 8)
    assert publication_floor(S.US_DGS2, friday) == publication_floor(S.US_DGS2, friday, {})
    holiday_week = {Market.KR: frozenset({D(2024, 3, 11)})}  # a Korean holiday moves nothing
    assert publication_floor(S.US_DGS2, friday, holiday_week) == D(2024, 3, 11)
    assert publication_floor(S.KR_KTB3Y, friday, us(friday)) == friday


def test_claims_need_a_saturday_reference_date():
    with pytest.raises(InvalidArgument, match="Saturday") as error:
        publication_floor(S.US_ICSA, D(2024, 3, 8))
    assert "2024" not in str(error.value)


def test_bad_arguments_are_rejected():
    with pytest.raises(InvalidArgument):
        publication_floor("US_DGS2", D(2024, 3, 8))  # type: ignore[arg-type]
    with pytest.raises(InvalidArgument):
        publication_floor(S.US_DGS2, "2024-03-08")  # type: ignore[arg-type]


def reference_days(code: SeriesCode) -> list[date]:
    days = [D(2023, 12, 1) + timedelta(days=i) for i in range(430)]
    spec = SERIES_SPECS[code]
    if code is S.US_ICSA:
        return [d for d in days if d.weekday() == 5]
    if spec.frequency.value == "MONTHLY":
        return [d for d in days if d.day == 1]
    return days


@pytest.mark.parametrize("code", list(SeriesCode))
def test_a_floor_is_never_before_the_reference_date(code):
    holidays = us(D(2024, 1, 1), D(2024, 7, 4), D(2024, 12, 25))
    assert all(publication_floor(code, d, holidays) >= d for d in reference_days(code))


@pytest.mark.parametrize("code", [*US_DAILY, S.US_DTWEXBGS, S.US_ICSA, S.US_CPILFESL, S.US_SAHM])
def test_a_us_value_is_never_usable_on_its_reference_date(code):
    assert all(publication_floor(code, d) > d for d in reference_days(code))


@pytest.mark.parametrize("code", [*US_DAILY, S.US_DTWEXBGS])
def test_a_friday_us_value_is_not_usable_on_the_saturday_after(code):
    fridays = [d for d in reference_days(code) if d.weekday() == 4]
    assert fridays
    assert all(publication_floor(code, d) > d + timedelta(days=1) for d in fridays)


def test_the_floors_land_on_business_days_when_a_calendar_is_given():
    holidays = {D(2024, 7, 4), D(2024, 9, 2), D(2024, 11, 28)}
    calendars = us(*holidays)
    for code in (*US_DAILY, S.US_DTWEXBGS, S.US_ICSA):
        for day in reference_days(code):
            floor = publication_floor(code, day, calendars)
            assert floor.weekday() < 5 and floor not in holidays

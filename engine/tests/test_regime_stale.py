from datetime import date, timedelta

import pytest
from regime_synthetic import FAR, obs, panel

from antlab_engine.regime import (
    PointInTime,
    SeriesCode,
    SeriesState,
    TermStatus,
    series_state,
    stale_indicators,
    term_signals,
)
from antlab_engine.regime.model import Market
from antlab_engine.regime.pit import add_months
from antlab_engine.regime.stale import exceeds_business_days

S = SeriesCode
MON = date(2024, 1, 8)  # a Monday


def state(code, ref, as_of, *, available=None, holidays=None):
    pit = PointInTime([obs(code, ref, 1.0, available)])
    return series_state(pit.snapshot(code, as_of), as_of, holidays)


def test_daily_series_is_stale_after_three_business_days_not_two():
    """TA16."""
    assert state(S.US_DGS2, MON, MON + timedelta(days=2)) is SeriesState.FRESH
    assert state(S.US_DGS2, MON, MON + timedelta(days=3)) is SeriesState.STALE


def test_weekends_do_not_count():
    friday = date(2024, 1, 12)
    assert state(S.US_DGS2, friday, date(2024, 1, 16)) is SeriesState.FRESH  # Mon, Tue
    assert state(S.US_DGS2, friday, date(2024, 1, 17)) is SeriesState.STALE


def test_a_holiday_of_the_same_market_does_not_count():
    friday, holiday = date(2024, 1, 12), date(2024, 1, 15)
    us = {Market.US: frozenset({holiday})}
    assert state(S.US_DGS2, friday, date(2024, 1, 17), holidays=us) is SeriesState.FRESH
    assert state(S.US_DGS2, friday, date(2024, 1, 18), holidays=us) is SeriesState.STALE
    kr = {Market.KR: frozenset({holiday})}  # a Korean holiday does not excuse a US series
    assert state(S.US_DGS2, friday, date(2024, 1, 17), holidays=kr) is SeriesState.STALE
    assert state(S.KR_KTB3Y, friday, date(2024, 1, 17), holidays=kr) is SeriesState.FRESH


def test_weekly_and_weekly_published_series_allow_fourteen_days():
    last = date(2024, 1, 5)
    for code in (S.US_ICSA, S.US_DTWEXBGS):
        assert state(code, last, last + timedelta(days=14)) is SeriesState.FRESH
        assert state(code, last, last + timedelta(days=15)) is SeriesState.STALE


def test_monthly_series_allow_two_months():
    # US_SAHM for January is usable from 2024-02-01, so it is fresh through 2024-04-01
    assert state(S.US_SAHM, date(2024, 1, 1), date(2024, 4, 1)) is SeriesState.FRESH
    assert state(S.US_SAHM, date(2024, 1, 1), date(2024, 4, 2)) is SeriesState.STALE


def test_the_policy_rate_is_never_stale_and_a_missing_series_is_missing():
    assert state(S.KR_BASE_RATE, date(2010, 1, 4), date(2024, 1, 4)) is SeriesState.FRESH
    empty = PointInTime([]).snapshot(S.US_VIX, FAR)
    assert series_state(empty, date(2024, 1, 4)) is SeriesState.MISSING


def test_business_day_counter_stops_early_and_handles_equal_dates():
    assert not exceeds_business_days(MON, MON, frozenset(), 2)
    assert exceeds_business_days(date(2000, 1, 3), date(2024, 1, 3), frozenset(), 2)


def test_add_months_is_what_the_monthly_rule_uses():
    assert add_months(date(2024, 12, 31), 2) == date(2025, 2, 28)


def test_a_stale_input_excludes_the_terms_that_read_it():
    """KR_KTB3Y stale: indicators 3, 12 and 14 count as stale, but 14US reads only BAA10Y."""
    base = panel()
    items = [o for o in base if o.series is not S.KR_KTB3Y]
    items += [o for o in base if o.series is S.KR_KTB3Y and o.ref_date <= date(2025, 8, 1)]
    pit = PointInTime(items)
    as_of = date(2025, 8, 15)
    views = {code: pit.snapshot(code, as_of) for code in S}
    assert stale_indicators(views, as_of) == {3, 12, 14}
    status = {s.term_id: s.status for s in term_signals(pit, as_of)}
    stale_terms = {t for t, st in status.items() if st is TermStatus.STALE}
    assert stale_terms == {"3a", "3b", "12a", "12b", "14KR"}
    assert status["14US"] is status["1a"] is TermStatus.OK


def test_a_stale_korean_credit_series_does_not_remove_the_us_credit_term():
    base = panel()
    items = [o for o in base if o.series is not S.KR_CORP_AA]
    items += [o for o in base if o.series is S.KR_CORP_AA and o.ref_date <= date(2025, 8, 1)]
    pit = PointInTime(items)
    as_of = date(2025, 8, 15)
    views = {code: pit.snapshot(code, as_of) for code in S}
    assert stale_indicators(views, as_of) == {14}
    status = {s.term_id: s.status for s in term_signals(pit, as_of)}
    assert status["14KR"] is TermStatus.STALE
    assert status["14US"] is TermStatus.OK and status["3a"] is TermStatus.OK


def test_a_missing_series_gives_missing_terms_not_stale_ones():
    items = [o for o in panel() if o.series is not S.US_VIX]
    status = {s.term_id: s.status for s in term_signals(PointInTime(items), date(2025, 8, 15))}
    assert status["13a"] is status["13b"] is TermStatus.MISSING
    assert status["1a"] is TermStatus.OK


def test_the_policy_rate_never_makes_an_indicator_stale():
    items = [o for o in panel() if o.series is not S.KR_BASE_RATE]
    items.append(obs(S.KR_BASE_RATE, date(2017, 9, 4), 3.0))
    pit = PointInTime(items)
    as_of = date(2025, 8, 15)
    views = {code: pit.snapshot(code, as_of) for code in S}
    assert 3 not in stale_indicators(views, as_of)
    assert {s.term_id: s.status for s in term_signals(pit, as_of)}["3b"] is TermStatus.OK


@pytest.mark.parametrize("code", [S.US_DGS2, S.KR_CPI])
def test_a_series_is_fresh_on_its_own_availability_date(code):
    ref = date(2024, 3, 1) if code is S.KR_CPI else MON
    pit = PointInTime([obs(code, ref, 1.0)])
    on_day = date(2024, 4, 7) if code is S.KR_CPI else MON
    assert series_state(pit.snapshot(code, on_day), on_day) is SeriesState.FRESH

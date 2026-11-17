import math
from datetime import date, timedelta

import numpy as np
import pytest
from regime_synthetic import daily, monthly, obs, view_of, weekdays

from antlab_engine.regime import TERMS_BY_ID, SeriesCode
from antlab_engine.regime import transforms as tf
from antlab_engine.regime.pit import PointInTime

S = SeriesCode


def last(code, items):
    """Latest value of a catalog term over the given observations."""
    views = {c: PointInTime(items).snapshot(c, date(2100, 1, 1)) for c in S}
    return TERMS_BY_ID[code].transform(views).values[-1]


def test_difference_and_mean_transforms_on_a_ramp():
    """TA4: ramp with slope s gives delta63 = 63 s and x - MA126 = 62.5 s."""
    s = 0.5
    ramp = np.arange(300.0) * s
    assert tf.delta(ramp, 63)[-1] == pytest.approx(63 * s, abs=1e-12)
    assert math.isnan(tf.delta(ramp, 63)[62]) and not math.isnan(tf.delta(ramp, 63)[63])
    assert tf.minus_mean(ramp, 126)[-1] == pytest.approx(62.5 * s, abs=1e-12)
    assert math.isnan(tf.minus_mean(ramp, 126)[124])
    assert not math.isnan(tf.minus_mean(ramp, 126)[125])


def test_ratio_transforms_on_constants_and_ramps():
    flat = np.full(300, 7.0)
    for out in (
        tf.mean_ratio_minus_one(flat, 4, 26),
        tf.over_mean_minus_one(flat, 200),
        tf.over_mean_minus_one(flat, 50),
        tf.pct_change(flat, 63),
    ):
        assert out[-1] == pytest.approx(0.0, abs=1e-15)
    ramp = np.arange(1.0, 101.0)  # 1..100
    assert tf.mean_ratio_minus_one(ramp, 4, 26)[-1] == pytest.approx(98.5 / 87.5 - 1, abs=1e-14)
    assert tf.over_mean_minus_one(ramp, 50)[-1] == pytest.approx(100 / 75.5 - 1, abs=1e-14)
    assert tf.pct_change(ramp, 63)[-1] == pytest.approx(100 / 37 - 1, abs=1e-14)


def test_division_by_zero_gives_nan_not_inf():
    zeros = np.zeros(100)
    assert np.isnan(tf.pct_change(zeros, 63)[-1])
    assert np.isnan(tf.over_mean_minus_one(zeros, 50)[-1])


def test_core_cpi_acceleration():
    """TA5: constant growth gives 0; a one-off jump gives the hand value."""
    g = 0.002
    grown = monthly(S.US_CPILFESL, date(2018, 1, 1), 40, lambda i: 100 * (1 + g) ** i)
    assert last("2", grown) == pytest.approx(0.0, abs=1e-12)
    jump = monthly(S.US_CPILFESL, date(2018, 1, 1), 40, lambda i: 100.0 if i < 39 else 101.0)
    assert last("2", jump) == pytest.approx(1.01**4 - 1 - 0.01, abs=1e-12)


def test_korean_cpi_year_over_year_and_its_change():
    """TA6."""
    rising = monthly(S.KR_CPI, date(2018, 1, 1), 40, lambda i: 100.0 + i)
    yoy = lambda i: (100.0 + i) / (88.0 + i) - 1  # noqa: E731 - month index i, 12 months back
    assert last("4a", rising) == pytest.approx(yoy(39), abs=1e-14)
    assert last("4b", rising) == pytest.approx(yoy(39) - yoy(36), abs=1e-14)


def test_exports_three_month_average_year_over_year():
    g = 0.01
    items = monthly(S.KR_EXPORTS, date(2018, 1, 1), 40, lambda i: 100 * (1 + g) ** i)
    assert last("9", items) == pytest.approx((1 + g) ** 12 - 1, abs=1e-12)


def test_leading_index_terms_use_the_change_and_the_level_minus_100():
    items = monthly(S.KR_CLI, date(2018, 1, 1), 40, lambda i: 99.0 + 0.1 * i)
    assert last("8a", items) == pytest.approx(0.3, abs=1e-12)
    assert last("8b", items) == pytest.approx(99.0 + 3.9 - 100.0, abs=1e-12)


def test_exports_moving_average_covers_three_months():
    """A one-off month: the latest average is (130 + 100 + 100) / 3 over a flat 100."""
    items = monthly(S.KR_EXPORTS, date(2018, 1, 1), 40, lambda i: 130.0 if i == 39 else 100.0)
    assert last("9", items) == pytest.approx(110.0 / 100.0 - 1, abs=1e-12)


def test_base_rate_gap_joins_the_last_rate_known_at_each_date():
    """TA7: the rate changes mid-window, once on a day with no bond quote."""
    days = weekdays(date(2024, 1, 1), 10)  # Mon 1 Jan .. Fri 12 Jan
    bonds = [obs(S.KR_KTB3Y, d, 3.0) for d in days]
    rates = [
        obs(S.KR_BASE_RATE, date(2023, 12, 1), 2.0),
        obs(S.KR_BASE_RATE, date(2024, 1, 6), 2.5),  # a Saturday: applies from Monday 8 Jan
    ]
    views = {c: view_of(bonds + rates, c) for c in S}
    gap = TERMS_BY_ID["3b"].transform(views)
    assert gap.values.tolist() == [1.0] * 5 + [0.5] * 5
    assert len(gap.ref_dates) == 10


def test_a_bond_quote_before_the_first_base_rate_has_no_gap_value():
    bonds = [obs(S.KR_KTB3Y, date(2024, 1, 2), 3.0)]
    rates = [obs(S.KR_BASE_RATE, date(2024, 1, 3), 2.0)]
    views = {c: view_of(bonds + rates, c) for c in S}
    assert np.isnan(TERMS_BY_ID["3b"].transform(views).values[0])


def test_korea_us_spread_uses_the_previous_us_value_on_a_us_holiday():
    """TA8: no US quote on Wed 3 Jan; the spread uses Tue's."""
    days = weekdays(date(2024, 1, 1), 5)
    kr = [obs(S.KR_KTB3Y, d, 3.0 + 0.1 * i) for i, d in enumerate(days)]
    us = [obs(S.US_DGS2, d, 2.0 + i) for i, d in enumerate(days) if i != 2]
    views = {c: view_of(kr + us, c) for c in S}
    spread = TERMS_BY_ID["12a"].transform(views)
    assert spread.values.tolist() == pytest.approx([1.0, 0.1, 0.2, -1.7, -2.6])


def test_korea_us_spread_change_over_126_observations():
    kr = daily(S.KR_KTB3Y, date(2020, 1, 1), 140, lambda i: 3.0 + 0.01 * i)
    us = daily(S.US_DGS2, date(2020, 1, 1), 140, lambda i: 2.0)
    views = {c: view_of(kr + us, c) for c in S}
    change = TERMS_BY_ID["12b"].transform(views).values
    assert change[-1] == pytest.approx(1.26, abs=1e-12)
    assert np.isnan(change[125]) and not np.isnan(change[126])


def test_corporate_spread_uses_only_dates_both_series_have():
    days = weekdays(date(2024, 1, 1), 70)
    aa = [obs(S.KR_CORP_AA, d, 5.0 + 0.01 * i) for i, d in enumerate(days)]
    ktb = [obs(S.KR_KTB3Y, d, 3.0) for i, d in enumerate(days) if i != 10]
    views = {c: view_of(aa + ktb, c) for c in S}
    joined = tf.same_date_spread(views[S.KR_CORP_AA], views[S.KR_KTB3Y])
    assert len(joined.ref_dates) == 69
    assert days[10] not in joined.ref_dates
    credit = TERMS_BY_ID["14KR"].transform(views)
    assert credit.values[-1] == pytest.approx(
        0.64, abs=1e-12
    )  # joined index 68 vs 5 (day 10 absent)


def test_a_missing_month_makes_the_point_missing_not_interpolated():
    """TA14: months 0-4 and 6-20 (month 5 missing)."""
    months = [i for i in range(21) if i != 5]
    values = np.array([100.0 + i for i in months])
    index = np.array(months, dtype=np.int64)
    shifted = tf.month_shift(index, values, 3)
    by_month = dict(zip(months, shifted, strict=True))
    assert math.isnan(by_month[8])  # month 5 is missing
    assert by_month[7] == 104.0 and by_month[10] == 107.0
    assert math.isnan(by_month[2])  # before the start
    yoy = tf.year_over_year(index, values)
    assert np.isnan(yoy[months.index(17)])  # needs month 5
    assert yoy[months.index(18)] == pytest.approx(118.0 / 106.0 - 1, abs=1e-14)


def test_percentile_input_is_the_raw_level():
    items = daily(S.US_VIX, date(2020, 1, 1), 5, lambda i: 10.0 + i)
    views = {c: view_of(items, c) for c in S}
    assert TERMS_BY_ID["13b"].transform(views).values.tolist() == [10.0, 11.0, 12.0, 13.0, 14.0]
    assert TERMS_BY_ID["13b"].percentile and not TERMS_BY_ID["13a"].percentile


def test_rolling_mean_depends_only_on_its_own_window():
    rng = np.random.default_rng(3)
    base = rng.normal(size=500)
    changed = base.copy()
    changed[:100] += 1000.0
    a, b = tf.rolling_mean(base, 126), tf.rolling_mean(changed, 126)
    assert a[225:].tobytes() == b[225:].tobytes()
    assert a[224] != b[224]


def test_weekday_helper_skips_weekends():
    days = weekdays(date(2024, 1, 5), 3)  # a Friday
    assert days == [date(2024, 1, 5), date(2024, 1, 8), date(2024, 1, 9)]
    assert days[1] - days[0] == timedelta(days=3)

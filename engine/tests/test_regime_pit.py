from datetime import date, datetime, timedelta
from decimal import Decimal

import numpy as np
import pytest
from regime_synthetic import FAR, obs, view_of

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import Observation, PointInTime, SeriesCode, effective_available
from antlab_engine.regime.pit import add_months, month_last_day, years_before

MARCH = date(2020, 3, 1)  # month end 2020-03-31


@pytest.mark.parametrize(
    ("series", "lag"),
    [
        (SeriesCode.KR_CPI, 7),
        (SeriesCode.KR_EXPORTS, 15),
        (SeriesCode.KR_CLI, 35),
        (SeriesCode.US_SAHM, 1),
        (SeriesCode.US_CPILFESL, 1),
    ],
)
def test_monthly_value_is_usable_from_month_end_plus_lag(series, lag):
    """TA1: boundary day invisible the day before, visible on the day."""
    first_day = date(2020, 3, 31) + timedelta(days=lag)
    pit = PointInTime([obs(series, MARCH, 1.0, available=MARCH)])
    assert len(pit.snapshot(series, first_day - timedelta(days=1))) == 0
    assert len(pit.snapshot(series, first_day)) == 1


def test_a_later_available_on_wins_over_the_floor():
    late = date(2020, 6, 1)
    item = obs(SeriesCode.KR_CPI, MARCH, 1.0, available=late)
    assert effective_available(item) == late
    assert len(view_of([item], SeriesCode.KR_CPI, late - timedelta(days=1))) == 0


def test_daily_and_weekly_values_are_usable_from_available_on():
    day = date(2020, 3, 4)
    pit = PointInTime(
        [
            obs(SeriesCode.US_DGS2, day, 1.0, available=day + timedelta(days=3)),
            obs(SeriesCode.US_ICSA, day, 2.0),
        ]
    )
    assert len(pit.snapshot(SeriesCode.US_DGS2, day + timedelta(days=2))) == 0
    assert len(pit.snapshot(SeriesCode.US_DGS2, day + timedelta(days=3))) == 1
    assert len(pit.snapshot(SeriesCode.US_ICSA, day)) == 1


def test_a_late_first_release_is_invisible_until_it_arrives():
    """A reference date in the past that is published late does not appear before then."""
    old, late = date(2020, 1, 6), date(2020, 2, 3)
    pit = PointInTime(
        [obs(SeriesCode.US_VIX, old, 1.0), obs(SeriesCode.US_VIX, date(2020, 1, 7), 2.0)]
        + [obs(SeriesCode.US_VIX, date(2020, 1, 8), 3.0, available=late)]
    )
    assert [d.day for d in pit.snapshot(SeriesCode.US_VIX, date(2020, 1, 31)).ref_dates] == [6, 7]
    assert len(pit.snapshot(SeriesCode.US_VIX, late)) == 3


def test_negative_cases_raise_invalid_argument():
    """TA2."""
    day = date(2020, 3, 4)
    nan, inf = Decimal("NaN"), Decimal("Infinity")
    bad = [
        lambda: Observation(SeriesCode.US_DGS2, day, Decimal(1), day - timedelta(days=1)),
        lambda: Observation(SeriesCode.US_SAHM, date(2020, 3, 2), Decimal(1), date(2020, 4, 1)),
        lambda: Observation(SeriesCode.US_DGS2, day, nan, day),
        lambda: Observation(SeriesCode.US_DGS2, day, inf, day),
        lambda: Observation(SeriesCode.US_DGS2, day, Decimal("sNaN"), day),
        lambda: Observation(SeriesCode.US_DGS2, day, Decimal("1E400"), day),
        lambda: Observation(SeriesCode.US_DGS2, day, 1.5, day),
        lambda: Observation(SeriesCode.US_DGS2, day, 1, day),
        lambda: Observation("US_DGS2", day, Decimal(1), day),
        lambda: Observation(SeriesCode.US_DGS2, datetime(2020, 3, 4), Decimal(1), day),
        lambda: PointInTime([obs(SeriesCode.US_DGS2, day, 1.0), obs(SeriesCode.US_DGS2, day, 2.0)]),
        lambda: PointInTime(["not an observation"]),
        lambda: PointInTime([]).snapshot(SeriesCode.US_DGS2, datetime(2020, 3, 4)),
    ]
    for build in bad:
        with pytest.raises(InvalidArgument):
            build()


def test_a_duplicate_vintage_with_the_same_availability_is_ambiguous():
    items = [obs(SeriesCode.US_ICSA, MARCH, 1.0), obs(SeriesCode.US_ICSA, MARCH, 2.0)]
    with pytest.raises(InvalidArgument):
        PointInTime(items)


def test_error_messages_carry_no_dates_or_values():
    day = date(2020, 3, 4)
    with pytest.raises(InvalidArgument) as caught:
        Observation(SeriesCode.US_DGS2, day, Decimal("12.3456"), day - timedelta(days=1))
    assert "2020" not in str(caught.value)
    assert "12.3456" not in str(caught.value)


def test_revisable_series_show_the_latest_vintage_known():
    """TA3."""
    d1, d2, d3 = date(2020, 4, 10), date(2020, 5, 8), date(2020, 6, 5)
    ref = date(2020, 3, 7)
    pit = PointInTime(
        [
            obs(SeriesCode.US_ICSA, ref, 100.0, available=d1),
            obs(SeriesCode.US_ICSA, ref, 110.0, available=d2),
            obs(SeriesCode.US_ICSA, ref, 120.0, available=d3),
        ]
    )
    shown = {
        d: pit.snapshot(SeriesCode.US_ICSA, d).values.tolist()
        for d in (d1 - timedelta(days=1), d1, d2 - timedelta(days=1), d2, d3)
    }
    assert shown == {
        d1 - timedelta(days=1): [],
        d1: [100.0],
        d2 - timedelta(days=1): [100.0],
        d2: [110.0],
        d3: [120.0],
    }


def test_a_revision_does_not_make_old_data_look_new():
    ref = date(2020, 3, 7)
    first, revised = date(2020, 4, 9), date(2020, 6, 1)
    pit = PointInTime(
        [obs(SeriesCode.US_ICSA, ref, 1.0, first), obs(SeriesCode.US_ICSA, ref, 2.0, revised)]
    )
    assert pit.snapshot(SeriesCode.US_ICSA, FAR).last_available_on == first


def test_snapshot_is_sorted_and_independent_of_input_order():
    items = [
        obs(SeriesCode.US_VIX, date(2020, 1, 1) + timedelta(days=i), float(i)) for i in range(30)
    ]
    shuffled = [items[i] for i in np.random.default_rng(7).permutation(len(items))]
    a = view_of(items, SeriesCode.US_VIX)
    b = view_of(shuffled, SeriesCode.US_VIX)
    assert a.ref_dates == b.ref_dates == tuple(sorted(a.ref_dates))
    assert a.values.tobytes() == b.values.tobytes()


def test_empty_series_gives_an_empty_view():
    view = PointInTime([]).snapshot(SeriesCode.US_VIX, FAR)
    assert len(view) == 0
    assert view.last_available_on is None


def test_views_are_read_only_and_reprs_hide_dates():
    """TA17."""
    day = date(2020, 3, 4)
    item = obs(SeriesCode.US_DGS2, day, 1.25)
    view = view_of([item], SeriesCode.US_DGS2)
    with pytest.raises(ValueError, match="read-only"):
        view.values[0] = 9.0
    with pytest.raises(ValueError, match="read-only"):
        view.ordinals[0] = 1
    for text in (repr(item), repr(view)):
        assert "2020" not in text
        assert "1.25" not in text


def test_date_helpers():
    assert month_last_day(date(2024, 2, 10)) == date(2024, 2, 29)
    assert month_last_day(date(2023, 12, 31)) == date(2023, 12, 31)
    assert add_months(date(2024, 1, 31), 1) == date(2024, 2, 29)
    assert add_months(date(2024, 11, 15), 3) == date(2025, 2, 15)
    assert add_months(date(2024, 3, 1), -4) == date(2023, 11, 1)
    assert years_before(date(2024, 2, 29), 1) == date(2023, 2, 28)
    assert years_before(date(2022, 3, 1), 2) == date(2020, 3, 1)

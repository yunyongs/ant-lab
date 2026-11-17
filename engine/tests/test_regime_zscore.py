import statistics
from datetime import date, timedelta
from decimal import Decimal
from statistics import NormalDist

import numpy as np
import pytest
from regime_synthetic import monthly, panel, weekdays

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import (
    TERMS_BY_ID,
    Observation,
    PointInTime,
    SeriesCode,
    TermStatus,
    Transformed,
    effective_available,
    term_signals,
    z_of_latest,
)
from antlab_engine.regime.pit import add_months
from antlab_engine.regime.zscore import _clip

S = SeriesCode
ASOF = date(2025, 8, 15)


def series(dates, values):
    return Transformed(tuple(dates), np.array(values, dtype=np.float64))


def yearly(values, last=date(2022, 1, 1)):
    return series(
        [add_months(last, -12 * (len(values) - 1 - i)) for i in range(len(values))], values
    )


def test_z_matches_the_hand_value_with_the_sample_deviation():
    """TA10: [1, 2, 3, 4, 10]: mean 4, sum of squares 50, std = sqrt(12.5) (ddof = 1)."""
    found = z_of_latest(yearly([1, 2, 3, 4, 10]), 1)
    assert found.status is TermStatus.OK
    assert found.z == pytest.approx(6 / 12.5**0.5, abs=1e-14)
    assert found.z != pytest.approx(6 / 10**0.5, abs=1e-3)  # not ddof = 0


def test_a_scale_that_overflows_the_statistics_is_degenerate_not_ok():
    huge = z_of_latest(yearly([1e200, -1e200, 1e200, -1e200, 5e200]), 1)
    assert huge.status is TermStatus.DEGENERATE and huge.z is None


def test_transformed_needs_one_value_per_date():
    with pytest.raises(InvalidArgument):
        Transformed((date(2020, 1, 1),), np.array([1.0, 2.0]))
    with pytest.raises(InvalidArgument):
        Transformed((date(2020, 1, 1),), np.array([[1.0]]))


def test_sign_flips_the_z():
    a, b = z_of_latest(yearly([1, 2, 3, 4, 10]), 1), z_of_latest(yearly([1, 2, 3, 4, 10]), -1)
    assert b.z == -a.z


def test_clip_is_exactly_three():
    """TA10."""
    assert _clip(3.0, 1) == 3.0
    assert _clip(3.0001, 1) == 3.0
    assert _clip(-3.5, 1) == -3.0
    assert _clip(2.0, -1) == -2.0
    four_months = [add_months(date(2017, 6, 1), 4 * i) for i in range(11)]
    outlier = z_of_latest(series(four_months, [0.0] * 10 + [1.0]), 1)
    assert outlier.z == 3.0  # unclipped it is (10/11) / sqrt(1/11) = 3.015


def test_warm_up_needs_two_years_of_points():
    """TA11: exactly two years is enough, one day less is not."""
    latest = date(2022, 3, 1)
    ok = series([date(2020, 3, 1), date(2021, 3, 1), latest], [1.0, 2.0, 5.0])
    warming = series([date(2020, 3, 2), date(2021, 3, 1), latest], [1.0, 2.0, 5.0])
    assert z_of_latest(ok, 1).status is TermStatus.OK
    assert z_of_latest(warming, 1).status is TermStatus.WARMING
    assert z_of_latest(warming, 1).z is None


def test_a_constant_window_is_degenerate_even_when_float_noise_leaves_a_tiny_std():
    four_months = [add_months(date(2017, 6, 1), 4 * i) for i in range(11)]
    assert np.std(np.full(11, 0.7), ddof=1) > 0  # the noise the exact check guards against
    assert z_of_latest(series(four_months, [0.7] * 11), 1).status is TermStatus.DEGENERATE


def test_constant_and_missing_series():
    assert z_of_latest(yearly([2.0] * 5), 1).status is TermStatus.DEGENERATE
    assert z_of_latest(yearly([1.0, 2.0, 3.0, 4.0, float("nan")]), 1).status is TermStatus.MISSING
    assert z_of_latest(series([], []), 1).status is TermStatus.MISSING


def test_the_window_is_five_years_and_excludes_its_first_day():
    """The point exactly five years before the latest is outside the window."""
    latest = date(2022, 1, 1)
    dates = [
        date(2017, 1, 1),
        date(2018, 1, 1),
        date(2019, 1, 1),
        date(2020, 1, 1),
        date(2021, 1, 1),
    ]
    base = series([*dates[1:], latest], [1.0, 2.0, 3.0, 4.0, 10.0])
    with_old = series([*dates, latest], [999.0, 1.0, 2.0, 3.0, 4.0, 10.0])
    assert z_of_latest(base, 1).z == z_of_latest(with_old, 1).z


def test_level_shift_gives_the_rolling_z_not_the_full_sample_z():
    """TA13: the shift is six years old; the window sees only the new level."""
    noise = [(-1.0) ** i for i in range(144)]
    values = [n if i < 72 else 10.0 + n for i, n in enumerate(noise)]
    values[-1] = 12.0
    dates = [add_months(date(2008, 1, 1), i) for i in range(144)]
    found = z_of_latest(series(dates, values), 1)
    window = values[-60:]  # (latest - 5 years, latest]: 60 monthly points
    expected = (12.0 - statistics.mean(window)) / statistics.stdev(window)
    assert found.z == pytest.approx(expected, abs=1e-12)
    full = (12.0 - statistics.mean(values)) / statistics.stdev(values)
    assert abs(found.z - full) > 0.5


def test_percentile_term_uses_the_mid_rank_in_the_window():
    """TA9: one unique maximum, ties at mid-rank, and the clip."""
    days = weekdays(date(2020, 1, 1), 540)  # about 2.1 years
    count = len(days)
    top = series(days, list(range(count)))  # the latest is the unique maximum
    clipped = z_of_latest(top, 1, percentile=True)
    assert clipped.z == 3.0
    assert NormalDist().inv_cdf(1 - 0.5 / count) > 3.0  # the clip did the work
    flat = z_of_latest(series(days, [5.0] * count), 1, percentile=True)
    assert flat.z == pytest.approx(0.0, abs=1e-15) and flat.status is TermStatus.OK
    values = [float(i % 7) for i in range(count)]
    values[-1] = 3.0
    mid = z_of_latest(series(days, values), 1, percentile=True)
    below = sum(v < 3.0 for v in values)
    equal = sum(v == 3.0 for v in values)
    assert mid.z == pytest.approx(NormalDist().inv_cdf((below + 0.5 * equal) / count), abs=1e-12)
    assert z_of_latest(series(days, values), -1, percentile=True).z == pytest.approx(-mid.z)


def walk(count, seed):
    return list(100.0 * np.exp(np.cumsum(np.random.default_rng(seed).normal(0, 0.01, count))))


def test_old_raw_points_cannot_change_a_z_and_recent_ones_do():
    """TA12: bit-equal outside lookback + window, different inside."""
    start = date(2016, 1, 4)
    count = 2400
    values = walk(count, 5)
    days = weekdays(start, count)
    cutoff = days[-1] - timedelta(days=5 * 366 + 400)

    def signals(raw):
        items = [
            Observation(S.US_DGS2, d, Decimal(repr(float(v))), d)
            for d, v in zip(days, raw, strict=True)
        ]
        return {s.term_id: s for s in term_signals(PointInTime(items), days[-1])}

    base = signals(values)
    older = [v * 7 if d < cutoff else v for d, v in zip(days, values, strict=True)]
    nearer = list(values)
    nearer[count - 500] *= 1.5
    after_old, after_near = signals(older), signals(nearer)
    for term in ("1a", "1b"):
        assert base[term].status is TermStatus.OK
        assert after_old[term].z.hex() == base[term].z.hex()
        assert after_near[term].z != base[term].z
    assert any(d < cutoff for d in days)


def full_panel_signals(items, as_of=ASOF):
    return term_signals(PointInTime(items), as_of)


def test_a_full_panel_gives_ok_signals_for_all_24_terms():
    signals = full_panel_signals(panel())
    assert [s.term_id for s in signals][:3] == ["1a", "1b", "2"]
    assert len(signals) == 24
    assert {s.status for s in signals} == {TermStatus.OK}
    assert all(s.z is not None and -3.0 <= s.z <= 3.0 for s in signals)


def test_signals_do_not_depend_on_the_order_of_the_observations():
    items = panel()
    shuffled = list(reversed(items))
    assert full_panel_signals(items) == full_panel_signals(shuffled)


def test_nothing_published_after_as_of_changes_the_signals():
    """Look-ahead at term level: rows that become usable after T cannot move any signal."""
    items = panel()
    base = full_panel_signals(items)
    late = ASOF + timedelta(days=40)
    last_claims = max((o for o in items if o.series is S.US_ICSA), key=lambda o: o.ref_date)
    future = [
        Observation(S.US_DGS2, date(2025, 8, 18), Decimal("99"), date(2025, 8, 18)),
        Observation(S.US_ICSA, last_claims.ref_date, Decimal("9999"), late),  # a new vintage
        Observation(S.KR_CPI, date(2025, 7, 1), Decimal("1"), date(2025, 8, 20)),
        Observation(S.US_VIX, date(2025, 7, 5), Decimal("0.5"), late),  # a late first release
    ]
    assert all(effective_available(o) > ASOF for o in future)
    assert full_panel_signals([*items, *future]) == base
    assert full_panel_signals([*items, *future][::-1]) == base
    usable = Observation(S.US_VIX, date(2025, 8, 15), Decimal("80"), ASOF)  # positive control
    assert full_panel_signals([*items, usable]) != base


def test_every_signal_is_finite_or_none():
    for found in full_panel_signals(panel(seed=9)):
        assert found.z is None or np.isfinite(found.z)


def test_a_missing_point_inside_the_window_is_skipped_not_poisoning_the_z():
    dates = [add_months(date(2017, 1, 1), i) for i in range(60)]
    values = [float((i * 7) % 11) for i in range(60)]
    holed = list(values)
    holed[30] = float("nan")
    found = z_of_latest(series(dates, holed), 1)
    finite = [v for i, v in enumerate(values) if i != 30]
    assert found.status is TermStatus.OK
    assert found.z == pytest.approx(
        (finite[-1] - statistics.mean(finite)) / statistics.stdev(finite), abs=1e-12
    )


def test_two_points_two_years_apart_are_still_warming():
    sparse = series([date(2020, 3, 1), date(2022, 3, 1)], [1.0, 5.0])
    assert z_of_latest(sparse, 1).status is TermStatus.WARMING


def test_constant_growth_core_cpi_is_degenerate_not_float_noise():
    items = monthly(S.US_CPILFESL, date(2017, 1, 1), 80, lambda i: 100 * 1.002**i)
    views = {c: PointInTime(items).snapshot(c, date(2100, 1, 1)) for c in S}
    accel = TERMS_BY_ID["2"].transform(views)
    assert abs(accel.values[-1]) < 1e-12
    assert z_of_latest(accel, 1).status is TermStatus.DEGENERATE

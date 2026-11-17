"""Transforms of MACRO_ETF §2.3. Pure numpy; a point that cannot be computed is NaN.

Daily and weekly lookbacks count observations, monthly ones count calendar months and need every
month in between (a gap gives NaN, never an interpolation). Every rolling mean uses a sliding
window, so a point depends only on its own window and not on older data.
"""

from datetime import date

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

from antlab_engine.regime.model import SeriesView, Transformed, take_dates


def _clean(values: np.ndarray) -> np.ndarray:
    values[~np.isfinite(values)] = np.nan
    return values


def _ratio(numerator: np.ndarray, denominator: np.ndarray) -> np.ndarray:
    with np.errstate(divide="ignore", invalid="ignore", over="ignore"):
        return _clean(numerator / denominator)


def shift(values: np.ndarray, n: int) -> np.ndarray:
    """x[i - n] (NaN before the start)."""
    out = np.full(values.shape, np.nan)
    if 0 < n < len(values):
        out[n:] = values[:-n]
    return out


def rolling_mean(values: np.ndarray, n: int) -> np.ndarray:
    out = np.full(values.shape, np.nan)
    if len(values) >= n:
        out[n - 1 :] = sliding_window_view(values, n).mean(axis=1)
    return out


def delta(values: np.ndarray, n: int) -> np.ndarray:
    return values - shift(values, n)


def pct_change(values: np.ndarray, n: int) -> np.ndarray:
    return _ratio(values, shift(values, n)) - 1.0


def minus_mean(values: np.ndarray, n: int) -> np.ndarray:
    return values - rolling_mean(values, n)


def over_mean_minus_one(values: np.ndarray, n: int) -> np.ndarray:
    return _ratio(values, rolling_mean(values, n)) - 1.0


def mean_ratio_minus_one(values: np.ndarray, short: int, long: int) -> np.ndarray:
    return _ratio(rolling_mean(values, short), rolling_mean(values, long)) - 1.0


def month_index(ref_dates: tuple[date, ...]) -> np.ndarray:
    return np.array([d.year * 12 + d.month - 1 for d in ref_dates], dtype=np.int64)


def month_shift(months: np.ndarray, values: np.ndarray, n: int) -> np.ndarray:
    """The value `n` calendar months earlier; NaN when that month is missing."""
    out = np.full(values.shape, np.nan)
    if len(values) == 0:
        return out
    target = months - n
    pos = np.searchsorted(months, target)
    at = np.minimum(pos, len(months) - 1)
    found = (pos < len(months)) & (months[at] == target)
    out[found] = values[at][found]
    return out


def core_cpi_acceleration(months: np.ndarray, values: np.ndarray) -> np.ndarray:
    """3-month annualised growth minus 12-month growth of a monthly level."""
    annualised = np.power(_ratio(values, month_shift(months, values, 3)), 4) - 1.0
    return _clean(annualised - (_ratio(values, month_shift(months, values, 12)) - 1.0))


def year_over_year(months: np.ndarray, values: np.ndarray) -> np.ndarray:
    return _ratio(values, month_shift(months, values, 12)) - 1.0


def year_over_year_change(months: np.ndarray, values: np.ndarray, n: int) -> np.ndarray:
    yoy = year_over_year(months, values)
    return yoy - month_shift(months, yoy, n)


def month_delta(months: np.ndarray, values: np.ndarray, n: int) -> np.ndarray:
    return values - month_shift(months, values, n)


def month_mean3(months: np.ndarray, values: np.ndarray) -> np.ndarray:
    return (values + month_shift(months, values, 1) + month_shift(months, values, 2)) / 3.0


def exports_year_over_year(months: np.ndarray, values: np.ndarray) -> np.ndarray:
    """3-month moving average over the same average a year earlier, minus one."""
    average = month_mean3(months, values)
    return _ratio(average, month_shift(months, average, 12)) - 1.0


def as_of_join(left: SeriesView, right: SeriesView) -> np.ndarray:
    """For each left date, the latest right value with `ref_date` on or before it (else NaN)."""
    out = np.full(len(left), np.nan)
    if len(right) == 0:
        return out
    pos = np.searchsorted(right.ordinals, left.ordinals, side="right") - 1
    found = pos >= 0
    out[found] = right.values[pos[found]]
    return out


def spread_on_left_dates(left: SeriesView, right: SeriesView) -> Transformed:
    """left - right, with `right` joined as of each left date."""
    return Transformed(left.ref_dates, left.values - as_of_join(left, right), left.ordinals)


def same_date_spread(left: SeriesView, right: SeriesView) -> Transformed:
    """left - right on the dates both series have."""
    common = np.intersect1d(left.ordinals, right.ordinals)
    at = np.searchsorted(left.ordinals, common)
    first = left.values[at]
    second = right.values[np.searchsorted(right.ordinals, common)]
    return Transformed(take_dates(left.ref_dates, at), first - second, common)

"""Rolling z-score of the latest transformed point (MACRO_ETF §2.4 step 2) and term signals.

The window is the 5 calendar years ending at the latest point's `ref_date` (expanding until 5
years exist); fewer than 2 years of points is WARMING. Statistics use the sample standard
deviation (ddof = 1). Nothing outside the window, and nothing after `as_of`, is used.
"""

import math
from collections.abc import Mapping
from datetime import date
from statistics import NormalDist

import numpy as np

from antlab_engine.regime.catalog import TERMS, Term
from antlab_engine.regime.model import (
    SeriesCode,
    SeriesView,
    TermSignal,
    TermStatus,
    Transformed,
)
from antlab_engine.regime.pit import PointInTime, years_before
from antlab_engine.regime.stale import Calendars, SeriesState, series_states

Z_WINDOW_YEARS = 5
Z_MIN_YEARS = 2
Z_CLIP = 3.0
Z_MIN_POINTS = 3
Z_TINY_STD = 1e-9  # relative: a window flat up to float noise has no signal


def _clip(z: float, sign: int) -> float:
    return max(-Z_CLIP, min(Z_CLIP, sign * z))


def _window(series: Transformed) -> tuple[np.ndarray, date, date] | None:
    """Finite points in the window (latest last), the earliest point's date and the latest date."""
    if len(series.values) == 0:
        return None
    ordinals = series.ordinals
    latest_date = date.fromordinal(int(ordinals[-1]))
    start = years_before(latest_date, Z_WINDOW_YEARS).toordinal()
    keep = (ordinals > start) & np.isfinite(series.values)
    if not keep.any() or not math.isfinite(series.values[-1]):
        return None
    first = date.fromordinal(int(ordinals[keep][0]))
    return series.values[keep], first, latest_date


def z_of_latest(series: Transformed, sign: int, percentile: bool = False) -> TermSignal:
    """Signed, clipped z of the latest point; `term_id` is filled in by the caller."""
    found = _window(series)
    if found is None:
        return TermSignal("", None, TermStatus.MISSING)
    window, first, latest_date = found
    if len(window) < Z_MIN_POINTS or first > years_before(latest_date, Z_MIN_YEARS):
        return TermSignal("", None, TermStatus.WARMING)
    latest = float(window[-1])
    if percentile:
        below = float(np.count_nonzero(window < latest))
        equal = float(np.count_nonzero(window == latest))
        z = NormalDist().inv_cdf((below + 0.5 * equal) / len(window))
        return TermSignal("", _clip(z, sign), TermStatus.OK)
    if window.max() == window.min():
        return TermSignal("", None, TermStatus.DEGENERATE)
    with np.errstate(over="ignore", invalid="ignore"):
        mean, std = float(window.mean()), float(window.std(ddof=1))
    z = (
        (latest - mean) / std
        if math.isfinite(mean) and math.isfinite(std) and std > Z_TINY_STD * max(1.0, abs(mean))
        else None
    )
    if z is None or not math.isfinite(z):
        return TermSignal("", None, TermStatus.DEGENERATE)
    return TermSignal("", _clip(z, sign), TermStatus.OK)


def _signal(
    term: Term, views: Mapping[SeriesCode, SeriesView], states: Mapping[SeriesCode, SeriesState]
) -> TermSignal:
    if any(states[code] is SeriesState.STALE for code in term.inputs):
        return TermSignal(term.term_id, None, TermStatus.STALE)
    if any(len(views[code]) == 0 for code in term.inputs):
        return TermSignal(term.term_id, None, TermStatus.MISSING)
    found = z_of_latest(term.transform(views), term.sign, term.percentile)
    return TermSignal(term.term_id, found.z, found.status)


def term_signals(
    pit: PointInTime, as_of: date, calendars: Calendars | None = None
) -> tuple[TermSignal, ...]:
    """One signal per catalog term, using only what was known on `as_of`."""
    views = {code: pit.snapshot(code, as_of) for code in SeriesCode}
    states = series_states(views, as_of, calendars)
    return signals_from_views(views, states)


def signals_from_views(
    views: Mapping[SeriesCode, SeriesView], states: Mapping[SeriesCode, SeriesState]
) -> tuple[TermSignal, ...]:
    """The same signals from snapshots and series states that the caller already holds."""
    return tuple(_signal(term, views, states) for term in TERMS)

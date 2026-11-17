"""Quantiles (type 7), maximum drawdown and breach counts over value-multiple paths."""

from collections.abc import Sequence
from fractions import Fraction

import numpy as np

from antlab_engine.projection.model import PortfolioProjection, Summary, readonly

BAND_POINTS = (5, 25, 50, 75, 95)
MDD_POINTS = (5, 10, 25, 50, 75, 95)


def quantiles(values: np.ndarray, points: Sequence[int]) -> np.ndarray:
    """Hyndman-Fan type 7 along axis 0, shape `(len(points), ...)`.

    The index `(n - 1) * k / 100` is an exact Fraction; only the fractional part becomes a float.
    """
    ordered = np.sort(values, axis=0)
    n = ordered.shape[0]
    rows = []
    for k in points:
        h = Fraction((n - 1) * k, 100)
        low = h.numerator // h.denominator
        fraction = h - low
        if fraction == 0:
            rows.append(ordered[low])
        else:
            rows.append(ordered[low] + float(fraction) * (ordered[low + 1] - ordered[low]))
    return np.stack(rows)


def running_mdd(values: np.ndarray) -> np.ndarray:
    """Maximum drawdown up to each turn, `(n, horizon + 1)`; the peak includes turn 0."""
    peak = np.maximum.accumulate(values, axis=1)
    return np.minimum.accumulate(values / peak - 1.0, axis=1)


def summarize(
    key: str, values: np.ndarray, summary_turns: Sequence[int], risk_limit: float
) -> PortfolioProjection:
    bands = quantiles(values[:, 1:], BAND_POINTS).T
    mdd = running_mdd(values)
    summaries = []
    for turn in summary_turns:
        at_turn = mdd[:, turn]
        summaries.append(
            Summary(
                turn=turn,
                end_quantiles=readonly(bands[turn - 1].copy()),
                mdd_quantiles=readonly(quantiles(at_turn, MDD_POINTS)),
                breach_count=int(np.count_nonzero(at_turn < risk_limit)),
            )
        )
    return PortfolioProjection(
        key=key,
        bands=readonly(np.ascontiguousarray(bands)),
        summaries=tuple(summaries),
        end_values=values,
    )

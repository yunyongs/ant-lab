"""Fixed-quantity (buy and hold) evaluation with element-wise operations only.

No matrix products or other BLAS-backed routines: their results can differ by machine and thread
count, while IEEE element-wise operations in a fixed order are reproducible.
"""

from collections.abc import Sequence

import numpy as np

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection.model import readonly

Book = tuple[tuple[tuple[int, float], ...], float]


def evaluate(factors: np.ndarray, paths: np.ndarray, books: Sequence[Book]) -> list[np.ndarray]:
    """Value multiples `(n, horizon + 1)` per book, turn 0 equal to 1.

    A book is `(((asset column, weight), ...) in ascending column order, cash)`. Each asset is
    compounded once along every path and added to each book that holds it, in column order.
    """
    n, horizon = paths.shape
    with np.errstate(over="ignore", invalid="ignore"):
        return _evaluate(factors, paths, books, n, horizon)


def _evaluate(
    factors: np.ndarray, paths: np.ndarray, books: Sequence[Book], n: int, horizon: int
) -> list[np.ndarray]:
    totals = [np.full((n, horizon), cash) for _, cash in books]
    weights = [dict(held) for held, _ in books]
    scratch = np.empty((n, horizon))
    for column in sorted({a for held, _ in books for a, _ in held}):
        grown = np.cumprod(np.ascontiguousarray(factors[:, column]).take(paths), axis=1)
        for total, weight in zip(totals, weights, strict=True):
            if column in weight:
                np.multiply(grown, weight[column], out=scratch)
                np.add(total, scratch, out=total)
    result = []
    for total in totals:
        if not np.isfinite(total).all():
            raise InvalidArgument("the projected values overflow a float64")
        values = np.empty((n, horizon + 1))
        values[:, 0] = 1.0
        values[:, 1:] = total
        result.append(readonly(values))
    return result

"""`rank`: integer rank counts of an observed season among N held-still paths (version m2-pj-v1).

The team and the benchmark are held as fixed quantities over the same N paths that `project` would
use, so the season's own path is never among them. Per path the library takes the end multiple of
the team and the Sharpe difference `sharpe(team) - sharpe(benchmark)`; it returns how many paths
fall strictly below and exactly on the observed values. Counts are integers: no rounding happens
here, and the caller derives the percentile.
"""

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

import numpy as np

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.simple import REPLAY_START
from antlab_engine.engine_errors import InvalidArgument, PathTooLong
from antlab_engine.projection.model import (
    MAX_PATHS,
    PROJECTION_VERSION,
    Portfolio,
    require_decimal,
)
from antlab_engine.projection.panel import ReturnPanel
from antlab_engine.projection.paths import draw_paths
from antlab_engine.projection.seeds import check_seed, derive_path_seeds

MIN_PATHS = 100


@dataclass(frozen=True)
class Counts:
    """Paths strictly below the observed value and paths exactly equal to it (float64)."""

    below: int
    equal: int


@dataclass(frozen=True)
class Rank:
    projection_version: str
    engine_version: str
    n: int
    horizon: int
    end_multiple: Counts
    sharpe_diff: Counts


def _int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise InvalidArgument(f"{name} must be an integer")
    return value


def _held(panel: ReturnPanel, book: Portfolio) -> tuple[tuple[tuple[int, float], ...], float]:
    known = set(panel.assets)
    if any(asset not in known for asset in book.weights):
        raise InvalidArgument("a portfolio holds an asset that the panel does not have")
    column = {asset: i for i, asset in enumerate(panel.assets)}
    return tuple((column[asset], float(w)) for asset, w in book.active), float(book.cash)


Book = tuple[tuple[tuple[int, float], ...], float]


def stable_values(factors: np.ndarray, paths: np.ndarray, books: list[Book]) -> list[np.ndarray]:
    """Value multiples `(n, horizon + 1)` per book as `1 + sum(weight * (growth - 1))`.

    Weights and cash add up to exactly 1, so this equals `cash + sum(weight * growth)` but an
    asset that does not move contributes exactly 0: a book whose holdings never move keeps the
    value 1.0 instead of drifting by rounding (0.1 added ten times is not 1.0), which would show up
    as a false first-turn return and a false Sharpe. `evaluate` (used by `project`) is unchanged,
    so the projection results do not move; values here may differ from it in the last bits.
    Element-wise operations in sorted column order only, as in `evaluate`.
    """
    n, horizon = paths.shape
    totals = [np.ones((n, horizon)) for _ in books]
    weights = [dict(held) for held, _ in books]
    scratch = np.empty((n, horizon))
    with np.errstate(over="ignore", invalid="ignore"):
        for column in sorted({a for held, _ in books for a, _ in held}):
            excess = np.cumprod(np.ascontiguousarray(factors[:, column]).take(paths), axis=1) - 1.0
            for total, weight in zip(totals, weights, strict=True):
                if column in weight:
                    np.multiply(excess, weight[column], out=scratch)
                    np.add(total, scratch, out=total)
    result = []
    for total in totals:
        if not np.isfinite(total).all():
            raise InvalidArgument("the projected values overflow a float64")
        values = np.empty((n, horizon + 1))
        values[:, 0] = 1.0
        values[:, 1:] = np.maximum(total, 0.0)  # rounding must not turn a total loss negative
        result.append(values)
    return result


def sharpe(values: np.ndarray) -> np.ndarray:
    """Per-path `mean(r) / std(r, ddof=1)` of `r_t = v_t / v_{t-1} - 1`; 0 where std is 0.

    A value that is already 0 stays 0 (nothing left to lose), so its step return is defined as 0.

    Sums run column by column in a fixed order (no pairwise or BLAS reductions), so the result is
    the same on every machine. `values` is `(n, horizon + 1)` with turn 0 first.
    """
    steps = values.shape[1] - 1
    if steps < 2:
        raise InvalidArgument("at least two return steps are needed")
    with np.errstate(over="ignore", invalid="ignore", divide="ignore"):
        previous = values[:, :-1]
        returns = np.where(
            previous > 0.0, values[:, 1:] / np.where(previous > 0.0, previous, 1.0) - 1.0, 0.0
        )
        if not np.isfinite(returns).all():
            raise InvalidArgument("the projected values overflow a float64")
        total = np.zeros(values.shape[0])
        for t in range(steps):
            total = total + returns[:, t]
        mean = total / steps
        squares = np.zeros(values.shape[0])
        for t in range(steps):
            deviation = returns[:, t] - mean
            squares = squares + deviation * deviation
        deviation = np.sqrt(squares / (steps - 1))
        ratio = np.where(deviation > 0.0, mean / np.where(deviation > 0.0, deviation, 1.0), 0.0)
    if not np.isfinite(ratio).all():
        raise InvalidArgument("the projected values overflow a float64")
    return ratio


def _counts(values: np.ndarray, observed: float) -> Counts:
    return Counts(
        below=int(np.count_nonzero(values < observed)),
        equal=int(np.count_nonzero(values == observed)),
    )


def _observed(value: object, name: str) -> float:
    number = float(require_decimal(value, name))
    if not np.isfinite(number):
        raise InvalidArgument(f"{name} is outside the float64 range")
    return number


def rank(
    panel: ReturnPanel,
    base_date: date,
    *,
    season_seed: int,
    horizon: int,
    team: Portfolio,
    benchmark: Portfolio,
    observed_end_multiple: Decimal,
    observed_sharpe_diff: Decimal,
    n: int = 2000,
    replay_start: date = REPLAY_START,
    params: stationary.BootstrapParams = stationary.DEFAULT_PARAMS,
) -> Rank:
    check_seed(season_seed)
    if _int(horizon, "horizon") not in stationary.ALLOWED_LENGTHS:
        raise InvalidArgument("horizon must be one of 12, 24, 60, 120")
    if not MIN_PATHS <= _int(n, "n") <= MAX_PATHS:
        raise InvalidArgument(f"n must be between {MIN_PATHS} and {MAX_PATHS}")
    if not isinstance(team, Portfolio) or not isinstance(benchmark, Portfolio):
        raise InvalidArgument("team and benchmark must be Portfolio values")
    end_at = _observed(observed_end_multiple, "observed end multiple")
    diff_at = _observed(observed_sharpe_diff, "observed Sharpe difference")
    books = [_held(panel, team), _held(panel, benchmark)]
    used = sorted({asset for p in (team, benchmark) for asset, _ in p.active})
    paths = draw_paths(
        panel, base_date, derive_path_seeds(season_seed, n), horizon, used, replay_start, params
    )
    if len(np.unique(paths, axis=0)) < 2:
        raise PathTooLong("the history allows only one projection path")
    team_values, benchmark_values = stable_values(panel.factors, paths, books)
    difference = sharpe(team_values) - sharpe(benchmark_values)
    return Rank(
        projection_version=PROJECTION_VERSION,
        engine_version=stationary.ENGINE_VERSION,
        n=n,
        horizon=horizon,
        end_multiple=_counts(team_values[:, horizon], end_at),
        sharpe_diff=_counts(difference, diff_at),
    )

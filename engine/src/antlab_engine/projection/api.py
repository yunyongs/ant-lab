"""`project`: the one entry point of the projection library (version m2-pj-v1).

The current team and baselines are held as fixed quantities over N paths of `m2-sb6-v1`. Seeds
derive from the season seed, so the season's own path is never among them and every portfolio
sees the same paths. Results are ratios; Java converts to won.
"""

from collections.abc import Sequence
from datetime import date
from decimal import Decimal

import numpy as np

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.simple import REPLAY_START, replay_upper_bound
from antlab_engine.engine_errors import InvalidArgument, PathTooLong
from antlab_engine.projection.evaluate import evaluate
from antlab_engine.projection.model import (
    MAX_PATHS,
    PROJECTION_VERSION,
    Portfolio,
    Projection,
    require_decimal,
)
from antlab_engine.projection.panel import ReturnPanel
from antlab_engine.projection.paths import draw_paths
from antlab_engine.projection.seeds import check_seed, derive_path_seeds
from antlab_engine.projection.stats import summarize

MIN_PATHS = 100
MAX_PORTFOLIOS = 6


def _int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise InvalidArgument(f"{name} must be an integer")
    return value


def _validate(
    portfolios: Sequence[Portfolio], panel: ReturnPanel, horizon: int, n: int
) -> list[Portfolio]:
    if _int(horizon, "horizon") not in stationary.ALLOWED_LENGTHS:
        raise InvalidArgument("horizon must be one of 12, 24, 60, 120")
    if not MIN_PATHS <= _int(n, "n") <= MAX_PATHS:
        raise InvalidArgument(f"n must be between {MIN_PATHS} and {MAX_PATHS}")
    books = list(portfolios)
    if not 1 <= len(books) <= MAX_PORTFOLIOS:
        raise InvalidArgument(f"between 1 and {MAX_PORTFOLIOS} portfolios are needed")
    if not all(isinstance(p, Portfolio) for p in books):
        raise InvalidArgument("portfolios must be Portfolio values")
    if len({p.key for p in books}) != len(books):
        raise InvalidArgument("portfolio keys must be unique")
    known = set(panel.assets)
    if any(asset not in known for p in books for asset in p.weights):
        raise InvalidArgument("a portfolio holds an asset that the panel does not have")
    return books


def _turns(summary_turns: Sequence[int], horizon: int) -> list[int]:
    wanted = {_int(t, "summary turn") for t in summary_turns}
    return sorted({t for t in wanted if 1 <= t <= horizon} | {horizon})


def project(
    panel: ReturnPanel,
    base_date: date,
    *,
    season_seed: int,
    horizon: int,
    portfolios: Sequence[Portfolio],
    risk_limit: Decimal,
    n: int = 2000,
    summary_turns: Sequence[int] = (60, 120),
    replay_start: date = REPLAY_START,
    params: stationary.BootstrapParams = stationary.DEFAULT_PARAMS,
) -> Projection:
    check_seed(season_seed)
    books = _validate(portfolios, panel, horizon, n)
    if not -1 < require_decimal(risk_limit, "risk_limit") < 0:
        raise InvalidArgument("risk_limit must be between -1 and 0")
    turns = _turns(summary_turns, horizon)
    used = sorted({asset for p in books for asset, _ in p.active})
    paths = draw_paths(
        panel, base_date, derive_path_seeds(season_seed, n), horizon, used, replay_start, params
    )
    if len(np.unique(paths, axis=0)) < 2:
        raise PathTooLong("the history allows only one projection path")
    column = {asset: i for i, asset in enumerate(panel.assets)}
    held = [
        (tuple((column[asset], float(w)) for asset, w in p.active), float(p.cash)) for p in books
    ]
    values = evaluate(panel.factors, paths, held)
    limit = float(risk_limit)
    upper = replay_upper_bound(base_date)
    rows = panel.rows_with(used)
    return Projection(
        projection_version=PROJECTION_VERSION,
        engine_version=stationary.ENGINE_VERSION,
        n=n,
        horizon=horizon,
        eligible_months=sum(1 for i in rows if replay_start <= panel.months[i] < upper),
        portfolios=tuple(
            summarize(p.key, v, turns, limit) for p, v in zip(books, values, strict=True)
        ),
    )

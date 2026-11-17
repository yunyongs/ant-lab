"""Paths as row indexes into the panel, drawn by the m2-sb6-v1 generator."""

from collections.abc import Sequence
from datetime import date

import numpy as np

from antlab_engine.bootstrap import stationary
from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection.model import MAX_PATHS
from antlab_engine.projection.panel import ReturnPanel


def draw_paths(
    panel: ReturnPanel,
    base_date: date,
    seeds: Sequence[int],
    horizon: int,
    used_assets: Sequence[str],
    replay_start: date,
    params: stationary.BootstrapParams,
) -> np.ndarray:
    """`(len(seeds), horizon)` int32 row indexes.

    Only months where every used asset has a return are eligible, one set for all portfolios.
    No recent months and no overlap limit: overlap avoidance is for seasons, not projections.
    """
    if len(seeds) > MAX_PATHS:
        raise InvalidArgument(f"at most {MAX_PATHS} paths can be drawn")
    rows = panel.rows_with(used_assets)
    eligible = [panel.months[i] for i in rows]
    row_of = {panel.months[i]: int(i) for i in rows}
    paths = np.empty((len(seeds), horizon), dtype=np.int32)
    for k, seed in enumerate(seeds):
        path = stationary.generate(
            eligible, base_date, horizon, seed, replay_start=replay_start, params=params
        )
        paths[k] = [row_of[m] for m in path.months]
    return paths

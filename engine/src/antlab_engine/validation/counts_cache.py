"""`CachedEngineCounts`: the same integer counts as `projection.rank`, with the path draw shared.

`rank` draws `n` paths per call, and the draw depends on the team only through the months where
every used asset has a return. Teams of one world that share that set of months share one draw, so
400 teams cost one draw plus one numpy evaluation each. The cache key therefore holds the eligible
rows: two teams with different gaps must never share paths.
"""

from datetime import date
from decimal import Decimal

import numpy as np

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.simple import REPLAY_START
from antlab_engine.engine_errors import InvalidArgument, PathTooLong
from antlab_engine.projection import ReturnPanel
from antlab_engine.projection.model import MAX_PATHS
from antlab_engine.projection.paths import draw_paths
from antlab_engine.projection.rank import MIN_PATHS, sharpe, stable_values
from antlab_engine.projection.seeds import check_seed, derive_path_seeds
from antlab_engine.sim.counts import (
    PercentileCounts,
    PercentileRequest,
    RankCounts,
    WirePortfolio,
)

_KEEP = 8
_Key = tuple[int, date, int, int, bytes]


def _book(
    column: dict[str, int], book: WirePortfolio
) -> tuple[tuple[tuple[int, float], ...], float]:
    if any(asset not in column for asset, _ in book.weights):
        raise InvalidArgument("a portfolio holds an asset that the panel does not have")
    held = sorted(
        (column[asset], float(Decimal(w))) for asset, w in book.weights if Decimal(w) != 0
    )
    return tuple(held), float(Decimal(book.cash))


class CachedEngineCounts:
    """`CountsSource` over a return panel; equal to `EngineCounts` for every request."""

    def __init__(self, panel: ReturnPanel, replay_start: date | None = None) -> None:
        self._panel = panel
        self._replay_start = REPLAY_START if replay_start is None else replay_start
        self._column = {asset: i for i, asset in enumerate(panel.assets)}
        self._paths: dict[_Key, np.ndarray | None] = {}
        self.draws = 0

    def _drawn(
        self, request: PercentileRequest, used: list[str], rows: np.ndarray
    ) -> np.ndarray | None:
        key = (
            request.season_seed,
            request.base_date,
            request.horizon_turns,
            request.n,
            rows.tobytes(),
        )
        if key in self._paths:
            return self._paths[key]
        self.draws += 1
        try:
            paths: np.ndarray | None = draw_paths(
                self._panel,
                request.base_date,
                derive_path_seeds(request.season_seed, request.n),
                request.horizon_turns,
                used,
                self._replay_start,
                stationary.DEFAULT_PARAMS,
            )
        except PathTooLong:
            paths = None
        if paths is not None and len(np.unique(paths, axis=0)) < 2:
            paths = None
        if len(self._paths) >= _KEEP:
            del self._paths[next(iter(self._paths))]
        self._paths[key] = paths
        return paths

    def counts(self, request: PercentileRequest) -> PercentileCounts | None:
        check_seed(request.season_seed)
        if not MIN_PATHS <= request.n <= MAX_PATHS:
            raise InvalidArgument(f"n must be between {MIN_PATHS} and {MAX_PATHS}")
        books = [_book(self._column, request.team), _book(self._column, request.benchmark)]
        used = sorted(
            {
                asset
                for book in (request.team, request.benchmark)
                for asset, weight in book.weights
                if Decimal(weight) != 0
            }
        )
        rows = self._panel.rows_with(used)
        paths = self._drawn(request, used, rows)
        if paths is None:
            return None
        team_values, benchmark_values = stable_values(self._panel.factors, paths, books)
        diff_at = float(Decimal(request.sharpe_diff))
        end_at = float(Decimal(request.end_multiple))
        difference = sharpe(team_values) - sharpe(benchmark_values)
        ends = team_values[:, request.horizon_turns]
        return PercentileCounts(
            RankCounts(
                int(np.count_nonzero(difference < diff_at)),
                int(np.count_nonzero(difference == diff_at)),
                request.n,
            ),
            RankCounts(
                int(np.count_nonzero(ends < end_at)),
                int(np.count_nonzero(ends == end_at)),
                request.n,
            ),
        )

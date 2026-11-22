"""Rank counts and the percentile request: the two sides of Java's `POST /v1/percentiles` call.

`PercentileRequest` is the exact request body Java sends. A `CountsSource` answers it with
integer counts: `FixedCounts` replays a recorded answer (parity), `EngineCounts` calls the engine
library in-process (no HTTP, no database). The only import from outside the simulator package is
the projection library behind `EngineCounts`.
"""

import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Protocol

from antlab_engine.engine_errors import PathTooLong
from antlab_engine.projection import Portfolio, ReturnPanel, rank

from .model import SimReportError, SimShapeError

HORIZONS = (12, 24, 60, 120)
MIN_PATHS = 100
MAX_PATHS = 5000
MAX_HOLDINGS = 200
_SHARE = re.compile(r"(0|1|0\.[0-9]{1,12}|1\.0{1,12})\Z")
_END_MULTIPLE = re.compile(r"[0-9]{1,12}(\.[0-9]{1,12})?\Z")
_SHARPE_DIFF = re.compile(r"-?[0-9]{1,6}(\.[0-9]{1,12})?\Z")


@dataclass(frozen=True)
class RankCounts:
    """Paths strictly below and exactly equal to an observed value, out of `n`."""

    below: int
    equal: int
    n: int

    def __post_init__(self) -> None:
        if self.n < 1 or self.below < 0 or self.equal < 0 or self.below + self.equal > self.n:
            raise SimShapeError("rank counts need n >= 1, below, equal >= 0, below + equal <= n")


@dataclass(frozen=True)
class PercentileCounts:
    sharpe_diff: RankCounts
    end_multiple: RankCounts

    def __post_init__(self) -> None:
        if self.sharpe_diff.n != self.end_multiple.n:
            raise SimShapeError("both rank counts must come from the same number of paths")


@dataclass(frozen=True)
class WirePortfolio:
    """Cash plus weights as plain decimal strings; together they add up to exactly 1."""

    cash: str
    weights: tuple[tuple[str, str], ...]

    def __post_init__(self) -> None:
        if not _SHARE.match(self.cash):
            raise SimShapeError("portfolio cash is not a share string")
        if len(self.weights) > MAX_HOLDINGS:
            raise SimShapeError(f"a portfolio holds at most {MAX_HOLDINGS} assets")
        total = Decimal(self.cash)
        seen: set[str] = set()
        for asset, weight in self.weights:
            if asset in seen or not _SHARE.match(weight):
                raise SimShapeError("a portfolio asset repeats or a weight is not a share string")
            seen.add(asset)
            total += Decimal(weight)
        if total != 1:
            raise SimShapeError("cash plus weights must add up to exactly 1")

    def to_wire(self) -> dict[str, object]:
        return {
            "cash": self.cash,
            "weights": [{"asset": a, "weight": w} for a, w in self.weights],
        }


@dataclass(frozen=True)
class PercentileRequest:
    """Java `EnginePercentileRequest`: where the observed season sits among `n` held-still paths."""

    season_seed: int
    base_date: date
    horizon_turns: int
    n: int
    team: WirePortfolio
    benchmark: WirePortfolio
    end_multiple: str
    sharpe_diff: str

    def __post_init__(self) -> None:
        if not -(2**63) <= self.season_seed < 2**63:
            raise SimShapeError("the season seed must fit a signed 64-bit integer")
        if self.horizon_turns not in HORIZONS:
            raise SimShapeError("horizon must be one of 12, 24, 60, 120")
        if not MIN_PATHS <= self.n <= MAX_PATHS:
            raise SimShapeError("n must be 100..5000")
        if not _END_MULTIPLE.match(self.end_multiple) or not _SHARPE_DIFF.match(self.sharpe_diff):
            raise SimShapeError("an observed value is not in wire form")

    def to_wire(self) -> dict[str, object]:
        return {
            "seasonSeed": str(self.season_seed),
            "baseDate": self.base_date.isoformat(),
            "horizonTurns": self.horizon_turns,
            "n": self.n,
            "team": self.team.to_wire(),
            "benchmark": self.benchmark.to_wire(),
            "observed": {"endMultiple": self.end_multiple, "sharpeDiff": self.sharpe_diff},
        }


class CountsSource(Protocol):
    def counts(self, request: PercentileRequest) -> PercentileCounts | None:
        """Counts, or None when the history cannot carry the horizon (Java `PATH_TOO_LONG`)."""
        ...


@dataclass(frozen=True)
class FixedCounts:
    """A recorded engine reply; None replays a `PATH_TOO_LONG` refusal. Counts must match `n`."""

    answer: PercentileCounts | None

    def counts(self, request: PercentileRequest) -> PercentileCounts | None:
        if self.answer is None:
            return None
        if self.answer.sharpe_diff.n != request.n:
            raise SimReportError("the recorded counts come from another number of paths")
        return self.answer


@dataclass(frozen=True)
class EngineCounts:
    """The engine's `rank` library over a return panel, called in-process."""

    panel: ReturnPanel
    replay_start: date | None = None

    def counts(self, request: PercentileRequest) -> PercentileCounts | None:
        """None only for `PathTooLong`; every other refusal propagates, as Java rethrows it."""
        extra = {} if self.replay_start is None else {"replay_start": self.replay_start}
        try:
            result = rank(
                self.panel,
                request.base_date,
                season_seed=request.season_seed,
                horizon=request.horizon_turns,
                team=_portfolio("TEAM", request.team),
                benchmark=_portfolio("BENCHMARK", request.benchmark),
                observed_end_multiple=Decimal(request.end_multiple),
                observed_sharpe_diff=Decimal(request.sharpe_diff),
                n=request.n,
                **extra,
            )
        except PathTooLong:
            return None
        return PercentileCounts(
            RankCounts(result.sharpe_diff.below, result.sharpe_diff.equal, result.n),
            RankCounts(result.end_multiple.below, result.end_multiple.equal, result.n),
        )


def _portfolio(key: str, book: WirePortfolio) -> Portfolio:
    return Portfolio(key, {a: Decimal(w) for a, w in book.weights}, Decimal(book.cash))

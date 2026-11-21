"""POST /v1/percentiles: where an observed season ranks among N held-still paths.

The route returns integer counts only (strictly below and exactly equal to each observed value),
never per-path values, months or won. The caller turns the counts into a percentile.
"""

from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends, Request

from antlab_engine.bootstrap import stationary
from antlab_engine.engine_errors import EngineBusy, InvalidArgument
from antlab_engine.errors import Problem
from antlab_engine.metrics import SlotPool
from antlab_engine.projection import Portfolio, rank

from . import PROBLEM_RESPONSES, RETRY_AFTER_HEADER
from .deps import history_reader, projection_slots, replay_start
from .percentile_models import (
    PercentilePortfolio,
    PercentileRequest,
    PercentileResult,
    RankCounts,
)
from .projection_panel import panel_from_snapshot
from .projections import history_digest

router = APIRouter()


def _portfolio(key: str, book: PercentilePortfolio) -> Portfolio:
    return Portfolio(key, {h.asset: Decimal(h.weight) for h in book.weights}, Decimal(book.cash))


@router.post(
    "/v1/percentiles",
    response_model=PercentileResult,
    operation_id="createPercentile",
    responses={
        **PROBLEM_RESPONSES,
        400: {"model": Problem, "description": "Invalid argument"},
        404: {"model": Problem, "description": "No pool for the base date"},
        422: {"model": Problem, "description": "The history cannot build the projection paths"},
        503: {
            "model": Problem,
            "description": "History unavailable or the engine is busy",
            "headers": RETRY_AFTER_HEADER,
        },
    },
)
def create_percentile(
    request: PercentileRequest,
    http_request: Request,
    slots: Annotated[SlotPool, Depends(projection_slots)],
) -> PercentileResult:
    seed = int(request.seasonSeed)
    if not stationary.SEED_MIN <= seed <= stationary.SEED_MAX:
        raise InvalidArgument("seasonSeed must fit in a signed 64-bit integer")
    # weights and cash are checked before any history is read
    team = _portfolio("TEAM", request.team)
    benchmark = _portfolio("BENCHMARK", request.benchmark)
    # resolved after the checks: a bad request is 400 even without a configured database
    reader = history_reader(http_request)
    start = replay_start(http_request)
    if not slots.acquire(blocking=False):
        raise EngineBusy("Too many projections are running; try again later.")
    try:
        snapshot = reader.read(request.baseDate)
        panel = panel_from_snapshot(snapshot)
        result = rank(
            panel,
            request.baseDate,
            season_seed=seed,
            horizon=request.horizonTurns,
            team=team,
            benchmark=benchmark,
            observed_end_multiple=Decimal(request.observed.endMultiple),
            observed_sharpe_diff=Decimal(request.observed.sharpeDiff),
            n=request.n,
            replay_start=start,
        )
        used = sorted({asset for p in (team, benchmark) for asset, _ in p.active})
        digest = history_digest(snapshot, panel, used, request.baseDate, start)
    finally:
        slots.release()
    return PercentileResult(
        projectionVersion=result.projection_version,
        engineVersion=result.engine_version,
        n=result.n,
        horizonTurns=result.horizon,
        historyDigest=digest,
        endMultiple=RankCounts(below=result.end_multiple.below, equal=result.end_multiple.equal),
        sharpeDiff=RankCounts(below=result.sharpe_diff.below, equal=result.sharpe_diff.equal),
    )

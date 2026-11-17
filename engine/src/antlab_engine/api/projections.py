"""POST /v1/projections: bands, drawdown quantiles and breach probability for fixed holdings.

The route returns value multiples (ratios) as decimal strings, never won, months or per-path
values. The history snapshot supplies the returns; the season seed only drives the path seeds.
"""

import hashlib
import math
from datetime import date
from decimal import ROUND_HALF_UP, Context, Decimal
from threading import BoundedSemaphore
from typing import Annotated

from fastapi import APIRouter, Depends

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.simple import REPLAY_START, replay_upper_bound
from antlab_engine.engine_errors import EngineBusy, InvalidArgument
from antlab_engine.errors import Problem
from antlab_engine.history.model import HistoryReader
from antlab_engine.projection import PROJECTION_VERSION, Portfolio, Projection, ReturnPanel, project
from antlab_engine.projection.stats import BAND_POINTS, MDD_POINTS

from . import PROBLEM_RESPONSES
from .deps import history_reader, projection_slots
from .projection_models import (
    EndQuantiles,
    MddQuantiles,
    ProjectionBand,
    ProjectionPortfolioResult,
    ProjectionRequest,
    ProjectionResult,
    ProjectionSummary,
)
from .projection_panel import panel_from_snapshot

RATIO_STEP = Decimal("1e-8")  # the scale of NUMERIC(12,8)
RATIO_CONTEXT = Context(prec=80)
RATIO_LIMIT = 1e24  # keeps every ratio inside the schema's digit budget

router = APIRouter()
_BAND_NAMES = tuple(f"p{k:02d}" for k in BAND_POINTS)
_MDD_NAMES = tuple(f"p{k:02d}" for k in MDD_POINTS)


def ratio(value: float) -> str:
    """A ratio as a decimal string with eight fractional digits, rounded half up once."""
    number = float(value)
    if not math.isfinite(number) or abs(number) >= RATIO_LIMIT:
        raise RuntimeError("a projected value is outside the wire range")
    rounded = Decimal(number).quantize(RATIO_STEP, rounding=ROUND_HALF_UP, context=RATIO_CONTEXT)
    return format(abs(rounded) if rounded == 0 else rounded, "f")


def probability(count: int, n: int) -> str:
    rounded = (Decimal(count) / Decimal(n)).quantize(RATIO_STEP, rounding=ROUND_HALF_UP)
    return format(rounded, "f")


def history_digest(panel: ReturnPanel, used: list[str], base_date: date) -> str:
    """SHA-256 over the versions, the used assets and the eligible months with their factors.

    The eligible months are the replayable months in which every used asset has a return, so a
    changed return, a missing cell or a different pool all change the digest.
    """
    upper = replay_upper_bound(base_date)
    columns = [panel.assets.index(a) for a in used]
    lines = [PROJECTION_VERSION, stationary.ENGINE_VERSION, " ".join(used)]
    for row in panel.rows_with(used):
        month = panel.months[int(row)]
        if REPLAY_START <= month < upper:
            factors = " ".join(repr(float(panel.factors[row, c])) for c in columns)
            lines.append(f"{month:%Y-%m} {factors}")
    return hashlib.sha256("\n".join(lines).encode("ascii")).hexdigest()


def _portfolios(request: ProjectionRequest) -> list[Portfolio]:
    return [
        Portfolio(p.key, {h.asset: Decimal(h.weight) for h in p.weights}, Decimal(p.cash))
        for p in request.portfolios
    ]


def _result(projection: Projection, digest: str) -> ProjectionResult:
    portfolios = []
    for book in projection.portfolios:
        bands = [
            ProjectionBand(turn=turn, **dict(zip(_BAND_NAMES, map(ratio, row), strict=True)))
            for turn, row in enumerate(book.bands, start=1)
        ]
        summaries = [
            ProjectionSummary(
                turn=s.turn,
                end=EndQuantiles(
                    **dict(zip(_BAND_NAMES, map(ratio, s.end_quantiles), strict=True))
                ),
                mdd=MddQuantiles(**dict(zip(_MDD_NAMES, map(ratio, s.mdd_quantiles), strict=True))),
                breachCount=s.breach_count,
                breachProbability=probability(s.breach_count, projection.n),
            )
            for s in book.summaries
        ]
        portfolios.append(ProjectionPortfolioResult(key=book.key, bands=bands, summaries=summaries))
    return ProjectionResult(
        projectionVersion=projection.projection_version,
        engineVersion=projection.engine_version,
        n=projection.n,
        horizonTurns=projection.horizon,
        historyDigest=digest,
        portfolios=portfolios,
    )


@router.post(
    "/v1/projections",
    response_model=ProjectionResult,
    operation_id="createProjection",
    responses={
        **PROBLEM_RESPONSES,
        400: {"model": Problem, "description": "Invalid argument"},
        404: {"model": Problem, "description": "No pool for the base date"},
        422: {"model": Problem, "description": "The history cannot build the projection paths"},
        503: {"model": Problem, "description": "History unavailable or the engine is busy"},
    },
)
def create_projection(
    request: ProjectionRequest,
    reader: Annotated[HistoryReader, Depends(history_reader)],
    slots: Annotated[BoundedSemaphore, Depends(projection_slots)],
) -> ProjectionResult:
    seed = int(request.seasonSeed)
    if not stationary.SEED_MIN <= seed <= stationary.SEED_MAX:
        raise InvalidArgument("seasonSeed must fit in a signed 64-bit integer")
    risk_limit = Decimal(request.riskLimit)
    if not -1 < risk_limit < 0:
        raise InvalidArgument("riskLimit must be between -1 and 0")
    books = _portfolios(request)  # weights and cash are checked before any history is read
    if not slots.acquire(blocking=False):
        raise EngineBusy("Too many projections are running; try again later.")
    try:
        return _run(request, reader, books, seed, risk_limit)
    finally:
        slots.release()


def _run(
    request: ProjectionRequest,
    reader: HistoryReader,
    books: list[Portfolio],
    seed: int,
    risk_limit: Decimal,
) -> ProjectionResult:
    panel = panel_from_snapshot(reader.read(request.baseDate))
    projection = project(
        panel,
        request.baseDate,
        season_seed=seed,
        horizon=request.horizonTurns,
        portfolios=books,
        risk_limit=risk_limit,
        n=request.n,
        summary_turns=request.summaryTurns,
    )
    used = sorted({asset for p in books for asset, _ in p.active})
    return _result(projection, history_digest(panel, used, request.baseDate))

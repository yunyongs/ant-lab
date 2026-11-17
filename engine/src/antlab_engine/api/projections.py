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

from fastapi import APIRouter, Depends, Request

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.simple import REPLAY_START, replay_upper_bound
from antlab_engine.engine_errors import EngineBusy, InvalidArgument
from antlab_engine.errors import Problem
from antlab_engine.history.model import HistoryReader, HistorySnapshot
from antlab_engine.projection import PROJECTION_VERSION, Portfolio, Projection, ReturnPanel, project
from antlab_engine.projection.stats import BAND_POINTS, MDD_POINTS

from . import PROBLEM_RESPONSES, RETRY_AFTER_HEADER
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


def _plain(value: Decimal) -> str:
    """A decimal without exponent or trailing zeros; zero is `0`."""
    return format(value.normalize(), "f") if value else "0"


def history_digest(
    snapshot: HistorySnapshot, panel: ReturnPanel, used: list[str], base_date: date
) -> str:
    """SHA-256 over the versions, the held assets and the source returns of the eligible months.

    Text (ASCII, lines joined by LF, no final LF): `m2-pj-v1`; `m2-sb6-v1`; the held asset keys
    sorted and joined by a space; then one line per eligible month in ascending order:
    `YYYY-MM` and, for each held asset in that order, ` <local_return>,<fx_change>` as plain
    decimals without trailing zeros (zero is `0`; KR assets carry fx `0`). Eligible months are the
    replayable months in which every held asset has a return. The text uses the stored decimals,
    not floats, so another system can reproduce it from `asset_month_return` and `fx_month`.
    """
    upper = replay_upper_bound(base_date)
    month_of = {m.month_block_id: m.month_start for m in snapshot.months}
    key_of = {a.asset_id: f"{a.market}:{a.ticker}" for a in snapshot.pool}
    is_us = {f"{a.market}:{a.ticker}": a.currency == "USD" for a in snapshot.pool}
    fx = {c.month_block_id: c.fx_change for c in snapshot.fx if c.currency == "USD"}
    local = {(key_of[r.asset_id], month_of[r.month_block_id]): r for r in snapshot.returns}
    lines = [PROJECTION_VERSION, stationary.ENGINE_VERSION, " ".join(used)]
    for row in panel.rows_with(used):
        month = panel.months[int(row)]
        if REPLAY_START <= month < upper:
            cells = []
            for asset in used:
                found = local[(asset, month)]
                move = fx[found.month_block_id] if is_us[asset] else Decimal(0)
                cells.append(f"{_plain(found.local_return)},{_plain(move)}")
            lines.append(f"{month:%Y-%m} " + " ".join(cells))
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
        503: {
            "model": Problem,
            "description": "History unavailable or the engine is busy",
            "headers": RETRY_AFTER_HEADER,
        },
    },
)
def create_projection(
    request: ProjectionRequest,
    http_request: Request,
    slots: Annotated[BoundedSemaphore, Depends(projection_slots)],
) -> ProjectionResult:
    seed = int(request.seasonSeed)
    if not stationary.SEED_MIN <= seed <= stationary.SEED_MAX:
        raise InvalidArgument("seasonSeed must fit in a signed 64-bit integer")
    risk_limit = Decimal(request.riskLimit)
    if not -1 < risk_limit < 0:
        raise InvalidArgument("riskLimit must be between -1 and 0")
    books = _portfolios(request)  # weights and cash are checked before any history is read
    # resolved after the checks: a bad request is 400 even without a configured database
    reader = history_reader(http_request)
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
    snapshot = reader.read(request.baseDate)
    panel = panel_from_snapshot(snapshot)
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
    return _result(projection, history_digest(snapshot, panel, used, request.baseDate))

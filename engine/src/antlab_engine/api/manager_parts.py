"""POST /v1/manager-parts: the return and risk parts of one finished season (AG-06, PLAN 6.9).

The season's own months, settings and stored series come in; the answer holds only components,
percentiles and parts, never a month, an asset or a won amount. Cheap checks (series shape and the
Sharpe parity) run before any history is read; the benchmark parity runs on the engine's world.
"""

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Request

from antlab_engine import __version__
from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.errors import Problem
from antlab_engine.leaderboard.parts import (
    SeasonParts,
    TeamSeries,
    checked_components,
    season_parts,
)
from antlab_engine.leaderboard.reference import Reference, ReferenceService, SeasonIdentity
from antlab_engine.sim import money
from antlab_engine.sim.model import Formation, Settings, SimShapeError
from antlab_engine.sim.report import plain

from . import PROBLEM_RESPONSES, RETRY_AFTER_HEADER
from .manager_parts_models import (
    ManagerPartsRequest,
    ManagerPartsResult,
    PartsComponents,
    PartsPercentiles,
    PartsValues,
)

BAD_SETTINGS = "The settings are not a season the simulator can play."

router = APIRouter()


def reference_service(request: Request) -> ReferenceService:
    return request.app.state.reference_service


def identity_of(request: ManagerPartsRequest) -> SeasonIdentity:
    s = request.settings
    f = s.startFormation
    try:
        settings = Settings(
            initial_cash=money.exact_won(Decimal(s.initialCash)),
            fee_rate_kr=Decimal(s.feeRateKr),
            fee_rate_us=Decimal(s.feeRateUs),
            fx_spread_rate=Decimal(s.fxSpreadRate),
            risk_limit=Decimal(s.riskLimit),
            start_formation=Formation(f.goalkeeper, f.defender, f.midfielder, f.forward),
            path_length=s.pathLength,
            member_kind=s.memberKind,
        )
    except SimShapeError:
        raise InvalidArgument(BAD_SETTINGS) from None
    if settings.initial_cash <= 0:
        raise InvalidArgument(BAD_SETTINGS)
    months = tuple(date.fromisoformat(m) for m in request.months)
    return SeasonIdentity(request.referenceVersion, request.baseDate, months, settings)


def team_of(request: ManagerPartsRequest, identity: SeasonIdentity) -> TeamSeries:
    t = request.team
    return TeamSeries(
        identity.settings.initial_cash,
        tuple(Decimal(v) for v in t.totals),
        tuple(Decimal(v) for v in t.returns),
        tuple(Decimal(v) for v in t.benchmarkReturns),
        Decimal(t.sharpe),
        Decimal(t.benchSharpe),
        None if t.riskScore is None else Decimal(t.riskScore),
    )


def _c(value: Decimal) -> str:
    return plain(money.round12(value))


def _p(value: Decimal) -> str:
    return plain(money.round8(value))


def result_of(
    identity: SeasonIdentity, reference: Reference, season: SeasonParts
) -> ManagerPartsResult:
    c, p, parts = season.components, season.percentiles, season.parts
    return ManagerPartsResult(
        referenceVersion=identity.reference_version,
        referenceKey=reference.key,
        referenceSize=reference.size,
        botVersion=reference.bot_version,
        engineVersion=__version__,
        components=PartsComponents(
            cagr=_c(c.cagr),
            benchCagr=_c(c.bench_cagr),
            excessCagr=_c(c.excess_cagr),
            sharpeDiff=_c(c.sharpe_diff),
            sortino=_c(c.sortino),
            benchSortino=_c(c.bench_sortino),
            sortinoDiff=_c(c.sortino_diff),
            calmar=_c(c.calmar),
            benchCalmar=_c(c.bench_calmar),
            calmarDiff=_c(c.calmar_diff),
            mdd=_c(c.mdd),
            benchMdd=_c(c.bench_mdd),
        ),
        percentiles=PartsPercentiles(
            excessCagr=_p(p.excess_cagr),
            sharpeDiff=_p(p.sharpe_diff),
            sortinoDiff=_p(p.sortino_diff),
            calmarDiff=_p(p.calmar_diff),
            riskItem=None if p.risk_item is None else _p(p.risk_item),
            mdd=_p(p.mdd),
        ),
        parts=PartsValues(
            ret=_p(parts.ret), riskAdjusted=_p(parts.risk_adjusted), risk=_p(parts.risk)
        ),
    )


@router.post(
    "/v1/manager-parts",
    response_model=ManagerPartsResult,
    operation_id="createManagerParts",
    responses={
        **PROBLEM_RESPONSES,
        400: {"model": Problem, "description": "Invalid argument"},
        404: {"model": Problem, "description": "No pool for the base date"},
        409: {"model": Problem, "description": "The engine's benchmark or Sharpe differs"},
        422: {"model": Problem, "description": "The history cannot build the season world"},
        503: {
            "model": Problem,
            "description": "History unavailable or a reference set is being built",
            "headers": RETRY_AFTER_HEADER,
        },
    },
)
def create_manager_parts(request: ManagerPartsRequest, http_request: Request) -> ManagerPartsResult:
    identity = identity_of(request)
    team = team_of(request, identity)
    components = checked_components(team, identity.settings.path_length)
    reference = reference_service(http_request).reference(identity)
    return result_of(identity, reference, season_parts(team, components, reference))

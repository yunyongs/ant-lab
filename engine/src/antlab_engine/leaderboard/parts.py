"""Team components, parity checks, percentiles and the three percentile parts of one season.

Pure: decimals in, decimals out. The formulas are the M3-03 ones (`validation.components`); the
one AG-06 rule on top is that the risk item is compared at its stored 2 places on both sides, and
a season without a stored risk item takes the drawdown percentile alone as its risk part.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal

from antlab_engine.engine_errors import InvalidArgument, ReferenceMismatch
from antlab_engine.sim import money
from antlab_engine.sim import score as s63
from antlab_engine.validation.components import series_metrics

from .reference import Reference

MISMATCH = "The engine's benchmark or Sharpe for this season differs from the sent values."
INCONSISTENT = "The sent returns do not follow from the sent totals."

_X = money.EXACT


@dataclass(frozen=True)
class TeamSeries:
    """The season as Java stored it: turn 1..T values, monthly returns and the stored scores."""

    initial_cash: Decimal
    totals: tuple[Decimal, ...]
    returns: tuple[Decimal, ...]
    benchmark_returns: tuple[Decimal, ...]
    sharpe: Decimal
    bench_sharpe: Decimal
    risk_score: Decimal | None


@dataclass(frozen=True)
class TeamComponents:
    cagr: Decimal
    bench_cagr: Decimal
    excess_cagr: Decimal
    sharpe: Decimal
    bench_sharpe: Decimal
    sharpe_diff: Decimal
    sortino: Decimal
    bench_sortino: Decimal
    sortino_diff: Decimal
    calmar: Decimal
    bench_calmar: Decimal
    calmar_diff: Decimal
    mdd: Decimal
    bench_mdd: Decimal


@dataclass(frozen=True)
class Percentiles:
    excess_cagr: Decimal
    sharpe_diff: Decimal
    sortino_diff: Decimal
    calmar_diff: Decimal
    risk_item: Decimal | None
    mdd: Decimal


@dataclass(frozen=True)
class Parts:
    ret: Decimal
    risk_adjusted: Decimal
    risk: Decimal


def check_series(team: TeamSeries, turns: int) -> None:
    """Lengths match the path and the returns are those of the totals (Java's own derivation)."""
    if len(team.totals) != turns or len(team.returns) != turns:
        raise InvalidArgument(INCONSISTENT)
    if len(team.benchmark_returns) != turns:
        raise InvalidArgument(INCONSISTENT)
    if s63.team_returns_of(team.initial_cash, team.totals) != list(team.returns):
        raise InvalidArgument(INCONSISTENT)


def team_components(team: TeamSeries, turns: int) -> TeamComponents:
    values = (team.initial_cash, *team.totals)  # series_metrics skips index 0 (turn 0)
    m = series_metrics(team.initial_cash, values, team.returns, team.benchmark_returns, turns)
    return TeamComponents(
        m.cagr,
        m.bench_cagr,
        _X.subtract(m.cagr, m.bench_cagr),
        m.sharpe,
        m.bench_sharpe,
        _X.subtract(m.sharpe, m.bench_sharpe),
        m.sortino,
        m.bench_sortino,
        _X.subtract(m.sortino, m.bench_sortino),
        m.calmar,
        m.bench_calmar,
        _X.subtract(m.calmar, m.bench_calmar),
        m.mdd,
        m.bench_mdd,
    )


def check_sharpe(team: TeamSeries, components: TeamComponents) -> None:
    """The engine's Sharpe values at their stored 8 places and cap equal the stored ones."""
    if s63._cap_sharpe(components.sharpe) != team.sharpe:
        raise ReferenceMismatch(MISMATCH)
    if s63._cap_sharpe(components.bench_sharpe) != team.bench_sharpe:
        raise ReferenceMismatch(MISMATCH)


def check_benchmark(team: TeamSeries, reference: Reference) -> None:
    """The engine world's benchmark returns equal the sent ones exactly (both at scale 30)."""
    if not _equal(team.benchmark_returns, reference.benchmark_returns):
        raise ReferenceMismatch(MISMATCH)


def _equal(a: Sequence[Decimal], b: Sequence[Decimal]) -> bool:
    return len(a) == len(b) and all(x == y for x, y in zip(a, b, strict=True))


def percentiles(
    components: TeamComponents, risk_score: Decimal | None, reference: Reference
) -> Percentiles:
    ref = reference.metrics
    return Percentiles(
        ref.percentile("excess_cagr", components.excess_cagr),
        ref.percentile("sharpe_diff", components.sharpe_diff),
        ref.percentile("sortino_diff", components.sortino_diff),
        ref.percentile("calmar_diff", components.calmar_diff),
        None if risk_score is None else ref.percentile("risk_item", risk_score),
        ref.percentile("mdd", components.mdd),
    )


def parts_of(p: Percentiles) -> Parts:
    """Return, risk-adjusted (mean of three) and risk (mean of two, or the drawdown alone)."""
    risk_adjusted = s63._mean([p.sharpe_diff, p.sortino_diff, p.calmar_diff])
    risk = p.mdd if p.risk_item is None else s63._mean([p.risk_item, p.mdd])
    return Parts(p.excess_cagr, risk_adjusted, risk)


@dataclass(frozen=True)
class SeasonParts:
    components: TeamComponents
    percentiles: Percentiles
    parts: Parts


def checked_components(team: TeamSeries, turns: int) -> TeamComponents:
    """The checks that need no reference (series shape, Sharpe parity), then the components."""
    check_series(team, turns)
    components = team_components(team, turns)
    check_sharpe(team, components)
    return components


def season_parts(team: TeamSeries, components: TeamComponents, reference: Reference) -> SeasonParts:
    """The benchmark parity check, then the percentiles and parts; a refusal returns nothing."""
    check_benchmark(team, reference)
    ranked = percentiles(components, team.risk_score, reference)
    return SeasonParts(components, ranked, parts_of(ranked))

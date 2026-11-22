"""Season score `s63-v3` and the steps around it (Java `SeasonScoreCalculator`, `ScoreAssembler`).

score = 0.5 x result + 0.5 x process. Result is 100 x the mid-rank percentile of the Sharpe
difference among the engine paths; process is the equal-weight mean of the item scores that have a
value. Every part stays unrounded at working scale and each stored value is rounded once.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, fields
from datetime import date
from decimal import Decimal
from enum import StrEnum

from . import behaviour, money
from .behaviour import TurnSignals
from .counts import (
    CountsSource,
    PercentileCounts,
    PercentileRequest,
    RankCounts,
    WirePortfolio,
)
from .model import SeasonStatus, Side, SimReportError, SimShapeError, SimStateError
from .report import (
    SeasonReport,
    _camel,
    _wire,
    baseline_returns,
    plain,
    sources_from_season,
)
from .report import (
    calculate as calculate_report,
)
from .rules import (
    BENCH_KR,
    BENCH_KR_WEIGHT,
    BENCH_US,
    BENCH_US_WEIGHT,
    SCORE_VERSION,
)
from .season import Season

_X = money.EXACT
_ZERO = Decimal(0)
_ONE = Decimal(1)
_HUNDRED = Decimal(100)
_HALF = Decimal("0.5")
_SHARPE_CAP = Decimal("999999.99999999")
_WIRE_SHARPE_CAP = Decimal("999999.999999999999")
_DIVERSIFICATION_FREE = Decimal("0.10")
_DIVERSIFICATION_SPAN = Decimal("0.40")
_TURNOVER_ZERO_AT = Decimal(2)
_FORMATION_ZERO_AT = Decimal("0.50")
_FORMATION_MAX = Decimal(2)


class RatingExclusion(StrEnum):
    NOT_STARTED = "NOT_STARTED"
    NO_SCORE = "NO_SCORE"


@dataclass(frozen=True)
class DisciplineRatios:
    """Panic-sell and chase-buy shares in [0, 1], or None when not measurable."""

    panic_sell_ratio: Decimal | None
    chase_buy_ratio: Decimal | None

    def __post_init__(self) -> None:
        for value in (self.panic_sell_ratio, self.chase_buy_ratio):
            if value is not None and not _ZERO <= value <= _ONE:
                raise SimShapeError("a discipline ratio must be within 0..1")


@dataclass(frozen=True)
class ScoreInputs:
    returns: tuple[Decimal, ...]
    benchmark_returns: tuple[Decimal, ...]
    report_turns: int
    risk_breach_turns: int | None
    risk_breach_depth: Decimal | None
    risk_limit: Decimal | None
    max_single_weight: Decimal | None
    turnover_annual: Decimal | None
    measured_formation_deviation: Decimal | None
    role_fit: Decimal | None
    nudge_share: Decimal | None
    noise_share: Decimal | None
    discipline: DisciplineRatios | None
    sharpe_diff_counts: RankCounts | None
    end_multiple_counts: RankCounts | None


@dataclass(frozen=True)
class SeasonScore:
    """Stored values, rounded for their columns (scores 2 decimals, Sharpe and percentiles 8)."""

    score_version: str
    score: Decimal | None
    result_score: Decimal | None
    process_score: Decimal
    sharpe: Decimal
    bench_sharpe: Decimal
    sharpe_diff: Decimal
    sharpe_diff_percentile: Decimal | None
    luck_percentile: Decimal | None
    risk_score: Decimal | None
    discipline_score: Decimal | None
    diversification_score: Decimal | None
    turnover_score: Decimal | None
    consistency_score: Decimal | None
    formation_score: Decimal | None
    role_score: Decimal | None
    noise_score: Decimal | None
    nudge_score: Decimal | None
    process_items: int

    def to_wire(self) -> dict[str, object]:
        return {_camel(f.name): _wire(getattr(self, f.name)) for f in fields(self)}


# ---------------------------------------------------------------------------- Sharpe, percentile


def sharpe_exact(returns: Sequence[Decimal]) -> Decimal:
    """mean / sample stddev (n - 1), risk-free 0; 0 when every return is equal (exact test)."""
    if len(returns) < 2 or any(r is None for r in returns):
        raise SimShapeError("sharpe needs at least two non-null returns")
    n = Decimal(len(returns))
    total = squares = _ZERO
    for r in returns:
        total = _X.add(total, r)
        squares = _X.add(squares, _X.multiply(r, r))
    scatter = _X.subtract(_X.multiply(n, squares), _X.multiply(total, total))
    if scatter == 0:
        return _ZERO
    variance = money.divide_significant(scatter, _X.multiply(n, _X.subtract(n, _ONE)))
    stddev = money.sqrt_significant(variance)
    mean = money.divide_significant(total, n)
    return money.score_divide(mean, stddev)


def percentile_exact(counts: RankCounts) -> Decimal:
    """(2 below + equal) / (2 n), unrounded."""
    return money.score_divide(Decimal(2 * counts.below + counts.equal), Decimal(2 * counts.n))


def percentile_stored(counts: RankCounts) -> Decimal:
    return money.round8(percentile_exact(counts))


# ---------------------------------------------------------------------------- calculator


def calculate(inputs: ScoreInputs) -> SeasonScore:
    _validate(inputs)
    sharpe = sharpe_exact(inputs.returns)
    bench_sharpe = sharpe_exact(inputs.benchmark_returns)
    sharpe_diff = _X.subtract(sharpe, bench_sharpe)

    items: list[Decimal] = []
    risk = _collect(items, _risk(inputs))
    disc = _collect(items, _discipline(inputs.discipline))
    diversification = _collect(items, _diversification(inputs.max_single_weight))
    turnover = _collect(items, _turnover(inputs.turnover_annual))
    formation = _collect(items, _formation(inputs.measured_formation_deviation))
    role = _collect(items, _role(inputs.role_fit))
    noise = _collect(items, _noise(inputs.noise_share))
    nudge = _collect(items, _nudge(inputs.nudge_share))
    if not items:
        raise SimShapeError("at least one process item is needed")
    process = _mean(items)

    result_exact = score = diff_percentile = luck_percentile = None
    if inputs.sharpe_diff_counts is not None and inputs.end_multiple_counts is not None:
        result_exact = _X.multiply(_HUNDRED, percentile_exact(inputs.sharpe_diff_counts))
        score = money.round2(_X.add(_X.multiply(_HALF, result_exact), _X.multiply(_HALF, process)))
        diff_percentile = percentile_stored(inputs.sharpe_diff_counts)
        luck_percentile = percentile_stored(inputs.end_multiple_counts)
    return SeasonScore(
        SCORE_VERSION,
        score,
        None if result_exact is None else money.round2(result_exact),
        money.round2(process),
        _cap_sharpe(sharpe),
        _cap_sharpe(bench_sharpe),
        _cap_sharpe(sharpe_diff),
        diff_percentile,
        luck_percentile,
        _round2(risk),
        _round2(disc),
        _round2(diversification),
        _round2(turnover),
        None,
        _round2(formation),
        _round2(role),
        _round2(noise),
        _round2(nudge),
        len(items),
    )


def exclusion(not_started: bool, result: SeasonScore) -> RatingExclusion | None:
    """Why the season stays out of the rating; not-started wins over no-score."""
    if not_started:
        return RatingExclusion.NOT_STARTED
    return RatingExclusion.NO_SCORE if result.score is None else None


def _risk(inputs: ScoreInputs) -> Decimal | None:
    """100 x (1 - 0.5 x breach turns / report turns - 0.5 x clamp(|depth| / |limit|))."""
    if (
        inputs.risk_breach_turns is None
        or inputs.risk_breach_depth is None
        or inputs.risk_limit is None
    ):
        return None
    turn_share = money.score_divide(Decimal(inputs.risk_breach_turns), Decimal(inputs.report_turns))
    depth_share = _clamp01(
        money.score_divide(inputs.risk_breach_depth.copy_abs(), inputs.risk_limit.copy_abs())
    )
    return _X.multiply(
        _HUNDRED,
        _X.subtract(
            _X.subtract(_ONE, _X.multiply(_HALF, turn_share)), _X.multiply(_HALF, depth_share)
        ),
    )


def _discipline(facts: DisciplineRatios | None) -> Decimal | None:
    """100 x (1 - mean of the measured panic-sell and chase-buy ratios)."""
    if facts is None:
        return None
    ratios = [r for r in (facts.panic_sell_ratio, facts.chase_buy_ratio) if r is not None]
    if not ratios:
        return None
    return _X.multiply(_HUNDRED, _X.subtract(_ONE, _mean(ratios)))


def _diversification(max_single_weight: Decimal | None) -> Decimal | None:
    """Full marks up to a 10 % single weight, zero from 50 %."""
    if max_single_weight is None:
        return None
    _require_range(max_single_weight, _ONE)
    excess = money.score_divide(
        _X.subtract(max_single_weight, _DIVERSIFICATION_FREE), _DIVERSIFICATION_SPAN
    )
    return _X.multiply(_HUNDRED, _X.subtract(_ONE, _clamp01(excess)))


def _turnover(turnover_annual: Decimal | None) -> Decimal | None:
    """Zero at 200 % annual turnover."""
    if turnover_annual is None:
        return None
    if turnover_annual < 0:
        raise SimShapeError("item input must not be negative")
    share = _clamp01(money.score_divide(turnover_annual, _TURNOVER_ZERO_AT))
    return _X.multiply(_HUNDRED, _X.subtract(_ONE, share))


def _formation(deviation: Decimal | None) -> Decimal | None:
    """Zero at a mean formation deviation of 0.50."""
    if deviation is None:
        return None
    _require_range(deviation, _FORMATION_MAX)
    share = _clamp01(money.score_divide(deviation, _FORMATION_ZERO_AT))
    return _X.multiply(_HUNDRED, _X.subtract(_ONE, share))


def _role(role_fit: Decimal | None) -> Decimal | None:
    if role_fit is None:
        return None
    _require_range(role_fit, _ONE)
    return _X.multiply(_HUNDRED, role_fit)


def _noise(noise_share: Decimal | None) -> Decimal | None:
    """100 x (1 - noise share): zero when every trade followed the noise."""
    if noise_share is None:
        return None
    _require_range(noise_share, _ONE)
    return _X.multiply(_HUNDRED, _X.subtract(_ONE, noise_share))


def _nudge(nudge_share: Decimal | None) -> Decimal | None:
    if nudge_share is None:
        return None
    _require_range(nudge_share, _ONE)
    return _X.multiply(_HUNDRED, _X.subtract(_ONE, nudge_share))


def _collect(items: list[Decimal], item: Decimal | None) -> Decimal | None:
    if item is not None:
        items.append(item)
    return item


def _mean(values: Sequence[Decimal]) -> Decimal:
    total = _ZERO
    for value in values:
        total = _X.add(total, value)
    return money.score_divide(total, Decimal(len(values)))


def _clamp01(x: Decimal) -> Decimal:
    return min(max(x, _ZERO), _ONE)


def _round2(value: Decimal | None) -> Decimal | None:
    return None if value is None else money.round2(value)


def _cap_sharpe(value: Decimal) -> Decimal:
    """Column scale 8 and range: a value under 10^6 in magnitude."""
    return min(max(money.round8(value), -_SHARPE_CAP), _SHARPE_CAP)


def _require_range(value: Decimal, high: Decimal) -> None:
    if value < 0 or value > high:
        raise SimShapeError("item input out of range")


def _validate(inputs: ScoreInputs) -> None:
    if inputs.report_turns < 1:
        raise SimShapeError("report turns must be at least 1")
    if (
        len(inputs.returns) != inputs.report_turns
        or len(inputs.benchmark_returns) != inputs.report_turns
    ):
        raise SimShapeError("return series must have one value per report turn")
    if (inputs.sharpe_diff_counts is None) != (inputs.end_multiple_counts is None):
        raise SimShapeError("rank counts must be given together or not at all")
    if (
        inputs.sharpe_diff_counts is not None
        and inputs.end_multiple_counts is not None
        and inputs.sharpe_diff_counts.n != inputs.end_multiple_counts.n
    ):
        raise SimShapeError("both rank counts must come from the same number of paths")
    breach = inputs.risk_breach_turns
    if breach is not None and not 0 <= breach <= inputs.report_turns:
        raise SimShapeError("risk breach turns out of range")
    if inputs.risk_breach_depth is not None and inputs.risk_breach_depth > 0:
        raise SimShapeError("risk breach depth must not be positive")
    if inputs.risk_limit is not None and inputs.risk_limit == 0:
        raise SimShapeError("risk limit must not be zero")


# ---------------------------------------------------------------------------- assembling


@dataclass(frozen=True)
class StartTeam:
    """The team after the turn-0 team building, as fixed shares of its total value."""

    cash: Decimal
    total_value: Decimal
    cash_share: Decimal
    shares: tuple[tuple[str, Decimal], ...]
    quantities: tuple[tuple[str, int], ...]


@dataclass(frozen=True)
class Observation:
    returns: tuple[Decimal, ...]
    benchmark_returns: tuple[Decimal, ...]
    sharpe: Decimal
    bench_sharpe: Decimal
    sharpe_diff: Decimal
    static_end_multiple: Decimal


def make_start_team(cash: Decimal, held: Sequence[tuple[str, int, Decimal]]) -> StartTeam:
    """Shares of the team's total value (rounded down at 12 digits); cash is the exact remainder.

    `held` is (asset, quantity, turn-0 price); a non-positive quantity is left out.
    """
    held = [h for h in held if h[1] > 0]
    valuations = [money.valuation(price, quantity) for _, quantity, price in held]
    total = money.total_value(cash, valuations)
    if total <= 0:
        raise SimReportError("the start team is empty")
    shares = []
    weight_sum = _ZERO
    for (asset, _, _), valuation in zip(held, valuations, strict=True):
        weight = money.share_down(valuation, total)
        shares.append((asset, weight))
        weight_sum = _X.add(weight_sum, weight)
    return StartTeam(
        cash,
        total,
        _X.subtract(_ONE, weight_sum),
        tuple(shares),
        tuple((asset, quantity) for asset, quantity, _ in held),
    )


def start_team(season: Season) -> StartTeam:
    """Cash after the opening deposit and turn-0 orders; the net turn-0 buys at turn-0 prices."""
    cash = next((row.balance_after for row in reversed(season.ledger) if row.turn == 0), None)
    if cash is None:
        raise SimReportError("the ledger has no opening deposit")
    net: dict[str, int] = {}
    for e in season.executions:
        if e.turn == 0:
            net[e.asset] = net.get(e.asset, 0) + (e.quantity if e.side is Side.BUY else -e.quantity)
    prices = season.turn_prices[0]
    # pool order stands in for Java's asset-id order
    return make_start_team(
        cash, [(a.asset, net.get(a.asset, 0), prices[a.asset]) for a in season.inputs.pool]
    )


def start_end_value(team: StartTeam, last_prices: Mapping[str, Decimal]) -> Decimal:
    """Cash of the start team plus its quantities at the last turn's prices."""
    valuations = [money.valuation(last_prices[a], q) for a, q in team.quantities]
    return money.total_value(team.cash, valuations)


def team_returns_of(initial_cash: Decimal, totals: Sequence[Decimal]) -> list[Decimal]:
    """Monthly returns on the report's base (turn 0 = initial cash, so the team fees count)."""
    out = []
    previous = initial_cash
    for total in totals:
        out.append(money.score_divide(_X.subtract(total, previous), previous))
        previous = total
    return out


def team_returns(season: Season) -> list[Decimal]:
    return team_returns_of(
        money.exact_won(season.settings.initial_cash), [t.total for t in season.turns]
    )


def observe(
    returns: Sequence[Decimal],
    benchmark_returns: Sequence[Decimal],
    team: StartTeam,
    end_value: Decimal,
) -> Observation:
    """Both Sharpe values unrounded and the start team's end multiple held to the last turn."""
    sharpe = sharpe_exact(returns)
    bench_sharpe = sharpe_exact(benchmark_returns)
    return Observation(
        tuple(returns),
        tuple(benchmark_returns),
        sharpe,
        bench_sharpe,
        _X.subtract(sharpe, bench_sharpe),
        money.score_divide(end_value, team.total_value),
    )


def build_request(
    team: StartTeam,
    observation: Observation,
    *,
    season_seed: int,
    base_date: date,
    horizon: int,
    path_count: int,
) -> PercentileRequest:
    """The request Java sends: start team and benchmark held still, observed values as strings."""
    team_book = WirePortfolio(
        plain(team.cash_share), tuple((asset, plain(weight)) for asset, weight in team.shares)
    )
    bench_book = WirePortfolio(
        "0", ((BENCH_KR, plain(BENCH_KR_WEIGHT)), (BENCH_US, plain(BENCH_US_WEIGHT)))
    )
    capped = min(max(observation.sharpe_diff, -_WIRE_SHARPE_CAP), _WIRE_SHARPE_CAP)
    return PercentileRequest(
        season_seed,
        base_date,
        horizon,
        path_count,
        team_book,
        bench_book,
        plain(money.round12(observation.static_end_multiple)),
        plain(money.round12(capped)),
    )


def discipline_facts_of(season: Season) -> behaviour.DisciplineFacts:
    """The orders, changes and turn values of turns 1..T-1 as `behaviour.discipline` takes them."""
    t = season.current_turn
    changes = tuple(
        behaviour.DisciplineChange(i, c.turn, c.before.forward, c.after.forward)
        for i, c in enumerate(season.formation_changes)
    )
    orders = tuple(
        behaviour.DisciplineOrder(
            e.turn,
            e.side,
            e.asset,
            e.notional,
            e.formation_change,
            season.turn_prices[e.turn][e.asset] if e.turn >= 1 else None,
            season.turn_prices[e.turn - 1][e.asset] if e.turn >= 1 else None,
        )
        for e in season.executions
        if e.turn < t
    )
    return behaviour.DisciplineFacts(
        t,
        money.exact_won(season.settings.initial_cash),
        tuple(behaviour.TurnValue(x.turn, x.total) for x in season.turns),
        changes,
        orders,
    )


def discipline_of(season: Season) -> DisciplineRatios | None:
    """Panic-sell and chase-buy shares of turns 1..T-1; None when both are unmeasurable."""
    result = behaviour.discipline(discipline_facts_of(season))
    if result.panic_sell_share is None and result.chase_buy_share is None:
        return None
    return DisciplineRatios(result.panic_sell_share, result.chase_buy_share)


def noise_of(season: Season, signals: Sequence[TurnSignals] | None) -> Decimal | None:
    """Noise share from caller-supplied signals; without signals the item is empty."""
    if signals is None:
        return None
    t = season.current_turn
    orders = [
        behaviour.NoiseOrder(e.turn, e.side, e.asset, e.notional)
        for e in season.executions
        if e.turn < t
    ]
    return behaviour.noise_of_season(t, signals, orders).noise_share


@dataclass(frozen=True)
class ScoredSeason:
    report: SeasonReport
    score: SeasonScore
    exclusion: RatingExclusion | None
    percentile_request: PercentileRequest | None
    counts: PercentileCounts | None
    observation: Observation
    discipline: DisciplineRatios | None
    noise_share: Decimal | None


def score_season(
    season: Season,
    counts_source: CountsSource | None,
    *,
    season_seed: int,
    base_date: date,
    path_count: int = 2000,
    signals: Sequence[TurnSignals] | None = None,
) -> ScoredSeason:
    """Report, request, counts and score of a finished season.

    No path distribution (no `counts_source`, or the source answers None for a history that
    cannot carry the horizon, Java `PATH_TOO_LONG`): result score and score stay empty and the
    season is excluded as NO_SCORE; the request is still built when a source was asked. A missing
    `counts_source` is a simulator convenience (Java's unsupported-horizon branch cannot occur for
    12/24/60 turns). Contradicting noise signals raise here; Java logs them and stores no noise
    item, a guard for its database path.
    """
    if season.status is not SeasonStatus.FINISHED:
        raise SimStateError("only a finished season has a score")
    sources = sources_from_season(season)
    report = calculate_report(sources)
    team = start_team(season)
    observation = observe(
        team_returns(season),
        baseline_returns(sources),
        team,
        start_end_value(team, season.turn_prices[season.current_turn]),
    )
    request = None
    counts = None
    if counts_source is not None:
        request = build_request(
            team,
            observation,
            season_seed=season_seed,
            base_date=base_date,
            horizon=season.path_length,
            path_count=path_count,
        )
        counts = counts_source.counts(request)
    ratios = discipline_of(season)
    noise_share = noise_of(season, signals)
    inputs = ScoreInputs(
        observation.returns,
        observation.benchmark_returns,
        report.report_turns,
        report.risk_breach_turns,
        report.risk_breach_depth,
        report.risk_limit,
        report.max_single_weight,
        report.turnover_annual,
        report.measured_formation_deviation,
        report.role_fit,
        report.nudge_share,
        noise_share,
        ratios,
        None if counts is None else counts.sharpe_diff,
        None if counts is None else counts.end_multiple,
    )
    score = calculate(inputs)
    return ScoredSeason(
        report,
        score,
        exclusion(season.not_started, score),
        request,
        counts,
        observation,
        ratios,
        noise_share,
    )

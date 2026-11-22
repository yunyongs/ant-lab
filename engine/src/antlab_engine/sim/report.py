"""Season report: the 38 values of Java `SeasonReportDto` from integer won facts.

`calculate` is the port of `SeasonReportCalculator` over `ReportSources` (the port of
`SeasonReportSources`), so the Java hand cases can be replayed by value. `sources_from_season`
builds the same facts from a played `Season` the way `SeasonReportQuery` reads them from the
database. Amounts are whole won; every ratio is one `money.ratio` division, as in Java.
"""

from collections import defaultdict
from dataclasses import dataclass, fields
from decimal import Decimal

from . import measure, money
from .model import (
    KRW,
    Position,
    SeasonStatus,
    Side,
    SimReportError,
    SourceKind,
)
from .rules import (
    ANNUAL_TURNS,
    BENCH_KR,
    BENCH_KR_WEIGHT,
    BENCH_US,
    BENCH_US_WEIGHT,
    NUDGE_SOURCES,
    PLATFORM_SOURCES,
    SEASON_MIN_DOWN,
    SEASON_MIN_MONTHS,
    SEASON_MIN_UP,
)
from .season import Season

_X = money.EXACT
_HUNDRED = Decimal(100)
_ZERO = Decimal(0)
_BENCH_LEGS = ((BENCH_KR, BENCH_KR_WEIGHT), (BENCH_US, BENCH_US_WEIGHT))


@dataclass(frozen=True)
class SeasonFacts:
    status: SeasonStatus
    path_length: int
    current_turn: int
    initial_cash: Decimal
    risk_limit: Decimal
    fee_rate_kr: Decimal
    fee_rate_us: Decimal
    fx_spread_rate: Decimal
    start_goalkeeper: int
    start_defender: int
    start_midfielder: int
    start_forward: int


@dataclass(frozen=True)
class TurnFact:
    turn: int
    total_value: Decimal
    drawdown: Decimal
    goalkeeper_value: Decimal
    defender_value: Decimal
    midfielder_value: Decimal
    forward_value: Decimal


@dataclass(frozen=True)
class ChangeFact:
    """A formation change: the target formation after it."""

    id: int
    turn: int
    after_goalkeeper: int
    after_defender: int
    after_midfielder: int
    after_forward: int


@dataclass(frozen=True)
class OrderFacts:
    """Sums over orders of turns before the report turn; notional and nudge sums skip turn 0."""

    trade_notional: Decimal
    total_fee: Decimal
    team_fee: Decimal
    platform_nudge_fee: Decimal
    nudge_notional: Decimal
    nudge_same_turn_notional: Decimal
    pending_orders: int
    orders_after_report: int


@dataclass(frozen=True)
class HoldingFact:
    """One held asset at the start of a turn: quantity x that turn's price (never zero)."""

    asset: str
    turn: int
    value: Decimal
    system: Position | None


@dataclass(frozen=True)
class LineupFact:
    asset: str
    declared: Position
    start_turn: int
    end_turn: int | None


@dataclass(frozen=True)
class ChangeCost:
    change_id: int
    turn: int
    order_count: int
    fee_sum: Decimal


@dataclass(frozen=True)
class ReconFacts:
    mismatch_rows: int
    turn_bundles: int
    manual_bundles: int
    out_of_range_rows: int


@dataclass(frozen=True)
class ReportSources:
    season: SeasonFacts
    turns: tuple[TurnFact, ...]
    changes: tuple[ChangeFact, ...]
    orders: OrderFacts
    holdings: tuple[HoldingFact, ...]
    lineup: tuple[LineupFact, ...]
    pool_returns: dict[str, list[Decimal | None]]
    benchmark_prices: dict[str, list[Decimal]]
    change_costs: tuple[ChangeCost, ...]
    recon: ReconFacts


@dataclass(frozen=True)
class SeasonReport:
    """The 38 report values in Java's component order."""

    season_status: SeasonStatus
    is_final: bool
    report_turns: int
    path_length: int
    pending_orders: int
    pending_changes: int
    initial_cash: Decimal
    final_value: Decimal
    total_return: Decimal
    monthly_volatility: Decimal | None
    max_drawdown: Decimal
    baseline_final_value: Decimal
    baseline_return: Decimal
    excess_return: Decimal
    risk_limit: Decimal
    risk_breach_turns: int
    risk_breach_depth: Decimal
    max_single_weight: Decimal
    trade_notional: Decimal
    turnover_annual: Decimal
    formation_deviation: Decimal
    measured_formation_deviation: Decimal
    role_fit: Decimal | None
    role_players_judged: int
    role_players_unjudged: int
    nudge_share: Decimal | None
    nudge_same_turn_share: Decimal | None
    total_fee: Decimal
    team_fee: Decimal
    formation_change_fee: Decimal
    platform_nudge_fee: Decimal
    formation_changes: int
    formation_change_orders: int
    changes_without_orders: int
    recon_mismatch_rows: int
    recon_turn_bundles: int
    recon_manual_bundles: int
    recon_out_of_range_rows: int

    def to_wire(self) -> dict[str, object]:
        """Java's JSON names; decimals as plain strings, status as its name."""
        return {_camel(f.name): _wire(getattr(self, f.name)) for f in fields(self)}


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)


def _wire(value: object) -> object:
    if isinstance(value, Decimal):
        return plain(value)
    if isinstance(value, SeasonStatus):
        return value.value
    return value


def plain(value: Decimal) -> str:
    """Java `toPlainString`: no exponent, and never a negative zero."""
    text = format(value, "f")
    return text[1:] if text.startswith("-") and value == 0 else text


# ---------------------------------------------------------------------------- calculator


def calculate(src: ReportSources) -> SeasonReport:
    season = src.season
    t = season.current_turn
    _validate(src)

    turns = src.turns
    initial_cash = season.initial_cash
    final_value = turns[t - 1].total_value
    sum_value = _ZERO
    for turn in turns:
        sum_value = _X.add(sum_value, turn.total_value)

    total_return = money.ratio(_X.subtract(final_value, initial_cash), initial_cash)
    period_returns = []
    previous = initial_cash
    for turn in turns:
        period_returns.append(money.pad40_divide(_X.subtract(turn.total_value, previous), previous))
        previous = turn.total_value
    monthly_volatility = money.sample_stddev8(period_returns)

    worst: Decimal | None = None
    breach_turns = 0
    min_diff: Decimal | None = None
    for turn in turns:
        if worst is None or turn.drawdown < worst:
            worst = turn.drawdown
        diff = _X.subtract(turn.drawdown, season.risk_limit)
        if min_diff is None or diff < min_diff:
            min_diff = diff
        if turn.drawdown < season.risk_limit:
            breach_turns += 1
    if worst is None or min_diff is None:
        raise SimReportError("no turn snapshots")
    breach_depth = money.round8(min(min_diff, _ZERO))

    baseline_final = baseline_value_at(src, t)
    baseline_return = money.ratio(_X.subtract(baseline_final, initial_cash), initial_cash)
    excess_return = money.ratio(_X.subtract(final_value, baseline_final), initial_cash)

    max_holding: dict[int, Decimal] = {}
    for h in src.holdings:
        if h.turn not in max_holding or h.value > max_holding[h.turn]:
            max_holding[h.turn] = h.value
    max_weight: Decimal | None = None
    numerators: list[int] = []
    denominators: list[int] = []
    for turn in turns:
        weight = money.ratio(max_holding.get(turn.turn, _ZERO), turn.total_value)
        if max_weight is None or weight > max_weight:
            max_weight = weight
        target = _target_formation(src, turn.turn)
        total = turn.total_value
        numerator = _X.add(
            _X.add(
                _deviation(turn.goalkeeper_value, target[0], total),
                _deviation(turn.defender_value, target[1], total),
            ),
            _X.add(
                _deviation(turn.midfielder_value, target[2], total),
                _deviation(turn.forward_value, target[3], total),
            ),
        )
        numerators.append(_exact_integer(numerator))
        denominators.append(_exact_integer(_X.multiply(_HUNDRED, total)))
    if max_weight is None:
        raise SimReportError("no turn snapshots")
    formation_deviation = money.fraction_mean(numerators, denominators)

    measured = season_measured(src)
    measured_deviation = _measured_deviation(src, measured)

    orders = src.orders
    turnover = money.ratio(_X.multiply(Decimal(ANNUAL_TURNS), orders.trade_notional), sum_value)
    nudge_share = nudge_same = None
    if orders.trade_notional > 0:
        nudge_share = money.exact_rate(money.ratio(orders.nudge_notional, orders.trade_notional))
        nudge_same = money.exact_rate(
            money.ratio(orders.nudge_same_turn_notional, orders.trade_notional)
        )
    change_fee = _ZERO
    change_count = change_orders = without_orders = 0
    for cost in src.change_costs:
        if cost.turn >= t:
            continue
        change_count += 1
        change_fee = _X.add(change_fee, cost.fee_sum)
        change_orders += cost.order_count
        if cost.order_count == 0:
            without_orders += 1

    fit, judged, unjudged = _role(src, measured)
    pending_changes = sum(1 for c in src.changes if c.turn == t)
    recon = src.recon
    return SeasonReport(
        season_status=season.status,
        is_final=season.status is SeasonStatus.FINISHED,
        report_turns=t,
        path_length=season.path_length,
        pending_orders=orders.pending_orders,
        pending_changes=pending_changes,
        initial_cash=money.exact_won(initial_cash),
        final_value=money.exact_won(final_value),
        total_return=money.exact_rate(total_return),
        monthly_volatility=None
        if monthly_volatility is None
        else money.exact_rate(monthly_volatility),
        max_drawdown=money.exact_rate(worst),
        baseline_final_value=money.exact_won(baseline_final),
        baseline_return=money.exact_rate(baseline_return),
        excess_return=money.exact_rate(excess_return),
        risk_limit=money.exact_rate(season.risk_limit),
        risk_breach_turns=breach_turns,
        risk_breach_depth=money.exact_rate(breach_depth),
        max_single_weight=money.exact_rate(max_weight),
        trade_notional=money.exact_won(orders.trade_notional),
        turnover_annual=money.exact_rate(turnover),
        formation_deviation=money.exact_rate(formation_deviation),
        measured_formation_deviation=money.exact_rate(measured_deviation),
        role_fit=fit,
        role_players_judged=judged,
        role_players_unjudged=unjudged,
        nudge_share=nudge_share,
        nudge_same_turn_share=nudge_same,
        total_fee=money.exact_won(orders.total_fee),
        team_fee=money.exact_won(orders.team_fee),
        formation_change_fee=money.exact_won(change_fee),
        platform_nudge_fee=money.exact_won(orders.platform_nudge_fee),
        formation_changes=change_count,
        formation_change_orders=change_orders,
        changes_without_orders=without_orders,
        recon_mismatch_rows=recon.mismatch_rows,
        recon_turn_bundles=recon.turn_bundles,
        recon_manual_bundles=recon.manual_bundles,
        recon_out_of_range_rows=recon.out_of_range_rows,
    )


def baseline_value_at(src: ReportSources, turn: int) -> Decimal:
    """Two proxy assets bought at turn 0 with half of the cash each (whole shares), held."""
    season = src.season
    cash = season.initial_cash
    held = _ZERO
    for key, weight in _BENCH_LEGS:
        prices = src.benchmark_prices[key]
        market = key.partition(":")[0]
        rate = money.fee_rate(market, season.fee_rate_kr, season.fee_rate_us, season.fx_spread_rate)
        first = prices[0]
        quantity = money.team_qty(_X.multiply(season.initial_cash, weight), first, rate)
        fee = _ZERO if quantity == 0 else money.fee(first, quantity, rate)
        count = Decimal(quantity)
        cash = _X.subtract(cash, _X.add(_X.multiply(count, first), fee))
        held = _X.add(held, _X.multiply(count, prices[turn]))
    return _X.add(held, cash)


def baseline_returns(src: ReportSources) -> list[Decimal]:
    """Benchmark monthly returns for turns 1..T on the team's base (first against initial cash)."""
    out = []
    previous = src.season.initial_cash
    for turn in range(1, src.season.current_turn + 1):
        value = baseline_value_at(src, turn)
        out.append(money.score_divide(_X.subtract(value, previous), previous))
        previous = value
    return out


def _target_formation(src: ReportSources, turn: int) -> tuple[int, int, int, int]:
    last: ChangeFact | None = None
    for change in src.changes:
        if change.turn < turn:
            last = change
    s = src.season
    if last is None:
        return s.start_goalkeeper, s.start_defender, s.start_midfielder, s.start_forward
    return last.after_goalkeeper, last.after_defender, last.after_midfielder, last.after_forward


def _deviation(position_value: Decimal, target: int, total: Decimal) -> Decimal:
    scaled = _X.subtract(_X.multiply(_HUNDRED, position_value), _X.multiply(Decimal(target), total))
    return scaled.copy_abs()


def _exact_integer(value: Decimal) -> int:
    if value != value.to_integral_value():
        raise SimReportError("a value that is not whole won appears")
    return int(value)


def season_measured(src: ReportSources) -> dict[str, Position]:
    """Positions measured on this season's path over the whole pool; short samples are absent."""
    t = src.season.current_turn
    # str() keeps 0.1 and 0.10 apart: the division scale follows the input scale
    key = (
        t,
        tuple((asset, tuple(map(str, returns))) for asset, returns in src.pool_returns.items()),
    )
    cached = _MEASURED_CACHE.get(key)
    if cached is None:
        # A pure function of the path, so many bots on one world share one measurement.
        cached = _measure_pool(t, src.pool_returns)
        if len(_MEASURED_CACHE) >= _MEASURED_CACHE_MAX:
            del _MEASURED_CACHE[next(iter(_MEASURED_CACHE))]
        _MEASURED_CACHE[key] = cached
    return dict(cached)


_MEASURED_CACHE_MAX = 32
_MEASURED_CACHE: dict[tuple, dict[str, Position]] = {}


def _measure_pool(t: int, returns_by_asset: dict[str, list[Decimal | None]]) -> dict[str, Position]:
    kr = returns_by_asset[BENCH_KR]
    us = returns_by_asset[BENCH_US]
    market = [measure.market_return(kr[i], us[i]) for i in range(t)]
    metrics = {
        asset: measure.measure(returns, market, SEASON_MIN_MONTHS, SEASON_MIN_UP, SEASON_MIN_DOWN)
        for asset, returns in returns_by_asset.items()
    }
    market_max_drawdown = measure.max_drawdown(market)
    if market_max_drawdown is None:
        raise SimReportError("no market returns")
    return measure.classify_pool(metrics, market_max_drawdown)


def _measured_deviation(src: ReportSources, measured: dict[str, Position]) -> Decimal:
    """Formation deviation on measured positions; goalkeeper is the residual of the total."""
    class_sums: dict[int, list[Decimal]] = {}
    for h in src.holdings:
        position = measured.get(h.asset) or h.system
        if position is None:
            raise SimReportError("a held asset has neither a measured nor a base-date position")
        if position is Position.GOALKEEPER:
            continue
        index = 0 if position is Position.DEFENDER else 1 if position is Position.MIDFIELDER else 2
        sums = class_sums.setdefault(h.turn, [_ZERO, _ZERO, _ZERO])
        sums[index] = _X.add(sums[index], h.value)
    numerators: list[int] = []
    denominators: list[int] = []
    for turn in src.turns:
        sums = class_sums.get(turn.turn, [_ZERO, _ZERO, _ZERO])
        total = turn.total_value
        goalkeeper = _X.subtract(_X.subtract(_X.subtract(total, sums[0]), sums[1]), sums[2])
        if goalkeeper < 0:
            raise SimReportError("measured position values exceed the total value")
        target = _target_formation(src, turn.turn)
        numerator = _X.add(
            _X.add(_deviation(goalkeeper, target[0], total), _deviation(sums[0], target[1], total)),
            _X.add(_deviation(sums[1], target[2], total), _deviation(sums[2], target[3], total)),
        )
        numerators.append(_exact_integer(numerator))
        denominators.append(_exact_integer(_X.multiply(_HUNDRED, total)))
    return money.fraction_mean(numerators, denominators)


def _role(src: ReportSources, measured: dict[str, Position]) -> tuple[Decimal | None, int, int]:
    """Share of measured exposure whose declared position equals the measured one."""
    exposure: dict[tuple[str, Position], Decimal] = {}
    for h in src.holdings:
        declared = _declared_at(src.lineup, h.asset, h.turn)
        key = (h.asset, declared)
        exposure[key] = _X.add(exposure.get(key, _ZERO), h.value)
    matched = judged_value = _ZERO
    judged: set[str] = set()
    unjudged: set[str] = set()
    for (asset, declared), value in exposure.items():
        actual = measured.get(asset)
        if actual is None:
            unjudged.add(asset)
            continue
        judged.add(asset)
        judged_value = _X.add(judged_value, value)
        if actual is declared:
            matched = _X.add(matched, value)
    fit = money.exact_rate(money.ratio(matched, judged_value)) if judged_value > 0 else None
    return fit, len(judged), len(unjudged)


def _declared_at(lineup: tuple[LineupFact, ...], asset: str, turn: int) -> Position:
    found: Position | None = None
    count = 0
    for span in lineup:
        if (
            span.asset == asset
            and span.start_turn < turn
            and (span.end_turn is None or turn <= span.end_turn)
        ):
            found = span.declared
            count += 1
    if count != 1 or found is None:
        raise SimReportError("a held turn has no single open lineup span")
    return found


def _validate(src: ReportSources) -> None:
    season = src.season
    t = season.current_turn
    if t < 1 or t > season.path_length:
        raise SimReportError("the report turn is out of range")
    if len(src.turns) != t or any(turn.turn != i + 1 for i, turn in enumerate(src.turns)):
        raise SimReportError("turn snapshots do not run from 1 to the report turn")
    # Before any ratio over total value, which would fail with another error type.
    if any(turn.total_value == 0 for turn in src.turns):
        raise SimReportError("a turn has no value left")
    if src.orders.orders_after_report != 0:
        raise SimReportError("an order comes after the report turn")
    if season.status is SeasonStatus.FINISHED:
        pending_changes = sum(1 for c in src.changes if c.turn == t)
        if t != season.path_length or src.orders.pending_orders != 0 or pending_changes != 0:
            raise SimReportError("a finished season still holds orders or changes of its last turn")
    for key in (BENCH_KR, BENCH_US):
        prices = src.benchmark_prices.get(key)
        if prices is None or len(prices) != t + 1 or any(p is None for p in prices):
            raise SimReportError("a benchmark proxy asset is missing from the pool or its prices")
        if key not in src.pool_returns:
            raise SimReportError("a market proxy asset is missing from the pool")
    for returns in src.pool_returns.values():
        if len(returns) != t:
            raise SimReportError("path returns do not run from 1 to the report turn")
    for h in src.holdings:
        if h.turn < 1 or h.turn > t:
            raise SimReportError("a holding is outside the report range")
        if h.value < 0:
            raise SimReportError("a cumulative quantity is negative")


# ---------------------------------------------------------------------------- from a season


def sources_from_season(season: Season) -> ReportSources:
    """The report facts of a played season (what `SeasonReportQuery` reads from the database)."""
    if season.current_turn < 1:
        raise SimReportError("the report turn is out of range")
    t = season.current_turn
    s = season.settings
    start = s.start_formation
    facts = SeasonFacts(
        season.status,
        s.path_length,
        t,
        money.exact_won(s.initial_cash),
        s.risk_limit,
        s.fee_rate_kr,
        s.fee_rate_us,
        s.fx_spread_rate,
        start.goalkeeper,
        start.defender,
        start.midfielder,
        start.forward,
    )
    turns = tuple(
        TurnFact(x.turn, x.total, x.drawdown, x.goalkeeper, x.defender, x.midfielder, x.forward)
        for x in season.turns
    )
    changes = tuple(
        ChangeFact(
            i, c.turn, c.after.goalkeeper, c.after.defender, c.after.midfielder, c.after.forward
        )
        for i, c in enumerate(season.formation_changes)
    )

    trade = fee_total = team_fee = platform_fee = nudge = nudge_same = _ZERO
    pending = after = 0
    delta: dict[str, dict[int, int]] = defaultdict(lambda: defaultdict(int))
    cost_fee: dict[int, Decimal] = defaultdict(lambda: _ZERO)
    cost_orders: dict[int, int] = defaultdict(int)
    for e in season.executions:
        if e.turn == t:
            pending += 1
            continue
        if e.turn > t:
            after += 1
            continue
        kind = e.source.kind.value
        fee_total = _X.add(fee_total, e.fee)
        if e.turn == 0:
            team_fee = _X.add(team_fee, e.fee)
        else:
            trade = _X.add(trade, e.notional)
            if kind in PLATFORM_SOURCES:
                platform_fee = _X.add(platform_fee, e.fee)
            if kind in NUDGE_SOURCES:
                nudge = _X.add(nudge, e.notional)
                if kind == SourceKind.RANKING.value or e.source.feed_turn == e.turn:
                    nudge_same = _X.add(nudge_same, e.notional)
        delta[e.asset][e.turn] += e.quantity if e.side is Side.BUY else -e.quantity
    for e in season.executions:
        if e.formation_change is not None:
            cost_fee[e.formation_change] = _X.add(cost_fee[e.formation_change], e.fee)
            cost_orders[e.formation_change] += 1
    orders = OrderFacts(trade, fee_total, team_fee, platform_fee, nudge, nudge_same, pending, after)

    pool = {a.asset: a for a in season.inputs.pool}
    holdings: list[HoldingFact] = []
    for asset in sorted(delta):
        quantity = 0
        for turn in range(1, t + 1):
            quantity += delta[asset].get(turn - 1, 0)
            if quantity != 0:
                value = _X.multiply(season.turn_prices[turn][asset], Decimal(quantity))
                holdings.append(HoldingFact(asset, turn, value, pool[asset].system_position))
    lineup = tuple(
        LineupFact(sp.asset, sp.position, sp.start_turn, sp.end_turn) for sp in season.spans
    )

    pool_returns: dict[str, list[Decimal | None]] = {}
    for a in season.inputs.pool:
        krw = a.currency == KRW
        pool_returns[a.asset] = [
            measure.krw_return(krw, step.local_returns[a.asset], step.fx_change.get(a.currency))
            for step in season.inputs.path[:t]
        ]
    benchmark_prices = {
        key: [season.turn_prices[i][key] for i in range(t + 1)]
        for key in (BENCH_KR, BENCH_US)
        if key in pool
    }
    costs = tuple(
        ChangeCost(i, c.turn, cost_orders[i], cost_fee[i])
        for i, c in enumerate(season.formation_changes)
    )
    return ReportSources(
        facts,
        turns,
        changes,
        orders,
        tuple(holdings),
        lineup,
        pool_returns,
        benchmark_prices,
        costs,
        ReconFacts(0, t, 0, 0),
    )


def report_of(season: Season) -> SeasonReport:
    return calculate(sources_from_season(season))

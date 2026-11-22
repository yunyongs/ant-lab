"""Trading discipline and reaction to noise (Java `DisciplineCalculator` and noise twin).

Both judge orders of turns 1..T-1 only: turn 0 is team building. Amounts are whole won sums and
each share is one `money.ratio` division. Contradicting facts raise `SimReportError`.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal

from . import money
from .model import Side, SimReportError
from .rules import DROP_PERCENT, RISE_PERCENT

_X = money.EXACT
_ZERO = Decimal(0)
_HUNDRED = Decimal(100)


def dropped(previous: Decimal, current: Decimal) -> bool:
    """100 * (current - previous) <= -8 * previous; a non-positive previous never drops."""
    if previous <= 0:
        return False
    return _X.multiply(_HUNDRED, _X.subtract(current, previous)) <= _X.multiply(
        Decimal(-DROP_PERCENT), previous
    )


def rose(previous: Decimal, current: Decimal) -> bool:
    """100 * (current - previous) >= 8 * previous; a non-positive previous never rises."""
    if previous <= 0:
        return False
    return _X.multiply(_HUNDRED, _X.subtract(current, previous)) >= _X.multiply(
        Decimal(RISE_PERCENT), previous
    )


@dataclass(frozen=True)
class TurnValue:
    turn: int
    total_value: Decimal


@dataclass(frozen=True)
class DisciplineChange:
    id: int
    turn: int
    before_forward: int
    after_forward: int


@dataclass(frozen=True)
class DisciplineOrder:
    """One filled order; prices are the asset's turn prices now and one turn earlier (None at 0)."""

    turn: int
    side: Side
    asset: str
    notional: Decimal | None
    formation_change_id: int | None
    price: Decimal | None
    previous_price: Decimal | None


@dataclass(frozen=True)
class DisciplineFacts:
    report_turn: int
    initial_cash: Decimal
    turn_values: tuple[TurnValue, ...]
    changes: tuple[DisciplineChange, ...]
    orders: tuple[DisciplineOrder, ...]


@dataclass(frozen=True)
class DisciplineResult:
    sell_notional: Decimal
    panic_sell_notional: Decimal
    panic_sell_share: Decimal | None
    buy_notional: Decimal
    chase_buy_notional: Decimal
    chase_buy_share: Decimal | None
    trade_notional: Decimal
    undisciplined_share: Decimal | None
    formation_changes: int
    panic_formation_changes: int


def discipline(facts: DisciplineFacts) -> DisciplineResult:
    _validate_discipline(facts)
    report_turn = facts.report_turn
    team_value = {v.turn: v.total_value for v in facts.turn_values}
    changes = sorted((c for c in facts.changes if 1 <= c.turn < report_turn), key=lambda c: c.id)
    panic_turns = _panic_turns(facts.initial_cash, team_value, changes, report_turn)
    by_id = {c.id: c for c in changes}
    panic_changes = sum(1 for c in changes if c.turn in panic_turns)

    sell = panic_sell = buy = chase_buy = _ZERO
    for order in facts.orders:
        if order.turn < 1:
            continue
        _require_judgeable(order, by_id)
        notional = order.notional
        if notional is None or order.price is None or order.previous_price is None:
            raise SimReportError("an order has no notional or turn prices")
        if order.side is Side.SELL:
            sell = _X.add(sell, notional)
            bound = order.formation_change_id
            if dropped(order.previous_price, order.price) or (
                bound is not None and by_id[bound].turn in panic_turns
            ):
                panic_sell = _X.add(panic_sell, notional)
        else:
            buy = _X.add(buy, notional)
            if rose(order.previous_price, order.price):
                chase_buy = _X.add(chase_buy, notional)

    trade = _X.add(sell, buy)
    return DisciplineResult(
        money.exact_won(sell),
        money.exact_won(panic_sell),
        _share(panic_sell, sell),
        money.exact_won(buy),
        money.exact_won(chase_buy),
        _share(chase_buy, buy),
        money.exact_won(trade),
        _share(_X.add(panic_sell, chase_buy), trade),
        len(changes),
        panic_changes,
    )


def panic_turns_of(facts: DisciplineFacts) -> set[int]:
    """The turns `discipline` judges as panic; a sell bound to a change of such a turn is panic."""
    _validate_discipline(facts)
    team_value = {v.turn: v.total_value for v in facts.turn_values}
    changes = sorted(
        (c for c in facts.changes if 1 <= c.turn < facts.report_turn), key=lambda c: c.id
    )
    return _panic_turns(facts.initial_cash, team_value, changes, facts.report_turn)


def _panic_turns(
    initial_cash: Decimal,
    team_value: dict[int, Decimal],
    changes: Sequence[DisciplineChange],
    report_turn: int,
) -> set[int]:
    """Turns with a team drop and a lower end-of-turn forward target than at the start."""
    start_forward: dict[int, int] = {}
    end_forward: dict[int, int] = {}
    for change in changes:
        start_forward.setdefault(change.turn, change.before_forward)
        end_forward[change.turn] = change.after_forward
    panic: set[int] = set()
    for turn in range(1, report_turn):
        previous = initial_cash if turn == 1 else team_value[turn - 1]
        if (
            turn in start_forward
            and dropped(previous, team_value[turn])
            and end_forward[turn] < start_forward[turn]
        ):
            panic.add(turn)
    return panic


def _share(part: Decimal, whole: Decimal) -> Decimal | None:
    if whole == 0:
        return None
    return money.ratio(money.exact_won(part), money.exact_won(whole))


def _require_judgeable(order: DisciplineOrder, by_id: dict[int, DisciplineChange]) -> None:
    if (
        order.notional is None
        or order.notional <= 0
        or order.notional != order.notional.to_integral_value()
    ):
        raise SimReportError("an order amount is not positive whole won")
    if (
        order.price is None
        or order.previous_price is None
        or order.price <= 0
        or order.previous_price <= 0
    ):
        raise SimReportError("an order asset has no positive turn price")
    if order.formation_change_id is not None:
        change = by_id.get(order.formation_change_id)
        if change is None or change.turn != order.turn:
            raise SimReportError("an order is bound to a missing or other-turn formation change")


def _validate_discipline(facts: DisciplineFacts) -> None:
    report_turn = facts.report_turn
    if report_turn < 1:
        raise SimReportError("the report turn is out of range")
    if facts.initial_cash is None or facts.initial_cash <= 0:
        raise SimReportError("the initial cash is not positive")
    values = facts.turn_values
    if len(values) != report_turn:
        raise SimReportError("turn snapshots do not run from 1 to the report turn")
    for i, value in enumerate(values):
        if value.turn != i + 1 or value.total_value is None or value.total_value < 0:
            raise SimReportError("turn snapshots do not run from 1 to the report turn")
    for order in facts.orders:
        if order.turn < 0 or order.turn >= report_turn:
            raise SimReportError("an order is outside the report range")


# ---------------------------------------------------------------------------- noise


@dataclass(frozen=True)
class TurnSignals:
    """Assets the player was pointed at in one turn, by `MARKET:TICKER`."""

    turn: int
    up: frozenset[str]
    down: frozenset[str]
    top_attention: frozenset[str] = frozenset()

    def __post_init__(self) -> None:
        if self.turn < 1:
            raise SimReportError("turn signals start at turn 1")


@dataclass(frozen=True)
class NoiseOrder:
    turn: int
    side: Side
    asset: str
    notional: Decimal


@dataclass(frozen=True)
class NoiseResult:
    noise_share: Decimal | None
    trading_turns: int
    followed_buy_notional: Decimal
    followed_sell_notional: Decimal
    trade_notional: Decimal


def noise_of_season(
    report_turn: int, signals: Sequence[TurnSignals], all_orders: Sequence[NoiseOrder]
) -> NoiseResult:
    """Drops the team purchase (turn 0) before judging."""
    return noise(report_turn, signals, [o for o in all_orders if o.turn >= 1])


def noise(
    report_turn: int, signals: Sequence[TurnSignals], orders: Sequence[NoiseOrder]
) -> NoiseResult:
    """A buy follows the noise when the asset is in the turn's `up` set, a sell when in `down`."""
    if report_turn < 1:
        raise SimReportError("the report turn is out of range")
    by_turn: dict[int, TurnSignals] = {}
    for s in signals:
        if s.turn >= report_turn or s.turn in by_turn:
            raise SimReportError("turn signals are outside the report range or repeat")
        by_turn[s.turn] = s
    traded: dict[int, Decimal] = {}
    followed_by_turn: dict[int, Decimal] = {}
    followed_buy = followed_sell = trade = _ZERO
    for order in orders:
        if order.turn < 1 or order.turn >= report_turn:
            raise SimReportError("an order is outside the report range")
        if order.notional <= 0 or order.notional != order.notional.to_integral_value():
            raise SimReportError("an order amount is not positive whole won")
        turn_signals = by_turn.get(order.turn)
        if turn_signals is None:
            raise SimReportError("an order turn has no signals")
        pointed = turn_signals.up if order.side is Side.BUY else turn_signals.down
        trade = _X.add(trade, order.notional)
        traded[order.turn] = _X.add(traded.get(order.turn, _ZERO), order.notional)
        if order.asset in pointed:
            followed_by_turn[order.turn] = _X.add(
                followed_by_turn.get(order.turn, _ZERO), order.notional
            )
            if order.side is Side.BUY:
                followed_buy = _X.add(followed_buy, order.notional)
            else:
                followed_sell = _X.add(followed_sell, order.notional)
    numerators: list[int] = []
    denominators: list[int] = []
    for turn in range(1, report_turn):
        if turn in traded:
            numerators.append(int(money.exact_won(followed_by_turn.get(turn, _ZERO))))
            denominators.append(int(money.exact_won(traded[turn])))
    share = money.fraction_mean(numerators, denominators) if numerators else None
    return NoiseResult(
        share,
        len(numerators),
        money.exact_won(followed_buy),
        money.exact_won(followed_sell),
        money.exact_won(trade),
    )

"""`bot-redeclare/1`: the random bot of the same index, plus a formation change after its orders.

Probe for the formation exploit (PLAN section 4.10): every turn from turn 1 it declares the
multiple-of-5 formation nearest to the weights it holds after its orders of the turn (projected at
the turn's prices, with fees), by system position (a position-less asset counts as a midfielder, as
the random bot declares it; goalkeeper is the residual of cash and goalkeeper-position assets), so
its formation item can only look good. It never trades for it.

Nearest = smallest sum of squared distances to the weights in percent, over the formations with
shares in steps of 5 summing to 100 and a goalkeeper of at least 5. Ties go to the first formation
in (goalkeeper, defender, midfielder, forward) order. The change is skipped when it equals the
formation in force at the start of the turn; at most one change is made per turn. The random bot's
own formation changes are never emitted, so the trades equal those of the random bot of the same
index. That bot is shown the formation it would have without the redeclarations (its own redraw
loop reads it), which keeps every draw of the pair aligned.
"""

from dataclasses import replace
from functools import cache

from antlab_engine.sim import money
from antlab_engine.sim.decisions import FormationUnit, OrderUnit, Unit
from antlab_engine.sim.model import Formation, Position, Side

from .random_bot import FORMATION_STEP, RandomBot, declared_position
from .view import BotView

VERSION = 1
STEPS = 100 // FORMATION_STEP


@cache
def _formations() -> tuple[tuple[int, int, int, int], ...]:
    out = []
    for goalkeeper in range(1, STEPS + 1):
        for defender in range(STEPS - goalkeeper + 1):
            for midfielder in range(STEPS - goalkeeper - defender + 1):
                forward = STEPS - goalkeeper - defender - midfielder
                out.append(
                    tuple(FORMATION_STEP * s for s in (goalkeeper, defender, midfielder, forward))
                )
    return tuple(out)


def snapshot_values(view: BotView, units: tuple[Unit, ...] = ()) -> tuple[int, int, int, int, int]:
    """Won held after `units` as (defender, midfielder, forward), the total, and the residual.

    The orders are projected at the turn's prices with the same fee rule as the simulator, so the
    snapshot is what the next turn's report sees; nothing after the turn is read.
    """
    held = {a.asset: view.holdings.get(a.asset, 0) for a in view.pool}
    cash = int(view.cash)
    for unit in units:
        if not isinstance(unit, OrderUnit):
            continue
        order = unit.order
        price = view.prices[order.asset]
        worth = int(money.valuation(price, order.quantity))
        fee = int(money.fee(price, order.quantity, view.rates[order.asset]))
        if order.side is Side.BUY:
            held[order.asset] += order.quantity
            cash -= worth + fee
        else:
            held[order.asset] -= order.quantity
            cash += worth - fee
    values = [0, 0, 0]
    total = cash
    for asset in view.pool:
        quantity = held[asset.asset]
        if not quantity:
            continue
        worth = int(money.valuation(view.prices[asset.asset], quantity))
        total += worth
        position = declared_position(asset)
        if position is Position.GOALKEEPER:
            continue
        slot = 0 if position is Position.DEFENDER else 1 if position is Position.MIDFIELDER else 2
        values[slot] += worth
    return values[0], values[1], values[2], total, total - sum(values)


def nearest_formation(view: BotView, units: tuple[Unit, ...] = ()) -> tuple[int, int, int, int]:
    defender, midfielder, forward, total, goalkeeper = snapshot_values(view, units)
    if total <= 0:
        raise ValueError("no value to declare a formation for")
    weights = (goalkeeper, defender, midfielder, forward)
    # min() keeps the first of equal costs, which is the tie rule
    return min(
        _formations(),
        key=lambda shares: sum(
            (total * s - 100 * w) ** 2 for s, w in zip(shares, weights, strict=True)
        ),
    )


class RedeclareBot:
    kind = "redeclare"

    def __init__(self, index: int, seed: int) -> None:
        """`seed` is the random bot's seed of the same index, so both make the same orders."""
        self.index = index
        self.seed = seed
        self.version = VERSION
        self.params: dict[str, object] = {"step": FORMATION_STEP}
        self._random = RandomBot(index, seed)
        self._shadow: Formation | None = None

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        if self._shadow is None:
            self._shadow = view.formation
        units = self._random.decide(replace(view, formation=self._shadow))
        trades: list[Unit] = []
        for unit in units:
            if isinstance(unit, FormationUnit):
                self._shadow = Formation(
                    unit.goalkeeper, unit.defender, unit.midfielder, unit.forward
                )
            else:
                trades.append(unit)
        if view.turn == 0:
            return tuple(trades)
        target = nearest_formation(view, tuple(trades))
        current = view.formation
        if target == (current.goalkeeper, current.defender, current.midfielder, current.forward):
            return tuple(trades)
        return (*trades, FormationUnit(*target))

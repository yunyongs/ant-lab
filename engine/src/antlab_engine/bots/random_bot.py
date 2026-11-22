"""`bot-random/1`: the null distribution for the process score (PLAN section 6.9).

Assumes the world sets no single-asset limit (`run_bot` refuses such worlds).

A start team of 6-10 random assets with random integer weights, then a coin-flip trade each turn.
All draws are integers from `BotRng`; the order of draws is part of the rule and is fixed here.
"""

from decimal import Decimal

from antlab_engine.sim import money
from antlab_engine.sim.decisions import FormationUnit, OrderUnit, Unit
from antlab_engine.sim.model import Order, OrderSource, Position, Side

from .rng import BotRng
from .view import BotView, ViewAsset

VERSION = 1
TEAM_MIN = 6
TEAM_MAX = 10
INVEST_PERCENT = 90
ORDER_MAX = 3
BUY_PERCENT_MIN = 2
BUY_PERCENT_MAX = 10
SELL_FRACTIONS = ((1, 4), (1, 2), (1, 1))
FORMATION_STEP = 5
GOALKEEPER_MAX = 40


def declared_position(asset: ViewAsset) -> Position:
    return asset.system_position or Position.MIDFIELDER


def buy_unit(
    view: BotView, asset: ViewAsset, budget: int, source: OrderSource | None = None
) -> tuple[OrderUnit, Decimal] | None:
    """The largest buy that fits `budget` (notional plus fee); None when not even one unit fits."""
    price = view.prices[asset.asset]
    rate = view.rates[asset.asset]
    quantity = money.team_qty(Decimal(budget), price, rate)
    if quantity < 1:
        return None
    cost = money.valuation(price, quantity) + money.fee(price, quantity, rate)
    order = Order(
        asset.asset, Side.BUY, quantity, declared_position(asset), source or OrderSource()
    )
    return OrderUnit(order), cost


def sell_unit(
    view: BotView, asset: str, quantity: int, source: OrderSource | None = None
) -> tuple[OrderUnit, Decimal]:
    price = view.prices[asset]
    proceeds = money.valuation(price, quantity) - money.fee(price, quantity, view.rates[asset])
    return OrderUnit(Order(asset, Side.SELL, quantity, None, source or OrderSource())), proceeds


def build_team(view: BotView, rng: BotRng) -> tuple[Unit, ...]:
    pool = view.pool
    size = min(rng.between(TEAM_MIN, TEAM_MAX), len(pool))
    order = list(range(len(pool)))
    for i in range(size):
        j = i + rng.below(len(order) - i)
        order[i], order[j] = order[j], order[i]
    chosen = order[:size]
    weights = [rng.between(1, 10) for _ in chosen]
    invest = int(view.cash) * INVEST_PERCENT // 100
    total_weight = sum(weights)
    units: list[Unit] = []
    for index, weight in zip(chosen, weights, strict=True):
        built = buy_unit(view, pool[index], invest * weight // total_weight)
        if built is not None:
            units.append(built[0])
    return tuple(units)


class RandomBot:
    kind = "random"

    def __init__(self, index: int, seed: int) -> None:
        self.index = index
        self.seed = seed
        self.version = VERSION
        self.params: dict[str, object] = {}
        self._rng = BotRng(seed)

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        if view.turn == 0:
            return build_team(view, self._rng)
        rng = self._rng
        sells: list[Unit] = []
        buys: list[tuple[ViewAsset, int]] = []
        proceeds = Decimal(0)
        if rng.chance(1, 2):
            held = {
                a.asset: view.holdings[a.asset] for a in view.pool if view.holdings.get(a.asset)
            }
            for _ in range(rng.between(1, ORDER_MAX)):
                if held and rng.chance(1, 2):
                    names = list(held)
                    asset = names[rng.below(len(names))]
                    num, den = SELL_FRACTIONS[rng.below(len(SELL_FRACTIONS))]
                    quantity = max(1, held[asset] * num // den)
                    unit, cash_in = sell_unit(view, asset, quantity)
                    sells.append(unit)
                    proceeds += cash_in
                    held[asset] -= quantity
                    if not held[asset]:
                        del held[asset]
                else:
                    asset_view = view.pool[rng.below(len(view.pool))]
                    percent = rng.between(BUY_PERCENT_MIN, BUY_PERCENT_MAX)
                    buys.append((asset_view, int(view.total or 0) * percent // 100))
        units = sells
        cash = int(view.cash + proceeds)
        for asset_view, budget in buys:
            built = buy_unit(view, asset_view, min(budget, cash))
            if built is not None:
                units.append(built[0])
                cash -= int(built[1])
        if rng.chance(1, 10):
            units.append(self._formation(view))
        return tuple(units)

    def _formation(self, view: BotView) -> FormationUnit:
        rng = self._rng
        current = view.formation
        while True:
            goalkeeper = FORMATION_STEP * rng.between(1, GOALKEEPER_MAX // FORMATION_STEP)
            steps = (100 - goalkeeper) // FORMATION_STEP
            # uniform over the compositions of `steps` into three parts
            while True:
                defender = rng.below(steps + 1)
                midfielder = rng.below(steps + 1)
                if defender + midfielder <= steps:
                    break
            forward = steps - defender - midfielder
            shares = (
                goalkeeper,
                defender * FORMATION_STEP,
                midfielder * FORMATION_STEP,
                forward * FORMATION_STEP,
            )
            if shares != (
                current.goalkeeper,
                current.defender,
                current.midfielder,
                current.forward,
            ):
                return FormationUnit(*shares)

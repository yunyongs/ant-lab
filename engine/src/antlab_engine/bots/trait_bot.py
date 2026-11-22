"""`bot-trait/1`: six independent behavioural traits, the probe of the process-item overlap (M3-06).

Assumes the world sets no single-asset limit (`run_bot` refuses such worlds). Every trait is a
level 0..4 (chance level/4 per turn) drawn from its own sub-stream, so the levels and the per-turn
chances are independent and setting one level to 0 never changes what another trait does at the
same state (later draws of `follow`, `activity` and `route` can still shift with holdings and the
order count). The start
team is the random bot's team of the same index. From turn 1 the traits act in a fixed order:
panic (sell the holdings that fell 8 percent or more), follow (one buy or sell the feed or the
boards point at), chase (buy one asset that rose 8 percent or more), event (react to the sign of
an EVENT row's stated reaction), activity (one random rebalance trade). `route` then decides per
order whether to cite a feed row or a board that the view really shows. The `event` trait buys
the pool's `KR:069500` or `US:SPY`; a pool without them gets no buy.
"""

from collections.abc import Mapping
from dataclasses import replace
from decimal import Decimal

from antlab_engine.sim.behaviour import dropped, rose
from antlab_engine.sim.decisions import OrderUnit, Unit
from antlab_engine.sim.model import OrderSource, SourceKind

from .random_bot import (
    BUY_PERCENT_MAX,
    BUY_PERCENT_MIN,
    SELL_FRACTIONS,
    build_team,
    buy_unit,
    sell_unit,
)
from .rng import BotRng, mix
from .view import BotView, ViewAsset

VERSION = 1
TRAITS = ("follow", "route", "panic", "chase", "activity", "event")
LEVELS = 4
INDEX_ASSETS = ("KR:069500", "US:SPY")
_TAGS = {name: i + 1 for i, name in enumerate(TRAITS)}


def level_text(level: int) -> str:
    return str(Decimal(level) / LEVELS)


def _rows(view: BotView, kinds: tuple[str, ...], direction: str) -> list[str]:
    """Assets named by a row of one of `kinds` and `direction`, in seq order, once each."""
    found: list[str] = []
    for item in sorted(view.feed, key=lambda i: i.seq):
        if (
            item.asset is not None
            and item.kind in kinds
            and item.direction == direction
            and item.asset not in found
        ):
            found.append(item.asset)
    return found


class _Turn:
    """The running state of one turn: units so far, holdings still sellable and cash left."""

    def __init__(self, view: BotView) -> None:
        self.view = view
        self.held = {
            a.asset: view.holdings[a.asset] for a in view.pool if view.holdings.get(a.asset)
        }
        self.cash = view.cash
        self.units: list[Unit] = []

    def sell(self, asset: str, quantity: int) -> None:
        unit, cash_in = sell_unit(self.view, asset, quantity)
        self.units.append(unit)
        self.cash += cash_in
        self.held[asset] -= quantity
        if not self.held[asset]:
            del self.held[asset]

    def buy(self, asset: ViewAsset, rng: BotRng) -> None:
        percent = rng.between(BUY_PERCENT_MIN, BUY_PERCENT_MAX)
        budget = int(self.view.total or 0) * percent // 100
        built = buy_unit(self.view, asset, int(min(Decimal(budget), self.cash)))
        if built is not None:
            self.units.append(built[0])
            self.cash -= built[1]


class TraitBot:
    kind = "trait"

    def __init__(
        self, index: int, seed: int, team_seed: int, levels: Mapping[str, int] | None = None
    ) -> None:
        """`levels` overrides the drawn levels (tests); the level draw is consumed either way."""
        self.index = index
        self.seed = seed
        self.version = VERSION
        self._team = BotRng(team_seed)
        self._rng = {t: BotRng(mix(seed, _TAGS[t])) for t in TRAITS}
        drawn = {t: self._rng[t].below(LEVELS + 1) for t in TRAITS}
        if levels is not None:
            unknown = set(levels) - set(TRAITS)
            if unknown or any(not 0 <= v <= LEVELS for v in levels.values()):
                raise ValueError("trait levels are 0..4 for the six known traits")
            drawn.update(levels)
        self.levels = drawn
        self.params: dict[str, object] = {t: level_text(drawn[t]) for t in TRAITS}

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        if view.turn == 0:
            return build_team(view, self._team)
        turn = _Turn(view)
        self._panic(turn)
        self._follow(turn)
        self._chase(turn)
        self._event(turn)
        self._activity(turn)
        return self._route(view, turn.units)

    def _acts(self, trait: str) -> bool:
        return self._rng[trait].chance(self.levels[trait], LEVELS)

    def _panic(self, turn: _Turn) -> None:
        view = turn.view
        if not self._acts("panic") or view.prev_prices is None:
            return
        for asset in view.pool:
            quantity = turn.held.get(asset.asset, 0)
            if quantity and dropped(view.prev_prices[asset.asset], view.prices[asset.asset]):
                turn.sell(asset.asset, quantity)

    def _follow(self, turn: _Turn) -> None:
        view = turn.view
        rng = self._rng["follow"]
        acts = self._acts("follow")
        prefer_sell = rng.chance(1, 2)
        names = {a.asset for a in view.pool}
        buys: list[str] = []
        for name in (*_rows(view, ("RISE", "ECHO"), "UP"), *view.gainers, *view.attention):
            if name in names and name not in buys:
                buys.append(name)
        falls = _rows(view, ("FALL", "ECHO"), "DOWN")
        sells = [
            a.asset
            for a in view.pool
            if a.asset in turn.held and (a.asset in falls or a.asset in view.losers)
        ]
        if not acts:
            return
        by_name = {a.asset: a for a in view.pool}
        if sells and (prefer_sell or not buys):
            asset = sells[rng.below(len(sells))]
            turn.sell(asset, turn.held[asset])
        elif buys:
            turn.buy(by_name[buys[rng.below(len(buys))]], rng)

    def _chase(self, turn: _Turn) -> None:
        view = turn.view
        rng = self._rng["chase"]
        if not self._acts("chase") or view.prev_prices is None:
            return
        risers = [a for a in view.pool if rose(view.prev_prices[a.asset], view.prices[a.asset])]
        if risers:
            turn.buy(risers[rng.below(len(risers))], rng)

    def _event(self, turn: _Turn) -> None:
        view = turn.view
        rng = self._rng["event"]
        acts = self._acts("event")
        reactions = [i.reaction for i in view.feed if i.kind == "EVENT" and i.reaction is not None]
        if not acts or not reactions:
            return
        net = sum(reactions, Decimal(0))
        if net < 0:
            forward = [
                a
                for a in view.pool
                if a.asset in turn.held
                and a.system_position is not None
                and a.system_position.is_forward
            ]
            if forward:
                top = max(forward, key=lambda a: turn.held[a.asset] * view.prices[a.asset])
                turn.sell(top.asset, turn.held[top.asset])
        elif net > 0:
            pair = [a for a in view.pool if a.asset in INDEX_ASSETS]
            if pair:
                turn.buy(pair[rng.below(len(pair))], rng)

    def _activity(self, turn: _Turn) -> None:
        view = turn.view
        rng = self._rng["activity"]
        if not self._acts("activity"):
            return
        sell_coin = rng.chance(1, 2)
        if turn.held and sell_coin:
            names = list(turn.held)
            asset = names[rng.below(len(names))]
            num, den = SELL_FRACTIONS[rng.below(len(SELL_FRACTIONS))]
            turn.sell(asset, max(1, turn.held[asset] * num // den))
        else:
            turn.buy(view.pool[rng.below(len(view.pool))], rng)

    def _route(self, view: BotView, units: list[Unit]) -> tuple[Unit, ...]:
        row_of: dict[str, int] = {}
        for item in sorted(view.feed, key=lambda i: i.seq):
            if item.asset is not None:
                row_of.setdefault(item.asset, item.seq)
        ranked = {*view.gainers, *view.losers, *view.attention}
        routed: list[Unit] = []
        for unit in units:
            if not isinstance(unit, OrderUnit):
                routed.append(unit)
                continue
            cites = self._acts("route")
            asset = unit.order.asset
            if cites and asset in row_of:
                source = OrderSource(SourceKind.FEED, view.turn, row_of[asset])
            elif cites and asset in ranked:
                source = OrderSource(SourceKind.RANKING)
            else:
                source = OrderSource()
            routed.append(replace(unit, order=replace(unit.order, source=source)))
        return tuple(routed)

"""`bot-disciplined/1`: holds the formation and rebalances only when a slot drifts past the band.

Assumes the world sets no single-asset limit (`run_bot` refuses such worlds). Slots are the
three non-goalkeeper positions; the goalkeeper slot is the cash residual. The guard never sells
an asset that fell 8 percent or more in the turn just shown and never buys one that rose 8
percent or more; guarded orders wait for the next band check.
"""

from antlab_engine.sim.behaviour import dropped, rose
from antlab_engine.sim.decisions import Unit
from antlab_engine.sim.model import Formation, Position

from .random_bot import buy_unit, sell_unit
from .rng import BotRng
from .view import BotView, ViewAsset

VERSION = 1
BAND_PERCENT = 5
ASSETS_PER_SLOT = 2
FALLBACK_PAIR = ("KR:069500", "US:SPY")
SLOTS = (Position.DEFENDER, Position.MIDFIELDER, Position.FORWARD_TARGET)


def slot_of(asset: ViewAsset) -> Position | None:
    position = asset.system_position
    if position is None or position is Position.GOALKEEPER:
        return None
    return Position.FORWARD_TARGET if position.is_forward else position


def share_of(formation: Formation, slot: Position) -> int:
    if slot is Position.DEFENDER:
        return formation.defender
    if slot is Position.MIDFIELDER:
        return formation.midfielder
    return formation.forward


def build_slots(view: BotView, rng: BotRng) -> dict[Position, tuple[ViewAsset, ...]]:
    """Two assets per slot by a seeded partial shuffle; the market pair fills an empty slot."""
    candidates = {s: [a for a in view.pool if slot_of(a) is s] for s in SLOTS}
    chosen: dict[Position, tuple[ViewAsset, ...]] = {}
    for slot in SLOTS:
        pool = candidates[slot]
        take = min(ASSETS_PER_SLOT, len(pool))
        for i in range(take):
            j = i + rng.below(len(pool) - i)
            pool[i], pool[j] = pool[j], pool[i]
        chosen[slot] = tuple(pool[:take])
    used = {a.asset for assets in chosen.values() for a in assets}
    by_name = {a.asset: a for a in view.pool}
    for slot in SLOTS:
        if chosen[slot]:
            continue
        pair = tuple(
            by_name[name] for name in FALLBACK_PAIR if name in by_name and name not in used
        )
        used.update(a.asset for a in pair)
        chosen[slot] = pair
    return chosen


def build_team(view: BotView, slots: dict[Position, tuple[ViewAsset, ...]]) -> tuple[Unit, ...]:
    units: list[Unit] = []
    cash = int(view.cash)
    for slot in SLOTS:
        assets = slots[slot]
        if not assets:
            continue
        budget = cash * share_of(view.formation, slot) // 100 // len(assets)
        for asset in assets:
            built = buy_unit(view, asset, budget)
            if built is not None:
                units.append(built[0])
    return tuple(units)


def _slot_value(view: BotView, assets: tuple[ViewAsset, ...]) -> int:
    return sum(view.holdings.get(a.asset, 0) * int(view.prices[a.asset]) for a in assets)


class DisciplinedBot:
    kind = "disciplined"

    def __init__(self, index: int, seed: int, guard: bool = True) -> None:
        self.index = index
        self.seed = seed
        self.version = VERSION
        self.guard = guard
        self.params: dict[str, object] = {"bandPercent": BAND_PERCENT, "guard": guard}
        self._rng = BotRng(seed)
        self._slots: dict[Position, tuple[ViewAsset, ...]] = {}

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        if view.turn == 0:
            self._slots = build_slots(view, self._rng)
            return build_team(view, self._slots)
        return self._rebalance(view)

    def _rebalance(self, view: BotView) -> tuple[Unit, ...]:
        values = {slot: _slot_value(view, assets) for slot, assets in self._slots.items()}
        cash = int(view.cash)
        total = cash + sum(values.values())
        if total <= 0:
            return ()
        formation = view.formation
        shares = {slot: share_of(formation, slot) for slot in SLOTS}
        drift = [(cash, formation.goalkeeper)] + [(values[s], shares[s]) for s in SLOTS]
        if not any(abs(100 * v - f * total) > BAND_PERCENT * total for v, f in drift):
            return ()
        sells: list[Unit] = []
        proceeds = 0
        for slot in SLOTS:
            excess = values[slot] - total * shares[slot] // 100
            if excess <= 0 or not values[slot]:
                continue
            for asset in self._slots[slot]:
                held = view.holdings.get(asset.asset, 0)
                if not held or self._falling(view, asset):
                    continue
                worth = held * int(view.prices[asset.asset])
                quantity = min(
                    held, excess * worth // values[slot] // int(view.prices[asset.asset])
                )
                if quantity < 1:
                    continue
                unit, cash_in = sell_unit(view, asset.asset, quantity)
                sells.append(unit)
                proceeds += int(cash_in)
        available = cash + proceeds
        units = sells
        for slot in SLOTS:
            assets = self._slots[slot]
            deficit = total * shares[slot] // 100 - values[slot]
            if deficit <= 0 or not assets:
                continue
            for asset in assets:
                if self._rising(view, asset):
                    continue
                built = buy_unit(view, asset, min(deficit // len(assets), available))
                if built is not None:
                    units.append(built[0])
                    available -= int(built[1])
        return tuple(units)

    def _falling(self, view: BotView, asset: ViewAsset) -> bool:
        return self.guard and self._moved(view, asset, dropped)

    def _rising(self, view: BotView, asset: ViewAsset) -> bool:
        return self.guard and self._moved(view, asset, rose)

    @staticmethod
    def _moved(view: BotView, asset: ViewAsset, test) -> bool:
        previous = view.prev_prices
        return previous is not None and test(previous[asset.asset], view.prices[asset.asset])

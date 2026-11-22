"""`bot-news/1`: starts from the disciplined team, then trades what the feed and the board point at.

Assumes the world sets no single-asset limit (`run_bot` refuses such worlds). Each turn it sells
all of every held asset that is in the turn's down set (a FALL or ECHO-down row naming it, or the
losers board) and buys up to three assets: RISE or ECHO-up rows by seq, then gainers by rank,
then the attention board by rank. An order carries `FEED(turn, seq)` of the row that named the
asset in that direction, or `RANKING` when only a board did. The team build uses the same draws
as the disciplined bot of the same index.
"""

from antlab_engine.sim.decisions import Unit
from antlab_engine.sim.model import OrderSource, SourceKind

from .disciplined_bot import build_slots, build_team
from .random_bot import buy_unit, sell_unit
from .rng import BotRng
from .view import BotView, ViewAsset

VERSION = 1
BUYS_PER_TURN = 3


def _first_rows(view: BotView, kinds: tuple[str, ...], direction: str) -> dict[str, int]:
    """asset -> seq of the first row of one of `kinds` and `direction` that names it."""
    first: dict[str, int] = {}
    for item in sorted(view.feed, key=lambda i: i.seq):
        if item.asset is not None and item.kind in kinds and item.direction == direction:
            first.setdefault(item.asset, item.seq)
    return first


def _source(view: BotView, seq: int | None) -> OrderSource:
    if seq is None:
        return OrderSource(SourceKind.RANKING)
    return OrderSource(SourceKind.FEED, view.turn, seq)


class NewsBot:
    kind = "news"

    def __init__(self, index: int, seed: int) -> None:
        self.index = index
        self.seed = seed
        self.version = VERSION
        self.params: dict[str, object] = {"buysPerTurn": BUYS_PER_TURN}
        self._rng = BotRng(seed)

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        if view.turn == 0:
            return build_team(view, build_slots(view, self._rng))
        falls = _first_rows(view, ("FALL", "ECHO"), "DOWN")
        rises = _first_rows(view, ("RISE", "ECHO"), "UP")
        units: list[Unit] = []
        sold: set[str] = set()
        cash = int(view.cash)
        for asset in view.pool:
            held = view.holdings.get(asset.asset, 0)
            seq = falls.get(asset.asset)
            if not held or (seq is None and asset.asset not in view.losers):
                continue
            unit, cash_in = sell_unit(view, asset.asset, held, _source(view, seq))
            units.append(unit)
            sold.add(asset.asset)
            cash += int(cash_in)
        by_name = {a.asset: a for a in view.pool}
        targets: list[tuple[ViewAsset, int | None]] = []
        seen = set(sold)

        def add(name: str, seq: int | None) -> None:
            if name in seen or name not in by_name or len(targets) >= BUYS_PER_TURN:
                return
            seen.add(name)
            targets.append((by_name[name], seq))

        for name, seq in sorted(rises.items(), key=lambda kv: kv[1]):
            add(name, seq)
        for name in view.gainers:
            add(name, None)
        for name in view.attention:
            add(name, None)
        for i, (asset, seq) in enumerate(targets):
            built = buy_unit(view, asset, cash // (len(targets) - i), _source(view, seq))
            if built is not None:
                units.append(built[0])
                cash -= int(built[1])
        return tuple(units)

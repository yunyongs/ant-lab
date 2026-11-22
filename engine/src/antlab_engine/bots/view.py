"""What a bot may see at a decision turn: facts as of that turn and nothing later.

The view has no path, block, month or date field. Prices are the turn's own and the previous
turn's; the feed (when a source is plugged in) is the turn's rows built from price history up to
the turn. A feed source gets the turn and the prices so far, so it cannot see ahead either.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Protocol

from antlab_engine.sim.behaviour import TurnSignals
from antlab_engine.sim.model import Formation, Position


@dataclass(frozen=True)
class ViewAsset:
    asset: str
    market: str
    system_position: Position | None


@dataclass(frozen=True)
class FeedItem:
    seq: int
    kind: str
    asset: str | None
    direction: str | None


@dataclass(frozen=True)
class TurnFeed:
    items: tuple[FeedItem, ...] = ()
    signals: TurnSignals | None = None


class FeedSource(Protocol):
    def at(self, turn: int, prices: Sequence[Mapping[str, Decimal]]) -> TurnFeed:
        """Feed rows and signals of `turn`; `prices` holds turns 0..turn, in won."""
        ...


@dataclass(frozen=True)
class BotView:
    turn: int
    season_turns: int
    cash: Decimal
    total: Decimal | None
    holdings: Mapping[str, int]
    prices: Mapping[str, Decimal]
    prev_prices: Mapping[str, Decimal] | None
    formation: Formation
    pool: tuple[ViewAsset, ...]
    rates: Mapping[str, Decimal]
    feed: tuple[FeedItem, ...] = ()
    signals: TurnSignals | None = None

"""As-of-turn facts the generator and the board may see, and their assembly from prices.

Nothing here has a field of a block, month, date or path type. `build_asset_facts` mirrors the
asset part of Java `FeedInputReader.readAssets`; `hidden_event_types` is the replay-range rule
(N1) that the reader applies in SQL.
"""

import decimal
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Context, Decimal

from .. import money
from .feedhash import byte_key
from .rules import (
    FAMILY_PREFIX,
    HIDDEN_FAMILY_LIMIT,
    Failure,
    FeedGenerationError,
)

_EXACT = Context(prec=200, Emax=decimal.MAX_EMAX, Emin=decimal.MIN_EMIN, traps=[decimal.Inexact])

UP = "UP"
DOWN = "DOWN"


def _input_error(reason: str) -> FeedGenerationError:
    return FeedGenerationError(Failure.FEED_INPUT, reason)


@dataclass(frozen=True, slots=True)
class AssetFacts:
    as_of_turn: int
    asset_id: int
    market: str
    ticker: str
    name: str
    group_id: int
    group_code: str
    group_name: str
    ret: Decimal
    prev_ret: Decimal | None
    streak: int
    attention: Decimal | None
    attention_rank: int | None
    attention_n: int
    prev_attention: Decimal | None
    prev_attention_rank: int | None
    prev_story_direction: str | None
    prev_echo_repeat: int
    focus_name: str | None

    def __post_init__(self) -> None:
        for value in (self.ret, self.prev_ret):
            if value is not None and _scale(value) != 8:
                raise _input_error("a return must have scale 8")
        if self.streak < 0 or self.attention_n < 0 or self.prev_echo_repeat < 1:
            raise _input_error("asset counts out of range")

    @property
    def ref(self) -> str:
        return f"{self.market}:{self.ticker}"


@dataclass(frozen=True, slots=True)
class EventFacts:
    as_of_turn: int
    month_event_id: int
    event_key: str
    category: str | None
    reaction: Decimal | None
    strength: int | None
    hidden: bool

    def __post_init__(self) -> None:
        if self.reaction is None:
            raise FeedGenerationError(Failure.FEED_REACTION_MISSING, "an event tag has no reaction")
        if self.hidden and self.category is not None:
            raise _input_error("a hidden event carries its category")
        if not self.hidden and self.category is None:
            raise _input_error("a public event has no category")


@dataclass(frozen=True, slots=True)
class FeedInput:
    seed: int
    turn_no: int
    assets: tuple[AssetFacts, ...]
    events: tuple[EventFacts, ...]

    def __post_init__(self) -> None:
        if self.turn_no < 1:
            raise _input_error("turn or fact list is missing")
        object.__setattr__(self, "assets", tuple(self.assets))
        object.__setattr__(self, "events", tuple(self.events))
        refs: set[str] = set()
        for a in self.assets:
            if a.as_of_turn != self.turn_no:
                raise _input_error("an asset fact is not as of the current turn")
            if a.ref in refs:
                raise _input_error("duplicate asset")
            refs.add(a.ref)
        keys: set[str] = set()
        for e in self.events:
            if e.as_of_turn != self.turn_no:
                raise _input_error("an event fact is not as of the current turn")
            if e.event_key in keys:
                raise _input_error("duplicate event")
            keys.add(e.event_key)


def _scale(value: Decimal) -> int:
    exponent = value.as_tuple().exponent
    if not isinstance(exponent, int):
        raise _input_error("not a finite number")
    return -exponent


@dataclass(frozen=True, slots=True)
class PoolAsset:
    asset_id: int
    market: str
    ticker: str
    name: str
    group_id: int
    group_code: str
    group_name: str


@dataclass(frozen=True, slots=True)
class PreviousRow:
    """A row of the previous turn's feed that names an asset, as stored."""

    asset_id: int
    kind: str
    direction: str | None
    repeat_count: int


@dataclass(frozen=True, slots=True)
class Story:
    direction: str | None
    echo_repeat: int


def change(prices: Sequence[Decimal], turn: int) -> Decimal:
    """`round(price[turn] / price[turn - 1] - 1, 8)`; `prices` holds turns 0..t."""
    quotient = money.pg_divide(prices[turn], prices[turn - 1])
    return money.round8(_EXACT.subtract(quotient, money.ONE))


def streak(prices: Sequence[Decimal]) -> int:
    """Run of equal price-change signs ending at the last turn (a zero change is a sign too)."""
    last = len(prices) - 1
    sign = _cmp(prices[last], prices[last - 1])
    count = 0
    turn = last
    while turn >= 1 and _cmp(prices[turn], prices[turn - 1]) == sign:
        count += 1
        turn -= 1
    return count


def _cmp(a: Decimal, b: Decimal) -> int:
    return (a > b) - (a < b)


def previous_stories(rows: Iterable[PreviousRow], turn_no: int) -> dict[int, Story]:
    """Last RISE/FALL/ECHO direction and largest ECHO repeat per asset; rows in seq order."""
    stories: dict[int, Story] = {}
    if turn_no < 2:
        return stories
    for row in rows:
        old = stories.get(row.asset_id)
        direction = None if old is None else old.direction
        repeat = 0 if old is None else old.echo_repeat
        if row.kind in ("RISE", "FALL", "ECHO") and row.direction is not None:
            direction = row.direction
        if row.kind == "ECHO":
            repeat = max(repeat, row.repeat_count)
        stories[row.asset_id] = Story(direction, repeat)
    return stories


def build_asset_facts(
    turn_no: int,
    pool: Sequence[PoolAsset],
    history: Mapping[int, Sequence[Decimal]],
    current_prices: Mapping[int, Decimal],
    attention: Mapping[int, Decimal],
    previous_attention: Mapping[int, Decimal],
    stories: Mapping[int, Story],
) -> list[AssetFacts]:
    """`pool` in (market, ticker) byte order; `history` holds prices of turns 0..turn_no-1."""
    exact = {
        asset_id: [money.exact_stored_price(p) for p in prices]
        for asset_id, prices in history.items()
    }
    for a in pool:
        seen = exact.get(a.asset_id)
        if a.asset_id not in current_prices or seen is None or len(seen) != turn_no:
            raise _input_error("a pool asset has an incomplete price history")
    ranked = sum(1 for a in pool if a.asset_id in attention)
    focus = None
    candidates = [a for a in pool if a.asset_id in attention]
    if candidates:
        best = min(
            candidates,
            key=lambda a: (_Reverse(attention[a.asset_id]), byte_key(a.market), byte_key(a.ticker)),
        )
        focus = best.name
    facts: list[AssetFacts] = []
    for a in pool:
        prices = list(exact[a.asset_id])
        prices.append(money.exact_stored_price(current_prices[a.asset_id]))
        own = attention.get(a.asset_id)
        before = previous_attention.get(a.asset_id)
        story = stories.get(a.asset_id)
        facts.append(
            AssetFacts(
                as_of_turn=turn_no,
                asset_id=a.asset_id,
                market=a.market,
                ticker=a.ticker,
                name=a.name,
                group_id=a.group_id,
                group_code=a.group_code,
                group_name=a.group_name,
                ret=change(prices, turn_no),
                prev_ret=change(prices, turn_no - 1) if turn_no >= 2 else None,
                streak=streak(prices),
                attention=own,
                attention_rank=None if own is None else _rank(own, pool, attention),
                attention_n=ranked,
                prev_attention=before,
                prev_attention_rank=None
                if before is None
                else _rank(before, pool, previous_attention),
                prev_story_direction=None if story is None else story.direction,
                prev_echo_repeat=1 if story is None else story.echo_repeat + 1,
                focus_name=focus,
            )
        )
    return facts


def _rank(own: Decimal, pool: Sequence[PoolAsset], values: Mapping[int, Decimal]) -> int:
    """Rank among pool assets only: the block holds other assets too, and they must not count."""
    return 1 + sum(1 for p in pool if p.asset_id in values and values[p.asset_id] > own)


@dataclass(frozen=True, slots=True)
class _Reverse:
    """Descending order of a Decimal inside a tuple key."""

    value: Decimal

    def __lt__(self, other: "_Reverse") -> bool:
        return self.value > other.value


def public_event_types(
    episodes: Iterable[tuple[str, str, date]], replay_start: date, base_year: int
) -> frozenset[str]:
    """Event types seen in at least three distinct key families inside the replay range.

    Each episode is (event type, event key, month start); pass every `month_event` row. Only
    months from `replay_start` up to January 1 of the season's base-date year count, so warm-up
    months do not inflate the count. Any type not returned is hidden (zero families included).
    """
    end = date(base_year, 1, 1)
    families: dict[str, set[str]] = defaultdict(set)
    for event_type, event_key, month_start in episodes:
        if replay_start <= month_start < end:
            families[event_type].add(event_key[:FAMILY_PREFIX])
    return frozenset(t for t, keys in families.items() if len(keys) >= HIDDEN_FAMILY_LIMIT)


def is_hidden_type(event_type: str, public_types: frozenset[str]) -> bool:
    return event_type not in public_types

"""Feed source of a bot world: the feed port run on the world's feed facts, one turn at a time.

The feed and the ranking board of a turn depend on the season seed, the prices up to that turn,
the attention of that turn and the one before, the events of that turn and the previous turn's
story rows. They do not depend on the player, so one source serves every bot of a world.
"""

from collections.abc import Collection, Mapping, Sequence
from decimal import Decimal
from typing import Any

from antlab_engine.sim import behaviour
from antlab_engine.sim.feed.catalog import Catalog
from antlab_engine.sim.feed.facts import (
    EventFacts,
    FeedInput,
    PoolAsset,
    PreviousRow,
    build_asset_facts,
    previous_stories,
)
from antlab_engine.sim.feed.feed import FeedGenerator, GeneratedRow
from antlab_engine.sim.feed.feedhash import byte_key
from antlab_engine.sim.feed.ranking import RankingInput, board
from antlab_engine.sim.feed.rules import Failure, FeedGenerationError
from antlab_engine.sim.feed.signals import FeedRow, signals_of

from .view import FeedItem, TurnFeed
from .world import BotWorld

_SOURCES: dict[str, "WorldFeedSource"] = {}
_SOURCES_MAX = 8
_CATALOG: list[Catalog] = []


def default_catalog() -> Catalog:
    if not _CATALOG:
        _CATALOG.append(Catalog.load())
    return _CATALOG[0]


class WorldFeedSource:
    """`FeedSource` over the `feed` section of a `bot-world/1`.

    Results are kept per turn so that the previous turn's story chain is built once. A cached
    turn is reused only when its own prices match, and the source belongs to one world.
    """

    def __init__(
        self,
        feed: Mapping[str, Any],
        seed: int,
        catalog: Catalog | None = None,
        pool: Collection[str] | None = None,
    ) -> None:
        """`pool`: the world's asset names; the feed facts must name exactly these assets."""
        if pool is not None:
            _check_assets(feed, pool)
        self._seed = seed
        self._generator = FeedGenerator(catalog or default_catalog())
        entries = sorted(
            feed["assets"], key=lambda a: tuple(byte_key(p) for p in a["asset"].split(":", 1))
        )
        self._pool: list[PoolAsset] = []
        self._ref_of: dict[int, str] = {}
        groups: dict[str, int] = {}
        for i, a in enumerate(entries, start=1):
            market, ticker = a["asset"].split(":", 1)
            gid = groups.setdefault(a["groupCode"], len(groups) + 1)
            self._pool.append(
                PoolAsset(i, market, ticker, a["name"], gid, a["groupCode"], a["groupName"])
            )
            self._ref_of[i] = a["asset"]
        self._attention: dict[int, dict[int, Decimal]] = {}
        for row in feed["attention"]:
            values = {
                p.asset_id: Decimal(row["values"][self._ref_of[p.asset_id]])
                for p in self._pool
                if row["values"].get(self._ref_of[p.asset_id]) is not None
            }
            self._attention[row["turn"]] = values
        self._events: dict[int, list[dict[str, Any]]] = {}
        for e in feed["events"]:
            self._events.setdefault(e["turn"], []).append(e)
        self._reviewed = frozenset(feed["reviewed"])
        self._cache: dict[int, tuple[tuple[Decimal, ...], TurnFeed, tuple[GeneratedRow, ...]]] = {}

    def at(self, turn: int, prices: Sequence[Mapping[str, Decimal]]) -> TurnFeed:
        return self._turn(turn, prices)[1]

    def _turn(
        self, turn: int, prices: Sequence[Mapping[str, Decimal]]
    ) -> tuple[tuple[Decimal, ...], TurnFeed, tuple[GeneratedRow, ...]]:
        if len(prices) != turn + 1:
            raise FeedGenerationError(Failure.FEED_INPUT, "prices must cover turns 0..turn")
        fingerprint = tuple(prices[turn][self._ref_of[p.asset_id]] for p in self._pool)
        cached = self._cache.get(turn)
        if cached is not None and cached[0] == fingerprint:
            return cached
        if turn not in self._reviewed:
            raise FeedGenerationError(Failure.FEED_EVENTS_UNREVIEWED, "the turn is not reviewed")
        previous_rows: tuple[GeneratedRow, ...] = ()
        if turn >= 2:
            previous_rows = self._turn(turn - 1, prices[:turn])[2]
        stories = previous_stories(
            (
                PreviousRow(r.asset_id, r.kind, r.direction, r.repeat_count)
                for r in previous_rows
                if r.asset_id is not None
            ),
            turn,
        )
        facts = build_asset_facts(
            turn,
            self._pool,
            {
                p.asset_id: [prices[t][self._ref_of[p.asset_id]] for t in range(turn)]
                for p in self._pool
            },
            {p.asset_id: prices[turn][self._ref_of[p.asset_id]] for p in self._pool},
            self._attention.get(turn, {}),
            self._attention.get(turn - 1, {}),
            stories,
        )
        events = tuple(
            EventFacts(
                turn,
                turn * 1000 + i,
                e["key"],
                e["category"],
                None if e["reaction"] is None else Decimal(e["reaction"]),
                e["strength"],
                e["hidden"],
            )
            for i, e in enumerate(self._events.get(turn, ()), start=1)
        )
        rows = tuple(self._generator.generate(FeedInput(self._seed, turn, tuple(facts), events)))
        ranking = RankingInput(turn, tuple(facts))
        ranked = board(ranking)
        signals = signals_of(
            turn,
            (FeedRow(turn, r.kind, r.asset_id, r.direction) for r in rows),
            ranking,
        )
        ref = self._ref_of

        def refs(ids: frozenset[int]) -> frozenset[str]:
            return frozenset(ref[i] for i in ids)

        reaction_of = {e.month_event_id: e.reaction for e in events}
        feed = TurnFeed(
            tuple(
                FeedItem(
                    r.seq,
                    r.kind,
                    None if r.asset_id is None else ref[r.asset_id],
                    r.direction,
                    reaction_of.get(r.month_event_id) if r.kind == "EVENT" else None,
                )
                for r in rows
            ),
            behaviour.TurnSignals(
                turn, refs(signals.up), refs(signals.down), refs(signals.top_attention)
            ),
            tuple(ref[r.asset_id] for r in ranked.gainers),
            tuple(ref[r.asset_id] for r in ranked.losers),
            tuple(ref[r.asset_id] for r in ranked.attention),
        )
        entry = (fingerprint, feed, rows)
        self._cache[turn] = entry
        return entry


def _check_assets(feed: Mapping[str, Any], pool: Collection[str]) -> None:
    names = [a["asset"] for a in feed["assets"]]
    if len(set(names)) != len(names):
        raise FeedGenerationError(Failure.FEED_INPUT, "duplicate asset in the feed facts")
    known = set(pool)
    if set(names) != known:
        raise FeedGenerationError(
            Failure.FEED_INPUT,
            f"feed assets differ from the pool (missing {sorted(known - set(names))},"
            f" extra {sorted(set(names) - known)})",
        )
    for row in feed["attention"]:
        extra = set(row["values"]) - known
        if extra:
            raise FeedGenerationError(
                Failure.FEED_INPUT, f"attention names assets outside the pool: {sorted(extra)}"
            )


def feed_source_for(world: BotWorld) -> WorldFeedSource | None:
    """The shared source of a world with feed facts; None for a world without them."""
    if world.feed is None:
        return None
    key = world.world_id
    source = _SOURCES.get(key)
    if source is None:
        source = WorldFeedSource(world.feed, world.seed, pool=[a.asset for a in world.inputs.pool])
        if len(_SOURCES) >= _SOURCES_MAX:
            del _SOURCES[next(iter(_SOURCES))]
        _SOURCES[key] = source
    return source

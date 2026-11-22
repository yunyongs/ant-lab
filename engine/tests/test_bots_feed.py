"""The feed source of a bot world: no look-ahead, scoring input of every bot run."""

import copy
from dataclasses import replace
from decimal import Decimal

import pytest

from antlab_engine.bots.rng import BotRng, mix
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import BotWorld, synthetic_world
from antlab_engine.bots.world_feed import WorldFeedSource, feed_source_for
from antlab_engine.sim.feed.rules import Failure, FeedGenerationError
from antlab_engine.sim.model import SimInputs
from antlab_engine.sim.season import Season


def _prices(world: BotWorld) -> list[dict[str, Decimal]]:
    season = Season(world.inputs)
    for _ in range(world.inputs.settings.path_length):
        season.advance()
    return season.turn_prices


def _source(world: BotWorld) -> WorldFeedSource:
    assert world.feed is not None
    return WorldFeedSource(world.feed, world.seed)


def test_every_turn_has_rows_a_board_and_signals_inside_the_pool():
    world = synthetic_world(3, assets=14, length=12, feed=True)
    prices = _prices(world)
    source = _source(world)
    names = {a.asset for a in world.inputs.pool}
    rows_seen = 0
    for turn in range(1, 12):
        feed = source.at(turn, prices[: turn + 1])
        rows_seen += len(feed.items)
        assert [i.seq for i in feed.items] == list(range(1, len(feed.items) + 1))
        assert 5 <= len(feed.items) <= 15
        assert feed.signals is not None and feed.signals.turn == turn
        for group in (feed.signals.up, feed.signals.down, feed.signals.top_attention):
            assert group <= names
        assert len(feed.gainers) <= 5 and len(feed.losers) <= 5 and len(feed.attention) <= 5
        assert set(feed.losers) <= feed.signals.down
        assert set(feed.gainers) | set(feed.attention) <= feed.signals.up
    assert rows_seen > 0


def test_a_fresh_source_and_a_warm_one_agree():
    world = synthetic_world(4, assets=14, length=12, feed=True)
    prices = _prices(world)
    warm = _source(world)
    first = [warm.at(t, prices[: t + 1]) for t in range(1, 12)]
    assert first == [warm.at(t, prices[: t + 1]) for t in range(1, 12)]
    cold = _source(world)
    assert cold.at(11, prices[:12]) == first[-1]  # the story chain is rebuilt from the prices


def test_a_turn_is_a_function_of_prices_up_to_it_and_the_facts_of_that_turn_and_the_one_before():
    for seed in range(20):
        world = synthetic_world(seed, assets=12, length=12, feed=True)
        prices = _prices(world)
        t = BotRng(mix(seed, 9)).between(2, 9)
        doc = copy.deepcopy(dict(world.feed or {}))
        for entry in doc["attention"]:
            if entry["turn"] > t:
                entry["values"] = {k: "1.00" for k in entry["values"]}
        doc["events"] = [e for e in doc["events"] if e["turn"] <= t]
        altered_prices = [dict(p) for p in prices]
        for later in range(t + 1, 13):
            altered_prices[later] = {k: v + 1 for k, v in prices[later].items()}
        base, other = _source(world), WorldFeedSource(doc, world.seed)
        for turn in range(1, t + 1):
            assert base.at(turn, prices[: turn + 1]) == other.at(turn, altered_prices[: turn + 1])


def test_an_unreviewed_turn_refuses_like_java():
    world = synthetic_world(5, assets=10, length=12, feed=True)
    doc = copy.deepcopy(dict(world.feed or {}))
    doc["reviewed"] = [t for t in doc["reviewed"] if t != 4]
    source = WorldFeedSource(doc, world.seed)
    prices = _prices(world)
    source.at(3, prices[:4])
    with pytest.raises(FeedGenerationError) as error:
        source.at(4, prices[:5])
    assert error.value.failure is Failure.FEED_EVENTS_UNREVIEWED


def test_prices_must_cover_exactly_the_turns_so_far():
    world = synthetic_world(5, assets=10, length=12, feed=True)
    prices = _prices(world)
    with pytest.raises(FeedGenerationError):
        _source(world).at(3, prices[:5])


def test_a_world_without_feed_facts_has_no_source_and_a_world_with_them_shares_one():
    assert feed_source_for(synthetic_world(1, assets=8, length=12)) is None
    world = synthetic_world(1, assets=8, length=12, feed=True)
    assert feed_source_for(world) is feed_source_for(replace(world))


def test_every_bot_run_is_scored_with_the_noise_item_when_the_world_has_a_feed():
    world = synthetic_world(6, assets=16, length=24, feed=True)
    plain_world = synthetic_world(6, assets=16, length=24)
    for kind in ("random", "disciplined", "news"):
        with_feed = run_bot(world, make_bot(kind, 0, 1, world.seed)).record
        without = run_bot(plain_world, make_bot(kind, 0, 1, world.seed)).record
        assert with_feed["noiseShare"] is not None, kind
        assert without["noiseShare"] is None, kind


def _changed_after(world: BotWorld, other: BotWorld, turn: int) -> BotWorld:
    steps = world.inputs.path[:turn] + other.inputs.path[turn:]
    return replace(world, inputs=SimInputs(world.inputs.settings, world.inputs.pool, steps))


@pytest.mark.parametrize("kind", ["disciplined", "disciplined-noguard", "news"])
def test_decisions_and_feed_up_to_turn_t_do_not_depend_on_later_steps(kind):
    for seed in range(50):
        world = synthetic_world(seed, assets=10, length=12, feed=True)
        other = synthetic_world(seed + 1000, assets=10, length=12, feed=True)
        t = BotRng(mix(seed, 5)).below(10) + 1
        altered = _changed_after(world, other, t)
        assert altered.inputs.path != world.inputs.path
        before = run_bot(world, make_bot(kind, 0, 3, world.seed))
        after = run_bot(altered, make_bot(kind, 0, 3, world.seed))
        first = [(turn, units) for turn, units in before.units_by_turn if turn <= t]
        second = [(turn, units) for turn, units in after.units_by_turn if turn <= t]
        assert first == second, (seed, kind, t)
        p1, p2 = _prices(world), _prices(altered)
        for turn in range(1, t + 1):
            a = feed_source_for(world).at(turn, p1[: turn + 1])  # type: ignore[union-attr]
            b = feed_source_for(altered).at(turn, p2[: turn + 1])  # type: ignore[union-attr]
            assert a == b, (seed, turn)


def _doc(world):
    return copy.deepcopy(dict(world.feed or {}))


def test_feed_assets_must_equal_the_pool_a_missing_one_refuses():
    world = synthetic_world(3, assets=12, length=12, feed=True)
    doc = _doc(world)
    doc["assets"] = [a for a in doc["assets"] if a["asset"] != "US:S001"]
    broken = replace(world, feed=doc)
    with pytest.raises(FeedGenerationError, match="missing .*US:S001") as error:
        feed_source_for(broken)
    assert error.value.failure is Failure.FEED_INPUT
    with pytest.raises(FeedGenerationError):
        run_bot(broken, make_bot("random", 0, 1, broken.seed))


def test_feed_assets_must_equal_the_pool_an_extra_one_refuses():
    world = synthetic_world(3, assets=12, length=12, feed=True)
    doc = _doc(world)
    doc["assets"].append({"asset": "KR:ZZZ999", "name": "x", "groupCode": "G0", "groupName": "g"})
    with pytest.raises(FeedGenerationError, match="extra .*KR:ZZZ999") as error:
        feed_source_for(replace(world, feed=doc))
    assert error.value.failure is Failure.FEED_INPUT


def test_a_duplicate_feed_asset_and_attention_outside_the_pool_refuse():
    world = synthetic_world(3, assets=12, length=12, feed=True)
    doc = _doc(world)
    doc["assets"].append(dict(doc["assets"][0]))
    with pytest.raises(FeedGenerationError, match="duplicate"):
        feed_source_for(replace(world, feed=doc))
    doc = _doc(world)
    doc["attention"][2]["values"]["KR:ZZZ999"] = "1.00"
    with pytest.raises(FeedGenerationError, match="outside the pool"):
        feed_source_for(replace(world, feed=doc))


class _BuyTopAttention:
    kind = "scripted"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self, at: int):
        self.at = at
        self.seen = None

    def decide(self, view):
        from antlab_engine.bots.random_bot import buy_unit

        if view.turn != self.at:
            return ()
        self.seen = (view.feed, view.signals)
        pool = {a.asset: a for a in view.pool}
        built = buy_unit(view, pool[view.attention[0]], 5_000_000)
        return (built[0],)


def _flat_turn_world() -> BotWorld:
    world = synthetic_world(5, assets=8, length=12, feed=True)
    steps = list(world.inputs.path)
    flat = steps[2]
    steps[2] = replace(
        flat,
        local_returns={k: Decimal(0) for k in flat.local_returns},
        fx_change={k: Decimal(0) for k in flat.fx_change},
    )
    doc = _doc(world)
    doc["events"] = [e for e in doc["events"] if e["turn"] != 3]
    for entry in doc["attention"]:
        if entry["turn"] in (2, 3):
            entry["values"] = {k: f"{10 + i}.00" for i, k in enumerate(sorted(entry["values"]))}
    inputs = SimInputs(world.inputs.settings, world.inputs.pool, tuple(steps))
    return replace(world, inputs=inputs, feed=doc)


def test_a_turn_with_no_feed_rows_still_gives_signals_from_the_board_to_the_score():
    world = _flat_turn_world()
    prices = _prices(world)
    feed = _source(world).at(3, prices[:4])
    assert feed.items == ()
    assert feed.signals is not None and feed.signals.up and feed.attention
    bot = _BuyTopAttention(3)
    record = run_bot(world, bot).record
    assert bot.seen[0] == () and bot.seen[1] == feed.signals
    # the buy follows an attention-board asset of a turn without rows: the noise item must see it
    assert record["noiseShare"] == "1.00000000"

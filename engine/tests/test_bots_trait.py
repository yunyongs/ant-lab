"""Rules of the trait probe bot, on hand-built views and on whole seasons."""

from decimal import Decimal

import pytest
from bots_support import KR, KR2, US, view

from antlab_engine.bots.random_bot import RandomBot
from antlab_engine.bots.rng import KIND_RANDOM, KIND_TRAIT, mix
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.trait_bot import TRAITS, TraitBot, level_text
from antlab_engine.bots.view import FeedItem, ViewAsset
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.decisions import OrderUnit
from antlab_engine.sim.model import Position, Side, SourceKind

ZERO = dict.fromkeys(TRAITS, 0)
TEAM_SEED = 99


def bot(seed: int = 1, **levels: int) -> TraitBot:
    return TraitBot(0, seed, TEAM_SEED, {**ZERO, **levels})


def orders(units):
    assert all(isinstance(u, OrderUnit) for u in units)
    return [u.order for u in units]


def moved(prev: dict[str, int], now: dict[str, int]):
    return {"prev_prices": prev, "prices": now}


def test_levels_are_text_in_steps_of_a_quarter():
    assert [level_text(i) for i in range(5)] == ["0", "0.25", "0.5", "0.75", "1"]
    assert dict(bot(follow=3, chase=4).params) == {
        "follow": "0.75",
        "route": "0",
        "panic": "0",
        "chase": "1",
        "activity": "0",
        "event": "0",
    }


def test_unknown_trait_or_level_is_refused():
    with pytest.raises(ValueError, match="levels"):
        TraitBot(0, 1, 2, {"patience": 1})
    with pytest.raises(ValueError, match="levels"):
        TraitBot(0, 1, 2, {"panic": 5})


def test_turn_zero_is_the_random_bots_team_of_the_same_index():
    v = view(turn=0, cash=100_000_000, total=None)
    assert bot().decide(v) == RandomBot(0, TEAM_SEED).decide(v)


def test_make_bot_seeds_the_team_from_the_random_bot_and_the_traits_from_their_own_tag():
    made = make_bot("trait", 4, 7, 11)
    assert made.seed == mix(7, 11, KIND_TRAIT, 4)
    v = view(turn=0, cash=100_000_000, total=None)
    assert made.decide(v) == RandomBot(4, mix(7, 11, KIND_RANDOM, 4)).decide(v)
    assert made.kind == "trait" and made.version == 1


def test_all_traits_zero_never_trade_after_turn_zero():
    feed = (
        FeedItem(1, "RISE", KR.asset, "UP"),
        FeedItem(2, "FALL", US.asset, "DOWN"),
        FeedItem(3, "EVENT", None, None, Decimal("-0.02")),
    )
    for seed in range(20):
        b = bot(seed)
        for turn in range(1, 5):
            v = view(
                turn=turn,
                holdings={KR.asset: 10, US.asset: 5},
                feed=feed,
                gainers=(KR2.asset,),
                **moved({KR.asset: 10_000, US.asset: 10_000}, {KR.asset: 8_000, US.asset: 12_000}),
            )
            assert b.decide(v) == ()


def test_panic_sells_every_holding_that_fell_eight_percent_and_only_those():
    v = view(
        holdings={KR.asset: 100, KR2.asset: 40, US.asset: 7},
        **moved(
            {KR.asset: 10_000, KR2.asset: 10_000, US.asset: 10_000},
            {KR.asset: 9_000, KR2.asset: 9_500, US.asset: 9_200},
        ),
    )
    placed = orders(bot(panic=4).decide(v))
    assert [(o.asset, o.side, o.quantity, o.source.kind) for o in placed] == [
        (KR.asset, Side.SELL, 100, SourceKind.DIRECT),
        (US.asset, Side.SELL, 7, SourceKind.DIRECT),
    ]


def test_panic_without_a_drop_or_without_a_holding_does_nothing():
    assert bot(panic=4).decide(view(holdings={KR.asset: 5})) == ()
    dropped = view(**moved({KR.asset: 10_000}, {KR.asset: 8_000}))
    assert bot(panic=4).decide(dropped) == ()


def test_chase_buys_one_asset_that_rose_eight_percent():
    v = view(**moved({KR.asset: 10_000, US.asset: 10_000}, {KR.asset: 10_800, US.asset: 10_790}))
    for seed in range(10):
        placed = orders(bot(seed, chase=4).decide(v))
        assert [(o.asset, o.side) for o in placed] == [(KR.asset, Side.BUY)]
    flat = view(**moved({KR.asset: 10_000}, {KR.asset: 10_500}))
    assert bot(chase=4).decide(flat) == ()


def test_chase_picks_among_the_risers():
    v = view(
        **moved(
            {KR.asset: 10_000, KR2.asset: 10_000, US.asset: 10_000},
            {KR.asset: 11_000, KR2.asset: 12_000, US.asset: 10_000},
        )
    )
    picked = {orders(bot(seed, chase=4).decide(v))[0].asset for seed in range(40)}
    assert picked == {KR.asset, KR2.asset}


def test_follow_trades_a_pointed_asset_every_turn():
    feed = (FeedItem(1, "RISE", KR2.asset, "UP"), FeedItem(2, "FALL", KR.asset, "DOWN"))
    seen = set()
    for seed in range(40):
        v = view(holdings={KR.asset: 10}, feed=feed)
        placed = orders(bot(seed, follow=4).decide(v))
        assert len(placed) == 1
        seen.add(
            (
                placed[0].side,
                placed[0].asset,
                placed[0].quantity if placed[0].side is Side.SELL else 0,
            )
        )
    assert seen == {(Side.BUY, KR2.asset, 0), (Side.SELL, KR.asset, 10)}


def test_follow_sells_losers_and_buys_gainers_and_attention_boards():
    only_buys = view(gainers=(KR2.asset,), attention=(US.asset,))
    assert {orders(bot(s, follow=4).decide(only_buys))[0].asset for s in range(40)} == {
        KR2.asset,
        US.asset,
    }
    only_sell = view(holdings={KR.asset: 3}, losers=(KR.asset,))
    placed = orders(bot(follow=4).decide(only_sell))
    assert [(o.asset, o.side, o.quantity) for o in placed] == [(KR.asset, Side.SELL, 3)]
    assert bot(follow=4).decide(view(holdings={KR.asset: 3})) == ()


def test_follow_never_sells_what_it_does_not_hold():
    v = view(feed=(FeedItem(1, "FALL", KR.asset, "DOWN"),), losers=(KR.asset,))
    assert bot(follow=4).decide(v) == ()


FORWARDS = (
    ViewAsset("KR:F1", "KR", Position.FORWARD_TARGET),
    ViewAsset("KR:F2", "KR", Position.FORWARD_BREAKOUT),
    ViewAsset("KR:069500", "KR", Position.MIDFIELDER),
    ViewAsset("US:SPY", "US", Position.MIDFIELDER),
)


def event_view(reaction, holdings=None, rows=1):
    feed = tuple(FeedItem(i + 1, "EVENT", None, None, reaction) for i in range(rows))
    return view(pool=FORWARDS, holdings=holdings or {}, feed=feed)


def test_event_with_a_negative_reaction_sells_the_largest_forward_holding():
    v = event_view(Decimal("-0.03"), {"KR:F1": 5, "KR:F2": 7, "KR:069500": 100})
    placed = orders(bot(event=4).decide(v))
    assert [(o.asset, o.side, o.quantity) for o in placed] == [("KR:F2", Side.SELL, 7)]


def test_event_with_a_positive_reaction_buys_a_market_index_asset():
    v = event_view(Decimal("0.02"), {"KR:F1": 5})
    bought = set()
    for seed in range(30):
        placed = orders(bot(seed, event=4).decide(v))
        assert len(placed) == 1 and placed[0].side is Side.BUY
        bought.add(placed[0].asset)
    assert bought == {"KR:069500", "US:SPY"}


def test_event_acts_only_in_event_turns_with_a_stated_reaction():
    holdings = {"KR:F1": 5}
    assert bot(event=4).decide(view(pool=FORWARDS, holdings=holdings)) == ()
    assert bot(event=4).decide(event_view(None, holdings)) == ()
    assert bot(event=4).decide(event_view(Decimal(0), holdings)) == ()
    assert bot(event=4).decide(event_view(Decimal("-0.03"), {"KR:069500": 9})) == ()


def test_activity_makes_one_random_trade():
    v = view(holdings={KR.asset: 100})
    sides = set()
    for seed in range(40):
        placed = orders(bot(seed, activity=4).decide(v))
        assert len(placed) == 1
        sides.add(placed[0].side)
    assert sides == {Side.BUY, Side.SELL}
    empty = orders(bot(activity=4).decide(view()))
    assert [o.side for o in empty] == [Side.BUY]


def test_traits_that_are_not_certain_act_in_some_turns_only():
    v = view(holdings={KR.asset: 100})
    acted = sum(bool(bot(seed, activity=2).decide(v)) for seed in range(200))
    assert 70 <= acted <= 130


def routed_view(turn=3):
    pool = (KR, KR2, US)
    return view(
        turn=turn,
        pool=pool,
        holdings={KR.asset: 50, KR2.asset: 50, US.asset: 50},
        feed=(FeedItem(4, "RISE", KR.asset, "UP"), FeedItem(9, "ECHO", KR.asset, "UP")),
        gainers=(KR2.asset,),
    )


def test_route_cites_only_rows_and_boards_the_view_shows():
    cited = {SourceKind.FEED: 0, SourceKind.RANKING: 0, SourceKind.DIRECT: 0}
    for seed in range(80):
        v = routed_view()
        for o in orders(bot(seed, activity=4, route=4).decide(v)):
            cited[o.source.kind] += 1
            if o.asset == KR.asset:
                assert o.source.kind is SourceKind.FEED
                assert (o.source.feed_turn, o.source.feed_seq) == (3, 4)
            elif o.asset == KR2.asset:
                assert o.source.kind is SourceKind.RANKING
            else:
                assert o.source.kind is SourceKind.DIRECT
    assert all(cited.values())


def test_route_zero_cites_nothing_and_a_chance_below_one_cites_some_orders():
    for seed in range(30):
        placed = orders(bot(seed, activity=4).decide(routed_view()))
        assert all(o.source.kind is SourceKind.DIRECT for o in placed)
    kinds = set()
    for seed in range(60):
        kinds |= {
            o.source.kind for o in orders(bot(seed, activity=4, route=2).decide(routed_view()))
        }
    assert SourceKind.DIRECT in kinds and SourceKind.FEED in kinds


def test_route_alone_trades_nothing():
    assert bot(route=4).decide(routed_view()) == ()


def test_changing_one_trait_leaves_the_actions_of_the_traits_before_it_unchanged():
    holdings = {KR.asset: 80, KR2.asset: 60, US.asset: 40}
    v = view(
        holdings=holdings,
        feed=(FeedItem(1, "RISE", KR2.asset, "UP"), FeedItem(2, "FALL", KR.asset, "DOWN")),
        gainers=(US.asset,),
        **moved(
            {KR.asset: 10_000, KR2.asset: 10_000, US.asset: 10_000},
            {KR.asset: 9_000, KR2.asset: 11_000, US.asset: 10_000},
        ),
    )
    differing = 0
    for seed in range(60):
        base = bot(seed, panic=2, follow=2)
        other = bot(seed, panic=2, follow=2, chase=3)
        for _ in range(4):
            a, b = base.decide(v), other.decide(v)
            assert b[: len(a)] == a
            differing += len(b) != len(a)
    assert differing > 0


def test_the_six_levels_of_one_bot_are_drawn_independently():
    tuples = {
        tuple(TraitBot(i, mix(5, 6, KIND_TRAIT, i), 1).levels[t] for t in TRAITS)
        for i in range(200)
    }
    assert len(tuples) > 150
    for t in TRAITS:
        levels = [TraitBot(i, mix(5, 6, KIND_TRAIT, i), 1).levels[t] for i in range(200)]
        assert set(levels) == {0, 1, 2, 3, 4}
    unequal = sum(
        len({TraitBot(i, mix(5, 6, KIND_TRAIT, i), 1).levels[t] for t in TRAITS}) > 1
        for i in range(200)
    )
    assert unequal > 190


def test_drawn_levels_are_kept_when_a_test_overrides_one_of_them():
    drawn = TraitBot(3, 77, 1)
    overridden = TraitBot(3, 77, 1, {"chase": 4})
    assert {t: v for t, v in overridden.levels.items() if t != "chase"} == {
        t: v for t, v in drawn.levels.items() if t != "chase"
    }


def test_whole_seasons_are_legal_deterministic_and_only_cite_what_was_shown():
    worlds = [synthetic_world(seed, assets=14, length=24, feed=True) for seed in range(4)]
    cited = 0
    for world in worlds:
        for index in range(12):
            recorder = _Recording(make_bot("trait", index, 303, world.seed))
            run_a = run_bot(world, recorder)
            run_b = run_bot(world, make_bot("trait", index, 303, world.seed))
            assert run_a.record["verdicts"]["rejected"] == {}
            assert run_a.record["decisionsSha256"] == run_b.record["decisionsSha256"]
            shown = recorder.views
            for turn, units in run_a.units_by_turn:
                view_of_turn = shown[turn]
                rows = {i.seq: i.asset for i in view_of_turn.feed}
                boards = {*view_of_turn.gainers, *view_of_turn.losers, *view_of_turn.attention}
                for unit in units:
                    source = unit.order.source
                    if source.kind is SourceKind.FEED:
                        cited += 1
                        assert source.feed_turn == turn
                        assert rows[source.feed_seq] == unit.order.asset
                    elif source.kind is SourceKind.RANKING:
                        cited += 1
                        assert unit.order.asset in boards
    assert cited > 0


class _Recording:
    """Delegates to a bot and keeps the views it saw."""

    def __init__(self, inner):
        self.inner = inner
        self.kind, self.version, self.index = inner.kind, inner.version, inner.index
        self.seed, self.params = inner.seed, inner.params
        self.views = {}

    def decide(self, v):
        self.views[v.turn] = v
        return self.inner.decide(v)

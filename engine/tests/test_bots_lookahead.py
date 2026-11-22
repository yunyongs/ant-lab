"""A bot never sees data after the turn it decides on."""

import dataclasses
from dataclasses import replace

from antlab_engine.bots import view as view_module
from antlab_engine.bots.rng import BotRng, mix
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.model import SimInputs

BANNED = ("path", "block", "month", "date", "next", "future")


def test_view_types_have_no_path_block_month_or_date_field():
    for cls in (
        view_module.BotView,
        view_module.ViewAsset,
        view_module.FeedItem,
        view_module.TurnFeed,
    ):
        for field in dataclasses.fields(cls):
            assert not any(word in field.name for word in BANNED), (cls.__name__, field.name)


class _Recorder:
    kind = "recorder"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self):
        self.views = []

    def decide(self, view):
        self.views.append(view)
        return ()


def _changed_after(world, other, turn):
    steps = world.inputs.path[:turn] + other.inputs.path[turn:]
    return replace(world, inputs=SimInputs(world.inputs.settings, world.inputs.pool, steps))


def test_units_and_views_up_to_turn_t_do_not_depend_on_later_steps():
    for seed in range(50):
        world = synthetic_world(seed, assets=10, length=12)
        other = synthetic_world(seed + 1000, assets=10, length=12)
        assert [a.asset for a in other.inputs.pool] == [a.asset for a in world.inputs.pool]
        t = BotRng(mix(seed, 5)).below(11)
        altered = _changed_after(world, other, t)
        assert altered.inputs.path != world.inputs.path
        for kind in ("random", "static"):
            before = run_bot(world, make_bot(kind, seed, 3, world.seed))
            after = run_bot(altered, make_bot(kind, seed, 3, world.seed))
            first = [(turn, units) for turn, units in before.units_by_turn if turn <= t]
            second = [(turn, units) for turn, units in after.units_by_turn if turn <= t]
            assert first == second, (seed, kind, t)


def test_the_view_at_turn_t_is_the_same_whatever_follows():
    world = synthetic_world(7, assets=10, length=12)
    other = synthetic_world(1007, assets=10, length=12)
    altered = _changed_after(world, other, 4)
    a, b = _Recorder(), _Recorder()
    run_bot(world, a)
    run_bot(altered, b)
    assert a.views[:5] == b.views[:5]
    assert a.views[5] != b.views[5]


def test_a_view_holds_prices_of_its_own_turn_and_the_one_before_only():
    world = synthetic_world(7, assets=10, length=12)
    recorder = _Recorder()
    run_bot(world, recorder)
    assert recorder.views[0].prev_prices is None
    for turn, v in enumerate(recorder.views):
        assert v.turn == turn
        if turn:
            assert v.prev_prices is not None
            assert v.prices != v.prev_prices

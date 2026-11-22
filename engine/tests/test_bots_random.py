"""Rules of the random bot, on hand-built views and a scripted stream."""

from decimal import Decimal

import pytest
from bots_support import KR, KR2, ScriptedRng, view

from antlab_engine.bots.random_bot import RandomBot, build_team
from antlab_engine.bots.rng import BotRng, mix
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.view import ViewAsset
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.decisions import FormationUnit, OrderUnit, apply_unit, parse_decisions
from antlab_engine.sim.model import Formation, Position, Side
from antlab_engine.sim.season import Season


def _bot(**script) -> RandomBot:
    bot = RandomBot(0, 1)
    bot._rng = ScriptedRng(**script)  # type: ignore[assignment]
    return bot


def test_start_team_size_assets_and_investment_share():
    world = synthetic_world(11, assets=20, length=12)
    sizes = set()
    for index in range(40):
        bot = make_bot("random", index, 1, world.seed)
        run = run_bot(world, bot)
        buys = [u for t, units in run.units_by_turn if t == 0 for u in units]
        assert all(isinstance(u, OrderUnit) and u.order.side is Side.BUY for u in buys)
        assert 6 <= len(buys) <= 10
        assert len({u.order.asset for u in buys}) == len(buys)
        sizes.add(len(buys))
    assert sizes >= {6, 7, 8, 9, 10}


def test_start_team_spends_at_most_ninety_percent_and_close_to_it():
    world = synthetic_world(3, assets=20, length=12)
    for index in range(10):
        run = run_bot(world, make_bot("random", index, 7, world.seed))
        season = Season(world.inputs)
        for unit in parse_decisions(run.decisions).turns[0].units:
            assert apply_unit(season, unit).accepted
        spent = Decimal(100_000_000) - season.cash
        assert Decimal(85_000_000) < spent <= Decimal(90_000_000)


def test_declared_position_is_the_system_position_or_midfielder():
    world = synthetic_world(5, assets=20, length=12)
    systems = {a.asset: a.system_position for a in world.inputs.pool}
    seen = set()
    for index in range(20):
        run = run_bot(world, make_bot("random", index, 1, world.seed))
        for _, units in run.units_by_turn:
            for u in units:
                if isinstance(u, OrderUnit) and u.order.side is Side.BUY:
                    assert u.order.position == (systems[u.order.asset] or Position.MIDFIELDER)
                    seen.add(u.order.position)
    assert Position.MIDFIELDER in seen


def test_team_is_smaller_than_the_draw_when_the_pool_is_small():
    units = build_team(view(turn=0, total=None), BotRng(mix(1, 2, 3, 4)))
    assert 1 <= len(units) <= 3


@pytest.mark.parametrize(
    ("fraction_index", "held", "expected"),
    [(0, 1, 1), (0, 10, 2), (1, 10, 5), (1, 1, 1), (1, 3, 1), (2, 10, 10), (2, 1, 1)],
)
def test_sell_fraction_floors_and_never_below_one(fraction_index, held, expected):
    bot = _bot(
        chance=[True, True, False],
        between=[1],
        below=[0, fraction_index],
    )
    units = bot.decide(view(holdings={KR.asset: held}))
    assert len(units) == 1
    order = units[0].order
    assert (order.side, order.asset, order.quantity) == (Side.SELL, KR.asset, expected)


def test_buy_budget_is_percent_of_last_total_and_skipped_below_one_unit():
    # 2 % of 50,000,000 = 1,000,000 won buys 99 units at 10,000 won plus fee
    bot = _bot(chance=[True, False], between=[1, 2], below=[0])
    units = bot.decide(view(prices={KR.asset: 10_000}))
    assert [(u.order.side, u.order.asset, u.order.quantity) for u in units] == [
        (Side.BUY, KR.asset, 99)
    ]
    # a price above the whole budget: nothing is sent
    bot = _bot(chance=[True, False], between=[1, 2], below=[0])
    assert bot.decide(view(prices={KR.asset: 2_000_000})) == ()


def test_buy_is_capped_by_cash():
    bot = _bot(chance=[True, False], between=[1, 10], below=[0])
    units = bot.decide(view(cash=300_000, prices={KR.asset: 10_000}))
    quantity = units[0].order.quantity
    assert quantity == 29  # 300,000 / (10,000 * 1.00015) rounded down


def test_sells_come_before_buys_and_formation_last():
    bot = _bot(
        chance=[True, True, True],  # act, sell (the second order has nothing to sell), formation
        between=[2, 2, 1],  # two orders, buy percent, goalkeeper step
        below=[0, 2, 1, 6, 6],  # sold asset, fraction, bought asset (KR2), d, m
    )
    units = bot.decide(view(holdings={KR.asset: 4}, prices={KR2.asset: 10_000}))
    kinds = [type(u).__name__ for u in units]
    sides = [u.order.side for u in units if isinstance(u, OrderUnit)]
    assert kinds[-1] == "FormationUnit"
    assert sides == sorted(sides, key=lambda s: s is Side.BUY)


def test_formation_is_valid_and_always_differs():
    current = Formation(10, 30, 30, 30)
    seen = set()
    for seed in range(300):
        bot = RandomBot(0, seed)
        unit = bot._formation(view(formation=current))
        shares = (unit.goalkeeper, unit.defender, unit.midfielder, unit.forward)
        assert sum(shares) == 100
        assert all(s % 5 == 0 for s in shares)
        assert 5 <= unit.goalkeeper <= 40
        assert shares != (10, 30, 30, 30)
        seen.add(shares)
    assert len(seen) > 50


def test_formation_redraws_when_the_draw_equals_the_current_one():
    bot = _bot(between=[2, 1], below=[6, 6, 0, 0])
    unit = bot._formation(view())
    assert isinstance(unit, FormationUnit)
    assert (unit.goalkeeper, unit.defender, unit.midfielder, unit.forward) == (5, 0, 0, 95)


def test_no_formation_unit_at_turn_zero_and_every_unit_is_accepted():
    world = synthetic_world(21, assets=14, length=24)
    formations = 0
    for index in range(30):
        run = run_bot(world, make_bot("random", index, 3, world.seed))
        assert run.record["verdicts"]["rejected"] == {}
        for turn, units in run.units_by_turn:
            for u in units:
                if isinstance(u, FormationUnit):
                    assert turn >= 1
                    formations += 1
    assert formations > 0


def _pool(n: int) -> tuple[ViewAsset, ...]:
    return tuple(ViewAsset(f"KR:P{i:02d}", "KR", Position.MIDFIELDER) for i in range(n))


def test_start_team_weights_run_from_one_to_ten_and_split_the_ninety_percent():
    # scripted: size 6, no swaps, then six weights; ScriptedRng fails on a bound other than 1..10
    rng = ScriptedRng(between=[6, 1, 1, 1, 1, 1, 10], below=[0] * 6)
    bot = RandomBot(0, 1)
    bot._rng = rng  # type: ignore[assignment]
    units = bot.decide(view(turn=0, total=None, cash=100_000_000, pool=_pool(10)))
    assert [u.order.quantity for u in units] == [599, 599, 599, 599, 599, 5999]
    assert [u.order.asset for u in units] == [f"KR:P{i:02d}" for i in range(6)]
    assert not rng.between_q and not rng.below_q


def test_act_chance_is_one_half_and_the_formation_chance_is_drawn_every_turn():
    rng = ScriptedRng(chance=[False, True], between=[1], below=[0, 0])
    bot = RandomBot(0, 1)
    bot._rng = rng  # type: ignore[assignment]
    units = bot.decide(view())
    assert rng.chance_args == [(1, 2), (1, 10)]
    assert units == (FormationUnit(5, 0, 0, 95),)
    quiet = ScriptedRng(chance=[False, False])
    bot._rng = quiet  # type: ignore[assignment]
    assert bot.decide(view()) == ()
    assert quiet.chance_args == [(1, 2), (1, 10)]


def test_a_buy_may_spend_this_turns_sell_proceeds():
    rng = ScriptedRng(chance=[True, True, False], between=[2, 10], below=[0, 2, 1])
    bot = RandomBot(0, 1)
    bot._rng = rng  # type: ignore[assignment]
    units = bot.decide(view(cash=0, holdings={KR.asset: 100}, prices={KR.asset: 10_000}))
    sell, buy = units
    assert (sell.order.side, sell.order.quantity) == (Side.SELL, 100)
    assert (buy.order.side, buy.order.asset) == (Side.BUY, KR2.asset)
    # proceeds 1,000,000 less the 150 won sell fee buy 99 units of 10,000 plus fee
    assert buy.order.quantity == 99
    assert rng.chance_args == [(1, 2), (1, 2), (1, 10)]


def test_formation_split_is_symmetric_between_the_three_outfield_lines():
    totals = [0, 0, 0]
    draws = 3000
    for seed in range(draws):
        unit = RandomBot(0, seed)._formation(view())
        for i, share in enumerate((unit.defender, unit.midfielder, unit.forward)):
            totals[i] += share
    means = [t / draws for t in totals]
    assert max(means) - min(means) < 2.0, means

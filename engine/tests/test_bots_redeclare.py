"""The redeclare probe: the random bot of the same index plus a formation change."""

import pytest
from bots_support import view

from antlab_engine.bots.random_bot import RandomBot
from antlab_engine.bots.redeclare_bot import (
    VERSION,
    RedeclareBot,
    nearest_formation,
    snapshot_values,
)
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.view import ViewAsset
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.decisions import FormationUnit, OrderUnit, parse_decisions, play
from antlab_engine.sim.model import Formation, Order, Position, Side
from antlab_engine.sim.report import plain

D = ViewAsset("KR:D", "KR", Position.DEFENDER)
M = ViewAsset("KR:M", "KR", Position.MIDFIELDER)
F = ViewAsset("KR:F", "KR", Position.FORWARD_BREAKOUT)
G = ViewAsset("KR:G", "KR", Position.GOALKEEPER)
N = ViewAsset("KR:N", "KR", None)
POOL = (D, M, F, G, N)
PRICE = 10_000


def _held(cash, **units):
    holdings = {a.asset: units[a.asset[3:]] for a in POOL if a.asset[3:] in units}
    return view(turn=3, cash=cash, holdings=holdings, pool=POOL)


def test_weights_round_to_the_nearest_multiple_of_five_by_system_position():
    # 5 percent cash, 42 defender, 33 midfielder, 20 forward
    v = _held(500_000, D=420, M=330, F=200)
    assert snapshot_values(v) == (4_200_000, 3_300_000, 2_000_000, 10_000_000, 500_000)
    assert nearest_formation(v) == (5, 40, 35, 20)


def test_steps_of_five_not_ten():
    v = _held(500_000, D=420, M=330, F=200)
    assert any(s % 10 for s in nearest_formation(v))


def test_a_position_less_asset_counts_as_a_midfielder_and_a_goalkeeper_asset_as_goalkeeper():
    v = _held(0, N=500, G=500)
    assert snapshot_values(v)[:3] == (0, 5_000_000, 0)
    assert nearest_formation(v) == (50, 0, 50, 0)


def test_all_cash_declares_the_largest_goalkeeper_and_a_tiny_weight_keeps_the_minimum_five():
    assert nearest_formation(view(turn=2, cash=10_000_000, pool=POOL)) == (100, 0, 0, 0)
    assert nearest_formation(_held(1, D=1_000)) == (5, 95, 0, 0)


def test_a_tie_goes_to_the_first_formation_in_goalkeeper_defender_midfielder_forward_order():
    # 25 / 12.5 / 12.5 / 50 percent: 10-15 and 15-10 are equally near
    v = _held(2_500_000, D=125, M=125, F=500)
    assert nearest_formation(v) == (25, 10, 15, 50)


def _bot(random_units=()):
    bot = RedeclareBot(0, 1)

    class _Stub:
        def decide(self, v):
            return random_units

    bot._random = _Stub()  # type: ignore[assignment]
    return bot


def test_nothing_is_added_when_the_current_formation_already_is_the_nearest_one():
    v = _held(500_000, D=420, M=330, F=200)
    same = view(
        turn=3,
        cash=500_000,
        holdings=v.holdings,
        pool=POOL,
        formation=Formation(5, 40, 35, 20),
    )
    assert _bot().decide(same) == ()
    other = _bot().decide(v)
    assert other == (FormationUnit(5, 40, 35, 20),)


def test_the_random_bots_own_formation_change_is_never_emitted():
    v = _held(500_000, D=420, M=330, F=200)
    target = FormationUnit(5, 40, 35, 20)
    assert _bot((FormationUnit(20, 30, 30, 20),)).decide(v) == (target,)
    assert _bot((target,)).decide(v) == (target,)
    in_force = view(
        turn=3, cash=500_000, holdings=v.holdings, pool=POOL, formation=Formation(5, 40, 35, 20)
    )
    # the random draw equals the formation in force: it would be refused, so it is dropped
    assert _bot((FormationUnit(5, 40, 35, 20),)).decide(in_force) == ()


def test_the_target_uses_the_weights_after_the_turns_orders_with_fees():
    v = view(turn=3, cash=10_000_000, pool=POOL)
    assert nearest_formation(v) == (100, 0, 0, 0)
    buy = OrderUnit(Order(D.asset, Side.BUY, 500, Position.DEFENDER))
    # 5,000,000 of defender and a fee of 750: 50.004 percent of 9,999,250
    assert snapshot_values(v, (buy,)) == (5_000_000, 0, 0, 9_999_250, 4_999_250)
    out = _bot((buy,)).decide(v)
    assert out == (buy, FormationUnit(50, 50, 0, 0))
    held = view(turn=4, cash=5_000_000, holdings={D.asset: 500}, pool=POOL)
    sell = OrderUnit(Order(D.asset, Side.SELL, 500))
    assert snapshot_values(held, (sell,))[:4] == (0, 0, 0, 9_999_250)


def test_no_change_at_turn_zero_and_the_random_trades_are_kept_in_front():
    v0 = view(turn=0, total=None, cash=100_000_000, pool=POOL)
    assert RedeclareBot(0, 5).decide(v0) == RandomBot(0, 5).decide(v0)
    v = _held(500_000, D=420, M=330, F=200)
    buy = OrderUnit(Order(M.asset, Side.BUY, 1, Position.MIDFIELDER))
    out = _bot((buy,)).decide(v)
    assert out[0] is buy and isinstance(out[-1], FormationUnit) and len(out) == 2


def test_make_bot_reuses_the_random_seed_and_records_its_kind():
    world = synthetic_world(9, assets=14, length=12)
    bot = make_bot("redeclare", 4, 5, world.seed)
    twin = make_bot("random", 4, 5, world.seed)
    assert bot.seed == twin.seed and bot.kind == "redeclare" and bot.version == VERSION


def _orders(run):
    return [(t, [u for u in units if isinstance(u, OrderUnit)]) for t, units in run.units_by_turn]


def test_the_orders_match_the_random_bot_of_the_same_index_and_formation_is_the_only_extra():
    world = synthetic_world(21, assets=14, length=24)
    extra = 0
    for index in range(12):
        random = run_bot(world, make_bot("random", index, 3, world.seed))
        probe = run_bot(world, make_bot("redeclare", index, 3, world.seed))
        assert probe.record["verdicts"]["rejected"] == {}
        assert [(t, o) for t, o in _orders(probe) if o] == [(t, o) for t, o in _orders(random) if o]
        assert probe.record["totals"] == random.record["totals"]
        for turn, units in probe.units_by_turn:
            kinds = [type(u) for u in units]
            assert kinds == sorted(kinds, key=lambda k: k is FormationUnit)
            extra += sum(isinstance(u, FormationUnit) and turn >= 1 for u in units)
    assert extra > 12


def test_every_formation_in_fifty_worlds_is_legal_and_a_real_change():
    seen = 0
    for seed in range(50):
        world = synthetic_world(seed, assets=10, length=12)
        run = run_bot(world, make_bot("redeclare", seed, 3, world.seed))
        seen += _check_formations(world, run)
    assert seen > 100


def test_long_seasons_never_make_the_simulator_refuse_a_formation_change():
    # run_bot raises BotRejection on any refused unit; 100 seasons of 60 turns
    for seed in range(5):
        world = synthetic_world(seed, assets=20, length=60)
        for index in range(20):
            run = run_bot(world, make_bot("redeclare", index, 3, world.seed))
            _check_formations(world, run)
            assert run.record["verdicts"]["rejected"] == {}


def test_a_random_draw_equal_to_the_formation_in_force_no_longer_stops_the_season():
    world = synthetic_world(0, assets=20, length=60)
    run = run_bot(world, make_bot("redeclare", 13, 3, world.seed))
    assert run.record["verdicts"]["rejected"] == {}
    random = run_bot(world, make_bot("random", 13, 3, world.seed))
    assert [x for x in _orders(run) if x[1]] == [x for x in _orders(random) if x[1]]


def _check_formations(world, run):
    seen = 0
    current = world.inputs.settings.start_formation
    for turn, units in run.units_by_turn:
        changes = [u for u in units if isinstance(u, FormationUnit)]
        assert len(changes) <= 1
        for u in changes:
            shares = (u.goalkeeper, u.defender, u.midfielder, u.forward)
            assert turn >= 1 and sum(shares) == 100 and u.goalkeeper >= 5
            assert all(s % 5 == 0 for s in shares)
            assert shares != (
                current.goalkeeper,
                current.defender,
                current.midfielder,
                current.forward,
            )
            current = Formation(*shares)
            seen += 1
    return seen


def test_a_season_replays_through_play_and_is_deterministic():
    world = synthetic_world(21, assets=14, length=24)
    run = run_bot(world, make_bot("redeclare", 2, 3, world.seed))
    assert run.record == run_bot(world, make_bot("redeclare", 2, 3, world.seed)).record
    played = play(world.inputs, parse_decisions(run.decisions))
    assert [plain(t.total) for t in played.season.turns] == run.record["totals"][1:]


@pytest.mark.parametrize("cash", [0, 3_000_000])
def test_it_refuses_nothing_when_the_holdings_are_worthless_only_when_the_total_is_zero(cash):
    v = view(turn=2, cash=cash, pool=POOL)
    if cash:
        assert nearest_formation(v) == (100, 0, 0, 0)
    else:
        with pytest.raises(ValueError):
            nearest_formation(v)


def test_golden_decisions_hash_needs_a_version_bump_to_change():
    # A change here needs `redeclare_bot.VERSION` + 1.
    world = synthetic_world(1, assets=12, length=12)
    run = run_bot(world, make_bot("redeclare", 0, 1, world.seed))
    assert any(isinstance(u, FormationUnit) for _, units in run.units_by_turn for u in units)
    assert (
        run.record["decisionsSha256"]
        == "d2ad8ba4e02149d408dd54bb0de8e2668f9ae56f3fbb3c248b3fb94b334e1930"
    )

"""Rules of the disciplined bot, on hand-built views and on whole seasons."""

import pytest
from bots_support import ScriptedRng, view

from antlab_engine.bots.disciplined_bot import DisciplinedBot, build_slots, build_team
from antlab_engine.bots.rng import BotRng, mix
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.view import ViewAsset
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.decisions import OrderUnit, parse_decisions, play
from antlab_engine.sim.model import Position, Side
from antlab_engine.sim.report import plain

D1, D2, D3 = (ViewAsset(f"KR:D{i}", "KR", Position.DEFENDER) for i in (1, 2, 3))
M1, M2 = (ViewAsset(f"KR:M{i}", "KR", Position.MIDFIELDER) for i in (1, 2))
F1, F2 = (ViewAsset(f"KR:F{i}", "KR", Position.FORWARD_TARGET) for i in (1, 2))
POOL = (D1, D2, D3, M1, M2, F1, F2)
SLOTS = {
    Position.DEFENDER: (D1, D2),
    Position.MIDFIELDER: (M1, M2),
    Position.FORWARD_TARGET: (F1, F2),
}
PRICE = 10_000


def _bot(guard: bool = True) -> DisciplinedBot:
    bot = DisciplinedBot(0, 1, guard=guard)
    bot._slots = dict(SLOTS)
    return bot


def _held(d: int, m: int, f: int) -> dict[str, int]:
    """Quantities per asset from slot values in won (each slot split over its two assets)."""
    out = {}
    for assets, value in (
        (SLOTS[Position.DEFENDER], d),
        (SLOTS[Position.MIDFIELDER], m),
        (SLOTS[Position.FORWARD_TARGET], f),
    ):
        half = value // PRICE // 2
        for a in assets:
            out[a.asset] = half
    return out


def _view(d, m, f, **kw):
    held = _held(d, m, f)
    cash = 100_000_000 - sum(held.values()) * PRICE
    return view(holdings=held, cash=cash, pool=POOL, **kw)


def test_start_team_two_assets_per_slot_and_budget_split_equally():
    v = view(turn=0, total=None, cash=100_000_000, pool=POOL)
    slots = build_slots(v, BotRng(mix(1, 2, 3)))
    for slot, assets in slots.items():
        assert len(assets) == 2
        assert all(a.system_position is slot for a in assets)
    units = build_team(v, slots)
    assert len(units) == 6
    notional = {u.order.asset: u.order.quantity * PRICE for u in units}
    for assets in slots.values():
        a, b = (notional[x.asset] for x in assets)
        assert a == b
        assert 14_900_000 < a <= 15_000_000
    assert sum(notional.values()) <= 90_000_000


def test_a_slot_with_one_asset_holds_that_one_and_an_empty_slot_takes_the_market_pair():
    spy = ViewAsset("US:SPY", "US", Position.FORWARD_TARGET)
    etf = ViewAsset("KR:069500", "KR", Position.MIDFIELDER)
    pool = (D1, M1, etf, spy)
    slots = build_slots(view(turn=0, total=None, pool=pool), BotRng(5))
    assert slots[Position.DEFENDER] == (D1,)
    assert slots[Position.MIDFIELDER] == (M1, etf)
    assert slots[Position.FORWARD_TARGET] == (spy,)
    pool = (D1, D2, spy, etf)
    slots = build_slots(view(turn=0, total=None, pool=pool), BotRng(5))
    assert slots[Position.MIDFIELDER] == (etf,)  # the pair minus what a slot already holds
    assert slots[Position.FORWARD_TARGET] == (spy,)


def test_the_shuffle_is_seeded_and_uses_the_pool_only():
    v = view(turn=0, total=None, pool=POOL)
    first = build_slots(v, BotRng(1))
    assert build_slots(v, BotRng(1)) == first
    seen = {build_slots(v, BotRng(s))[Position.DEFENDER] for s in range(30)}
    assert len(seen) > 1 and all(set(pair) <= {D1, D2, D3} for pair in seen)


def test_a_slot_exactly_five_points_off_does_not_trade_and_one_won_more_does():
    # total 100,000,000; formation 10/30/30/30; defender slot at 35 % exactly
    quiet = _view(35_000_000, 25_000_000, 30_000_000)
    assert _bot().decide(quiet) == ()
    loud = _view(35_010_000, 24_990_000, 30_000_000)
    assert _bot().decide(loud) != ()
    just_inside = _view(34_990_000, 25_010_000, 30_000_000)
    assert _bot().decide(just_inside) == ()


def test_the_goalkeeper_slot_counts_as_a_slot_of_the_band():
    # cash 15 % is exactly +5 points; 15.01 % is not
    inside = _view(28_500_000, 28_500_000, 28_000_000)
    assert inside.cash == 15_000_000
    assert _bot().decide(inside) == ()
    outside = _view(28_500_000, 28_500_000, 27_990_000)
    assert outside.cash > 15_000_000
    units = _bot().decide(outside)
    assert units and all(u.order.side is Side.BUY for u in units)


def test_overweight_slots_are_sold_first_pro_rata_then_underweight_slots_are_bought():
    v = _view(40_000_000, 20_000_000, 30_000_000)
    units = _bot().decide(v)
    sides = [u.order.side for u in units]
    assert sides == sorted(sides, key=lambda s: s is Side.BUY)  # every sell before any buy
    sells = {u.order.asset: u.order.quantity for u in units if u.order.side is Side.SELL}
    assert sells == {"KR:D1": 500, "KR:D2": 500}  # 10 million of excess over two equal holdings
    buys = {u.order.asset: u.order.quantity for u in units if u.order.side is Side.BUY}
    assert set(buys) == {"KR:M1", "KR:M2"}
    assert buys["KR:M1"] == buys["KR:M2"] == 499  # 5 million each, fee included
    assert all(u.order.position is Position.MIDFIELDER for u in units if u.order.side is Side.BUY)


def test_unequal_holdings_inside_a_slot_are_sold_in_proportion():
    held = {
        "KR:D1": 3000,
        "KR:D2": 1000,
        "KR:M1": 1000,
        "KR:M2": 1000,
        "KR:F1": 1500,
        "KR:F2": 1500,
    }
    v = view(holdings=held, cash=100_000_000 - sum(held.values()) * PRICE, pool=POOL)
    units = _bot().decide(v)
    sells = {u.order.asset: u.order.quantity for u in units if u.order.side is Side.SELL}
    assert sells == {"KR:D1": 750, "KR:D2": 250}  # excess 10 million split 3:1


@pytest.mark.parametrize(("now", "guarded"), [(9_200, True), (9_201, False), (8_000, True)])
def test_no_sell_after_a_fall_of_eight_percent_or_more_boundary_included(now, guarded):
    v = _view(
        40_000_000,
        20_000_000,
        30_000_000,
        prices={"KR:D1": now},
        prev_prices={"KR:D1": 10_000},
    )
    sold = {u.order.asset for u in _bot().decide(v) if u.order.side is Side.SELL}
    assert ("KR:D1" in sold) is not guarded
    assert "KR:D2" in sold


@pytest.mark.parametrize(("now", "guarded"), [(10_800, True), (10_799, False), (12_000, True)])
def test_no_buy_after_a_rise_of_eight_percent_or_more_boundary_included(now, guarded):
    v = _view(
        40_000_000,
        20_000_000,
        30_000_000,
        prices={"KR:M1": now},
        prev_prices={"KR:M1": 10_000},
    )
    bought = {u.order.asset for u in _bot().decide(v) if u.order.side is Side.BUY}
    assert ("KR:M1" in bought) is not guarded
    assert "KR:M2" in bought


def test_without_the_guard_the_same_orders_go_out():
    v = _view(
        40_000_000,
        20_000_000,
        30_000_000,
        prices={"KR:D1": 9_000, "KR:M1": 11_000},
        prev_prices={"KR:D1": 10_000, "KR:M1": 10_000},
    )
    guarded = {(u.order.side, u.order.asset) for u in _bot().decide(v)}
    free = {(u.order.side, u.order.asset) for u in _bot(guard=False).decide(v)}
    assert (Side.SELL, "KR:D1") in free and (Side.SELL, "KR:D1") not in guarded
    assert (Side.BUY, "KR:M1") in free and (Side.BUY, "KR:M1") not in guarded


def test_buys_never_spend_more_than_the_cash_on_hand_plus_the_sells():
    v = _view(30_000_000, 30_000_000, 30_000_000)
    assert v.cash == 10_000_000
    drained = view(holdings=_held(34_000_000, 20_000_000, 36_000_000), cash=10_000_000, pool=POOL)
    units = _bot().decide(drained)
    spent = 0
    for u in units:
        o = u.order
        value = o.quantity * PRICE
        spent += value if o.side is Side.BUY else -value
    assert spent <= 10_000_000


def _world_run(world, kind="disciplined", index=0):
    return run_bot(world, make_bot(kind, index, 3, world.seed))


def test_seasons_are_legal_deterministic_and_replay_through_play():
    world = synthetic_world(21, assets=14, length=24, feed=True)
    run = _world_run(world)
    assert run.record["verdicts"]["rejected"] == {}
    assert run.record == _world_run(world).record
    played = play(world.inputs, parse_decisions(run.decisions))
    assert played.season.status.value == run.record["status"]
    assert [plain(t.total) for t in played.season.turns] == run.record["totals"][1:]


def test_the_season_trades_in_the_start_turn_and_sometimes_afterwards():
    traded = 0
    for seed in range(6):
        world = synthetic_world(seed, assets=14, length=24, feed=True)
        run = _world_run(world)
        turns = [t for t, _ in run.units_by_turn]
        assert turns[0] == 0
        traded += len(turns) - 1
        for _, units in run.units_by_turn[1:]:
            assert all(isinstance(u, OrderUnit) for u in units)
            sells = [u.order.side is Side.SELL for u in units]
            assert sells == sorted(sells, reverse=True)
    assert traded > 0


def test_the_guard_is_a_recorded_parameter_and_changes_the_run():
    world = synthetic_world(8, assets=14, length=60, feed=True)
    on = _world_run(world)
    off = _world_run(world, "disciplined-noguard")
    assert on.record["bot"]["params"]["guard"] is True
    assert off.record["bot"]["params"]["guard"] is False
    assert on.record["decisionsSha256"] != off.record["decisionsSha256"]


def test_scripted_stream_is_consumed_in_slot_order():
    rng = ScriptedRng(below=[2, 0, 1, 0, 0, 0])
    slots = build_slots(view(turn=0, total=None, pool=POOL), rng)
    assert [a.asset for a in slots[Position.DEFENDER]] == ["KR:D3", "KR:D2"]
    assert not rng.below_q


GOLDEN = "289bf8f7daf5e076d49f1e5814ae316c9a2d2d9fd07c744ace4d4a8f5f49ebcf"


def test_golden_decisions_hash_needs_a_version_bump_to_change():
    # A change here needs `disciplined_bot.VERSION` + 1.
    world = synthetic_world(1, assets=12, length=60, feed=True)
    run = run_bot(world, make_bot("disciplined", 0, 1, world.seed))
    assert len(run.units_by_turn) > 1  # the pin covers rebalances, not only the start team
    assert run.record["decisionsSha256"] == GOLDEN


def test_the_market_pair_never_sits_in_two_slots():
    etf = ViewAsset("KR:069500", "KR", Position.MIDFIELDER)
    spy = ViewAsset("US:SPY", "US", Position.GOALKEEPER)
    pool = (etf, M1, F1, spy)
    slots = build_slots(view(turn=0, total=None, pool=pool), BotRng(3))
    assert slots[Position.MIDFIELDER] == (etf, M1) or slots[Position.MIDFIELDER] == (M1, etf)
    assert slots[Position.DEFENDER] == (spy,)  # the pair minus the ETF the midfield slot holds
    held = [a.asset for assets in slots.values() for a in assets]
    assert len(held) == len(set(held))

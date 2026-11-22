"""Discipline and noise reaction, replayed from the Java hand fixtures.

The golden fixture is `DisciplineCalculatorTest` (T = 6, three assets, team values around the
8 % line); the expected numbers are copied from there.
"""

from dataclasses import replace
from decimal import Decimal

import pytest

from antlab_engine.sim import behaviour as b
from antlab_engine.sim import report
from antlab_engine.sim.model import Side, SimReportError

A, B, C = "KR:AAA001", "US:BBB002", "KR:CCC003"
GAINER, LOSER, PLAIN, TOP = "KR:G", "KR:L", "KR:P", "KR:T"


def won(v: int) -> Decimal:
    return Decimal(v).quantize(Decimal("0.0001"))


def order(oid, turn, side, asset, notional, change, price, previous) -> b.DisciplineOrder:
    return b.DisciplineOrder(turn, side, asset, won(notional), change, won(price), won(previous))


def values(*totals: int) -> tuple[b.TurnValue, ...]:
    return tuple(b.TurnValue(i + 1, won(v)) for i, v in enumerate(totals))


def changes() -> tuple[b.DisciplineChange, ...]:
    return (
        b.DisciplineChange(1, 2, 30, 20),
        b.DisciplineChange(2, 3, 20, 10),
        b.DisciplineChange(3, 4, 10, 5),
        b.DisciplineChange(4, 4, 5, 40),
        b.DisciplineChange(5, 5, 40, 30),
        b.DisciplineChange(9, 0, 30, 30),
        b.DisciplineChange(10, 6, 30, 30),
    )


BUY, SELL = Side.BUY, Side.SELL


def orders() -> tuple[b.DisciplineOrder, ...]:
    return (
        order(1, 1, BUY, A, 1_000_000, None, 108, 100),
        order(2, 1, BUY, B, 2_000_000, None, 10_799, 10_000),
        order(3, 2, SELL, A, 3_000_000, None, 92, 100),
        order(4, 2, SELL, B, 1_500_000, None, 9_201, 10_000),
        order(5, 2, SELL, C, 500_000, 1, 105, 100),
        order(6, 3, SELL, A, 700_000, 2, 100, 100),
        order(7, 3, BUY, C, 400_000, None, 112, 100),
        order(8, 4, SELL, B, 600_000, None, 9_000, 10_000),
        order(9, 4, SELL, C, 250_000, None, 100, 100),
        order(10, 4, SELL, A, 150_000, 3, 100, 100),
        order(11, 5, BUY, A, 800_000, None, 130, 100),
        order(12, 5, BUY, B, 900_000, None, 9_500, 10_000),
        order(13, 0, BUY, A, 50_000_000, None, 200, 100),
    )


def golden(**kw) -> b.DisciplineFacts:
    base = b.DisciplineFacts(
        6,
        won(30_000_000),
        values(30_300_000, 27_876_000, 25_645_921, 23_594_247, 24_000_000, 24_100_000),
        changes(),
        orders(),
    )
    return replace(base, **kw)


def plain(d: Decimal | None) -> str | None:
    return None if d is None else report.plain(d)


def test_golden_fixture_matches_the_hand_computation():
    r = b.discipline(golden())
    assert plain(r.sell_notional) == "6700000"
    assert plain(r.panic_sell_notional) == "4100000"
    assert plain(r.panic_sell_share) == "0.61194030"
    assert plain(r.buy_notional) == "5100000"
    assert plain(r.chase_buy_notional) == "2200000"
    assert plain(r.chase_buy_share) == "0.43137255"
    assert plain(r.trade_notional) == "11800000"
    assert plain(r.undisciplined_share) == "0.53389831"
    assert (r.formation_changes, r.panic_formation_changes) == (5, 1)


def test_input_order_of_changes_and_orders_does_not_matter():
    g = golden()
    flipped = golden(changes=tuple(reversed(g.changes)), orders=tuple(reversed(g.orders)))
    assert b.discipline(flipped) == b.discipline(g)


def test_asset_boundary_is_inclusive_and_one_won_above_is_not():
    hundred = won(100)
    assert b.dropped(hundred, won(92)) and not b.dropped(hundred, won(93))
    assert b.rose(hundred, won(108)) and not b.rose(hundred, won(107))
    # a non-positive previous value can neither fall nor rise
    assert not b.dropped(won(0), won(0)) and not b.rose(won(0), won(0))


def test_the_team_boundary_is_minus_eight_percent_exactly():
    g = golden()
    shifted = replace(
        g,
        turn_values=values(30_300_000, 27_876_001, 25_645_921, 23_594_247, 24_000_000, 24_100_000),
    )
    r = b.discipline(shifted)
    assert r.panic_formation_changes == 0 and plain(r.panic_sell_notional) == "3600000"
    fallen = replace(
        g,
        turn_values=values(30_300_000, 27_876_000, 25_645_920, 23_594_247, 24_000_000, 24_100_000),
    )
    f = b.discipline(fallen)
    assert f.panic_formation_changes == 2 and plain(f.panic_sell_notional) == "4800000"


def test_a_drop_turn_needs_a_lower_forward_target_to_be_panic():
    g = golden()
    raised = (b.DisciplineChange(1, 2, 20, 30), *g.changes[1:])
    r = b.discipline(replace(g, changes=raised))
    assert r.panic_formation_changes == 0 and plain(r.panic_sell_notional) == "3600000"
    same = (b.DisciplineChange(1, 2, 20, 20), *g.changes[1:])
    assert b.discipline(replace(g, changes=same)).panic_formation_changes == 0


def test_several_changes_in_a_turn_are_judged_first_before_to_last_after():
    g = golden()
    swapped = list(g.changes)
    swapped[2:4] = [b.DisciplineChange(3, 4, 10, 40), b.DisciplineChange(4, 4, 40, 5)]
    r = b.discipline(replace(g, changes=tuple(swapped)))
    assert r.panic_formation_changes == 3 and plain(r.panic_sell_notional) == "4250000"
    ids_swapped = list(g.changes)
    ids_swapped[2:4] = [b.DisciplineChange(4, 4, 10, 40), b.DisciplineChange(3, 4, 40, 5)]
    assert b.discipline(replace(g, changes=tuple(ids_swapped))).panic_formation_changes == 1
    up_then_down = list(g.changes)
    up_then_down[2:4] = [b.DisciplineChange(3, 4, 30, 50), b.DisciplineChange(4, 4, 50, 40)]
    assert b.discipline(replace(g, changes=tuple(up_then_down))).panic_formation_changes == 1


def test_the_asset_not_the_team_decides_a_sell():
    g = golden()
    assert plain(b.discipline(g).panic_sell_notional) == "4100000"
    moved = list(g.orders)
    moved[8] = order(9, 4, SELL, C, 250_000, None, 92, 100)
    assert plain(b.discipline(replace(g, orders=tuple(moved))).panic_sell_notional) == "4350000"


def test_turn_zero_orders_are_never_judged_and_empty_sides_have_no_share():
    g = golden()
    zero = (
        order(1, 0, BUY, A, 50_000_000, None, 200, 100),
        b.DisciplineOrder(0, SELL, B, won(1_000_000), None, None, None),
    )
    r = b.discipline(replace(g, orders=zero))
    assert plain(r.trade_notional) == "0"
    assert r.panic_sell_share is None and r.chase_buy_share is None
    assert r.undisciplined_share is None
    buys = tuple(o for o in g.orders if o.side is BUY)
    r = b.discipline(replace(g, orders=buys))
    assert r.panic_sell_share is None and plain(r.chase_buy_share) == "0.43137255"
    assert plain(r.undisciplined_share) == "0.43137255"
    sells = tuple(o for o in g.orders if o.side is SELL)
    r = b.discipline(replace(g, orders=sells))
    assert r.chase_buy_share is None and plain(r.panic_sell_share) == "0.61194030"


def test_contradicting_discipline_facts_are_refused_with_a_fixed_error():
    g = golden()
    bad_orders = {
        "amount zero": order(1, 1, BUY, A, 0, None, 100, 100),
        "amount negative": order(1, 1, BUY, A, -5, None, 100, 100),
        "no price": b.DisciplineOrder(1, BUY, A, won(10), None, None, None),
        "non-positive price": order(1, 1, BUY, A, 10, None, 0, 100),
        "bound to a missing change": order(1, 1, SELL, A, 10, 99, 100, 100),
        "bound to another turn": order(1, 1, SELL, A, 10, 1, 100, 100),
        "outside the range": order(1, 6, BUY, A, 10, None, 100, 100),
        "negative turn": order(1, -1, BUY, A, 10, None, 100, 100),
    }
    for _name, bad in bad_orders.items():
        with pytest.raises(SimReportError):
            b.discipline(replace(g, orders=(bad,)))
    fractional = b.DisciplineOrder(1, BUY, A, Decimal("10.5"), None, won(100), won(100))
    with pytest.raises(SimReportError):
        b.discipline(replace(g, orders=(fractional,)))
    with pytest.raises(SimReportError):
        b.discipline(replace(g, report_turn=0))
    with pytest.raises(SimReportError):
        b.discipline(replace(g, initial_cash=won(0)))
    with pytest.raises(SimReportError):
        b.discipline(replace(g, turn_values=g.turn_values[:5]))
    with pytest.raises(SimReportError):
        b.discipline(replace(g, turn_values=(b.TurnValue(2, won(1)), *g.turn_values[1:])))
    with pytest.raises(SimReportError):
        b.discipline(replace(g, turn_values=(b.TurnValue(1, won(-1)), *g.turn_values[1:])))


# ---------------------------------------------------------------- noise


def signals(turn, up=(), down=(), top=()) -> b.TurnSignals:
    return b.TurnSignals(turn, frozenset(up), frozenset(down), frozenset(top))


def noise_order(turn, side, asset, notional) -> b.NoiseOrder:
    return b.NoiseOrder(turn, side, asset, Decimal(notional))


def test_the_season_value_is_the_mean_of_trading_turns():
    sig = [signals(t, {GAINER}, {LOSER}) for t in (1, 2, 3)]
    orders = [
        noise_order(1, BUY, GAINER, 600),
        noise_order(1, BUY, PLAIN, 400),
        noise_order(2, SELL, LOSER, 300),
        noise_order(2, SELL, PLAIN, 100),
    ]
    r = b.noise(4, sig, orders)
    assert plain(r.noise_share) == "0.67500000"  # (0.6 + 0.75) / 2, turn 3 does not dilute
    assert r.trading_turns == 2
    assert plain(r.followed_buy_notional) == "600" and plain(r.followed_sell_notional) == "300"
    assert plain(r.trade_notional) == "1400"


def test_wrong_side_and_top_attention_do_not_follow():
    sig = [signals(1, {GAINER}, {LOSER}, {TOP})]
    orders = [
        noise_order(1, BUY, LOSER, 100),
        noise_order(1, SELL, GAINER, 100),
        noise_order(1, SELL, TOP, 100),
        noise_order(1, BUY, TOP, 100),
    ]
    r = b.noise(2, sig, orders)
    assert plain(r.noise_share) == "0.00000000" and r.trading_turns == 1


def test_an_asset_in_both_sets_counts_its_buy_and_its_sell():
    r = b.noise(
        2,
        [signals(1, {GAINER}, {GAINER})],
        [noise_order(1, BUY, GAINER, 100), noise_order(1, SELL, GAINER, 300)],
    )
    assert plain(r.noise_share) == "1.00000000"


def test_the_mean_is_rounded_once_at_the_end():
    sig = [signals(t, {GAINER}) for t in (1, 2, 3)]
    orders = [
        noise_order(1, BUY, GAINER, 2),
        noise_order(1, BUY, PLAIN, 1),
        noise_order(2, BUY, GAINER, 1),
        noise_order(2, BUY, PLAIN, 2),
        noise_order(3, BUY, GAINER, 1),
        noise_order(3, BUY, PLAIN, 6),
    ]
    assert plain(b.noise(4, sig, orders).noise_share) == "0.38095238"  # 8 / 21


def test_no_trade_in_the_judged_turns_gives_none_and_zero_amounts():
    r = b.noise(3, [signals(1), signals(2)], [])
    assert r.noise_share is None and r.trading_turns == 0
    assert plain(r.trade_notional) == "0"


def test_noise_of_season_drops_the_team_purchase_before_judging():
    r = b.noise_of_season(
        3,
        [signals(1, {GAINER}), signals(2)],
        [noise_order(0, BUY, GAINER, 999), noise_order(1, BUY, GAINER, 10)],
    )
    assert plain(r.trade_notional) == "10" and plain(r.noise_share) == "1.00000000"
    with pytest.raises(SimReportError):
        b.noise(3, [signals(1)], [noise_order(0, BUY, GAINER, 10)])


def test_noise_refuses_orders_and_signals_outside_the_range():
    sig = [signals(1), signals(2)]
    for bad in (noise_order(3, BUY, GAINER, 100), noise_order(1, BUY, GAINER, 0)):
        with pytest.raises(SimReportError):
            b.noise(3, sig, [bad])
    with pytest.raises(SimReportError):
        b.noise(3, sig, [b.NoiseOrder(1, BUY, GAINER, Decimal("100.5"))])
    with pytest.raises(SimReportError):
        b.noise(3, [signals(1)], [noise_order(2, BUY, GAINER, 100)])
    with pytest.raises(SimReportError):
        b.noise(2, [signals(2)], [])
    with pytest.raises(SimReportError):
        b.noise(3, [signals(1), signals(1)], [])
    with pytest.raises(SimReportError):
        b.noise(0, [], [])
    with pytest.raises(SimReportError):
        b.TurnSignals(0, frozenset(), frozenset())


def test_discipline_checks_run_in_the_java_order():
    g = golden()
    bad_order = order(1, 1, BUY, A, 0, None, 100, 100)
    chain = [
        (replace(g, report_turn=0, initial_cash=won(0)), "report turn is out of range"),
        (replace(g, initial_cash=won(0), turn_values=g.turn_values[:2]), "initial cash"),
        (
            replace(g, turn_values=g.turn_values[:2], orders=(order(1, 6, BUY, A, 5, None, 1, 1),)),
            "do not run from 1",
        ),
        (
            replace(g, orders=(bad_order, order(2, 6, BUY, A, 5, None, 1, 1))),
            "outside the report range",
        ),
        (
            replace(g, orders=(bad_order, order(2, 1, SELL, A, 5, 99, 0, 1))),
            "not positive whole won",
        ),
        (replace(g, orders=(order(2, 1, SELL, A, 5, 99, 0, 1),)), "positive turn price"),
        (replace(g, orders=(order(2, 1, SELL, A, 5, 99, 1, 1),)), "missing or other-turn"),
    ]
    for facts, message in chain:
        with pytest.raises(SimReportError, match=message):
            b.discipline(facts)


def test_noise_checks_run_in_the_java_order():
    sig = [signals(1), signals(2)]
    chain = [
        ((0, sig, [noise_order(9, BUY, GAINER, 0)]), "report turn is out of range"),
        ((3, [signals(3), signals(3)], [noise_order(9, BUY, GAINER, 0)]), "repeat"),
        ((3, sig, [noise_order(9, BUY, GAINER, 0)]), "outside the report range"),
        ((3, sig, [noise_order(1, BUY, GAINER, 0)]), "not positive whole won"),
        ((3, [signals(1)], [noise_order(2, BUY, GAINER, 5)]), "no signals"),
    ]
    for args, message in chain:
        with pytest.raises(SimReportError, match=message):
            b.noise(*args)


def test_discipline_amounts_beyond_the_int_range_stay_exact():
    g = golden()
    huge = Decimal(10) ** 20
    big = (
        b.DisciplineOrder(1, BUY, A, huge, None, won(108), won(100)),
        b.DisciplineOrder(2, SELL, A, huge, None, won(92), won(100)),
    )
    r = b.discipline(replace(g, orders=big))
    assert plain(r.panic_sell_share) == "1.00000000" and plain(r.chase_buy_share) == "1.00000000"
    assert plain(r.undisciplined_share) == "1.00000000"

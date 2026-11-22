"""Season rules against hand values and the Java check order (OrderService, TurnService)."""

import random
from collections import Counter
from dataclasses import replace
from decimal import Decimal

import pytest
from sim_support import KR_A, KR_B, US_X, inputs, pool

from antlab_engine.sim import money
from antlab_engine.sim.model import (
    LineupSpan,
    Order,
    OrderSource,
    PathStep,
    PoolAsset,
    Position,
    SeasonStatus,
    Settings,
    Side,
    SimInputError,
    SimInputs,
    SimInvariantError,
    SimShapeError,
    SimStateError,
    SourceKind,
)
from antlab_engine.sim.season import Season

D = Decimal


def buy(asset, quantity, position=Position.DEFENDER, source=None):
    return Order(asset, Side.BUY, quantity, position, source or OrderSource())


def sell(asset, quantity):
    return Order(asset, Side.SELL, quantity)


def test_turn_zero_prices_are_initial_prices():
    season = Season(inputs())
    assert season.turn_prices[0] == {KR_A: D(10000), KR_B: D(2501), US_X: D(130000)}


def test_incremental_prices_equal_the_kernel_with_one_rounding():
    rng = random.Random(7)  # noqa: S311
    steps = tuple(
        PathStep(
            t,
            {a.asset: D(rng.randint(-3000, 3000)) / D(10000) for a in pool()},
            {"USD": D(rng.randint(-500, 500)) / D(10000)},
        )
        for t in range(1, 25)
    )
    season = Season(SimInputs(Settings(path_length=24), pool(), steps))
    for _ in range(24):
        season.advance()
    for a in pool():
        initial = money.initial_price(a.base_price, a.base_fx_rate)
        for t in range(1, 25):
            returns = [
                (
                    steps[k].local_returns[a.asset],
                    D(0) if a.currency == "KRW" else steps[k].fx_change["USD"],
                )
                for k in range(t)
            ]
            assert season.turn_prices[t][a.asset] == money.price_at(initial, returns)


def test_buy_posts_trade_then_fee_and_opens_a_span():
    season = Season(inputs())
    verdict = season.place_order(buy(KR_A, 120))
    assert verdict.accepted
    # 10000 * 120 = 1,200,000; KR fee 0.015% = 180
    assert [(r.type.value, r.amount) for r in season.ledger] == [
        ("DEPOSIT", D(100_000_000)),
        ("BUY", D(-1_200_000)),
        ("FEE", D(-180)),
    ]
    assert season.cash == D(98_799_820)
    assert season.holdings[KR_A] == (120, D("10000.0000"))
    span = season.spans[0]
    assert (span.asset, span.position, span.start_turn, span.end_turn) == (
        KR_A,
        Position.DEFENDER,
        0,
        None,
    )


def test_us_fee_adds_the_fx_spread():
    season = Season(inputs())
    season.place_order(buy(US_X, 10, Position.MIDFIELDER))
    # 130000 * 10 * (0.25% + 0.40%) = 8450
    assert season.executions[0].fee == D(8450)


def test_fee_ties_round_half_up():
    season = Season(inputs(fee_rate_kr=D("0.00005000")))
    season.place_order(buy(KR_A, 1))
    assert season.executions[0].fee == D(1)  # 10000 * 0.00005 = 0.5


def test_zero_fee_posts_no_fee_row():
    season = Season(inputs(fee_rate_kr=D("0.00000000")))
    season.place_order(buy(KR_A, 1))
    assert [r.type.value for r in season.ledger] == ["DEPOSIT", "BUY"]


def test_fractional_and_out_of_range_quantities_are_shape_errors():
    with pytest.raises(SimShapeError):
        Order(KR_A, Side.BUY, 1.5, Position.DEFENDER)
    with pytest.raises(SimShapeError):
        Order(KR_A, Side.BUY, D("2"), Position.DEFENDER)
    with pytest.raises(SimShapeError):
        Order(KR_A, Side.BUY, 2**31, Position.DEFENDER)
    with pytest.raises(SimShapeError):
        Order(KR_A, Side.SELL, 1, Position.DEFENDER)
    with pytest.raises(SimShapeError):
        OrderSource(SourceKind.FEED, 1, None)
    with pytest.raises(SimShapeError):
        OrderSource(SourceKind.DIRECT, 1, 1)


def test_check_order_source_then_pool_then_quantity():
    season = Season(inputs())
    bench = Order("KR:NOPE", Side.BUY, 0, Position.DEFENDER, OrderSource(SourceKind.BENCH))
    assert season.place_order(bench).code == "SOURCE_NOT_ALLOWED"
    assert season.place_order(buy("KR:NOPE", 0)).code == "ASSET_NOT_IN_POOL"
    assert season.place_order(buy(KR_A, 0)).code == "QUANTITY_INVALID"
    assert season.place_order(buy(KR_A, -3)).code == "QUANTITY_INVALID"
    assert season.executions == []


def test_labelled_sources_are_accepted_and_recorded():
    season = Season(inputs())
    for _ in range(3):
        season.advance()
    source = OrderSource(SourceKind.FEED, 3, 2)
    assert season.place_order(buy(KR_A, 1, source=source)).accepted
    assert season.executions[0].source == source


def test_cash_is_checked_before_and_with_the_fee():
    season = Season(inputs(initial_cash=D(1_000_000)))
    assert season.place_order(buy(KR_A, 101)).code == "INSUFFICIENT_CASH"
    # notional alone fits (100 * 10000 = 1,000,000) but not with the 150 fee
    assert season.place_order(buy(KR_A, 100)).code == "INSUFFICIENT_CASH"
    assert season.place_order(buy(KR_A, 99)).accepted
    assert season.place_order(buy(KR_A, 2**31 - 1)).code == "INSUFFICIENT_CASH"


def test_position_required_and_conflict():
    season = Season(inputs())
    assert season.place_order(buy(KR_A, 1, position=None)).code == "POSITION_REQUIRED"
    season.place_order(buy(KR_A, 1, Position.DEFENDER))
    assert season.place_order(buy(KR_A, 1, Position.MIDFIELDER)).code == "POSITION_CONFLICT"
    assert season.place_order(buy(KR_A, 1, position=None)).accepted
    assert season.place_order(buy(KR_A, 1, Position.DEFENDER)).accepted
    assert season.holdings[KR_A][0] == 3


def test_average_price_blends_buys_at_scale_four():
    season = Season(inputs())
    season.place_order(buy(KR_A, 3))
    season.advance()  # KR_A +10% -> 11000
    season.place_order(buy(KR_A, 1, position=None))
    # (10000 * 3 + 11000) / 4 = 10250
    assert season.holdings[KR_A] == (4, D("10250.0000"))


def test_sell_books_pnl_before_changing_the_holding():
    season = Season(inputs())
    season.place_order(buy(KR_A, 10))
    season.advance()
    verdict = season.place_order(sell(KR_A, 4))
    execution = verdict.execution
    assert execution.realized_pnl == D("4000.0000")  # (11000 - 10000) * 4
    assert execution.notional == D(44_000) and execution.fee == D(7)  # 6.6 rounds up
    assert season.holdings[KR_A] == (6, D("10000.0000"))
    assert season.spans[0].end_turn is None
    season.place_order(sell(KR_A, 6))
    assert season.holdings[KR_A] == (0, D("0.0000"))
    assert season.spans[0].end_turn == 1
    assert season.ledger[-2].type.value == "SELL" and season.ledger[-1].type.value == "FEE"


def test_sell_refusals_and_turn_zero_sell():
    season = Season(inputs())
    assert season.place_order(sell(KR_A, 1)).code == "INSUFFICIENT_HOLDING"
    season.place_order(buy(KR_A, 5))
    assert season.place_order(sell(KR_A, 6)).code == "INSUFFICIENT_HOLDING"
    assert season.place_order(sell(KR_A, 5)).accepted  # turn 0 sells are allowed
    assert season.spans[0].end_turn == 0
    again = season.place_order(buy(KR_A, 1, Position.MIDFIELDER))
    assert again.accepted and season.spans[1].position is Position.MIDFIELDER


def test_single_asset_limit_allows_exactly_on_the_limit():
    # zero fee: 5000 shares are exactly 50% of 100,000,000
    tie = Season(inputs(single_asset_limit=D("0.5"), fee_rate_kr=D("0")))
    assert tie.place_order(buy(KR_A, 5000)).accepted
    assert tie.place_order(buy(KR_A, 1)).code == "SINGLE_ASSET_LIMIT"
    other = Season(inputs(single_asset_limit=D("0.5"), fee_rate_kr=D("0")))
    other.place_order(buy(KR_B, 1000))
    assert other.place_order(buy(KR_A, 4000)).accepted  # 40M of 100M minus nothing lost


def test_advance_snapshot_hand_values():
    season = Season(inputs())
    season.place_order(buy(KR_A, 100, Position.DEFENDER))
    season.place_order(buy(KR_B, 200, Position.FORWARD_TARGET))
    season.place_order(buy(US_X, 1, Position.GOALKEEPER))
    snap = season.advance()
    prices = season.turn_prices[1]
    assert prices[KR_A] == D(11000) and prices[KR_B] == D(2376)  # 2501 * 0.95 = 2375.95
    assert prices[US_X] == D(133926)
    assert snap.cash == D(98_368_730)
    assert snap.holdings_value == D(1_709_126)
    assert snap.total == D(100_077_856)
    assert (snap.defender, snap.midfielder, snap.forward) == (D(1_100_000), D(0), D(475_200))
    assert snap.goalkeeper == D(98_502_656)
    assert snap.drawdown == D("0.00000000")
    assert season.turns == [snap] and season.current_turn == 1


def test_peak_includes_initial_cash_so_a_first_turn_loss_is_a_drawdown():
    season = Season(inputs(returns={KR_A: D("-0.10")}))
    season.place_order(buy(KR_A, 9000))
    snap = season.advance()
    # cash 100M - 90M - 13,500 fee; holdings 9000 * 9000 = 81M
    assert snap.total == D(90_986_500)
    assert snap.drawdown == D("-0.09013500")
    later = Season(inputs(returns={KR_A: D("0.10")}))
    later.place_order(buy(KR_A, 9000))
    rising = later.advance()
    assert rising.drawdown == D("0.00000000")


def test_goalkeeper_declared_holdings_stay_in_the_residual():
    season = Season(inputs())
    season.place_order(buy(KR_A, 10, Position.GOALKEEPER))
    snap = season.advance()
    assert (snap.defender, snap.midfielder, snap.forward) == (D(0), D(0), D(0))
    assert snap.goalkeeper == snap.total


def test_span_started_at_turn_t_counts_at_snapshot_t_plus_1():
    season = Season(inputs())
    season.advance()
    season.place_order(buy(KR_A, 10, Position.MIDFIELDER))  # span starts at turn 1
    snap = season.advance()
    assert snap.midfielder == D(10 * 12100)


def test_full_season_finishes_on_the_last_advance_and_refuses_orders():
    season = Season(inputs(length=12))
    for turn in range(1, 12):
        season.advance()
        assert season.status is SeasonStatus.IN_PROGRESS and season.current_turn == turn
    season.advance()
    assert season.status is SeasonStatus.FINISHED and season.current_turn == 12
    assert len(season.turns) == 12 and len(season.turn_prices) == 13
    with pytest.raises(SimStateError):
        season.place_order(buy(KR_A, 1))
    with pytest.raises(SimStateError):
        season.advance()


def test_non_positive_price_and_missing_data_refuse_the_season():
    season = Season(inputs(returns={KR_A: D("-1")}))
    with pytest.raises(SimInputError):
        season.advance()
    steps = (PathStep(1, {KR_A: D("0.1"), KR_B: None, US_X: D("0.1")}, {"USD": D("0")}),)
    with pytest.raises(SimInputError):
        SimInputs(Settings(path_length=12), pool(), steps)


def test_invariant_checks_catch_a_tampered_state():
    season = Season(inputs())
    season.place_order(buy(KR_A, 10))
    season.check_invariants()
    season.holdings[KR_A] = (11, season.holdings[KR_A][1])
    with pytest.raises(SimInvariantError):
        season.check_invariants()
    season.holdings[KR_A] = (10, season.holdings[KR_A][1])
    season.cash += 1
    with pytest.raises(SimInvariantError):
        season.check_invariants()


def play_random(seed: int, length: int, codes: Counter | None = None) -> Season:
    rng = random.Random(seed)  # noqa: S311
    season = Season(inputs(length=length, single_asset_limit=D("0.6")), strict=True)
    assets = [KR_A, KR_B, US_X, "KR:NOPE"]
    positions = list(Position)
    for _ in range(length):
        for _ in range(rng.randint(0, 6)):
            asset = rng.choice(assets)
            quantity = rng.randint(-1, 800)
            if rng.random() < 0.55:
                position = rng.choice([*positions, None])
                verdict = season.place_order(buy(asset, quantity, position))
            else:
                if rng.random() < 0.4 and asset in season.holdings:
                    quantity = season.holdings[asset][0] or quantity
                verdict = season.place_order(sell(asset, quantity))
            if codes is not None:
                codes[verdict.code] += 1
        season.advance()
    return season


@pytest.mark.parametrize("seed", range(25))
def test_random_streams_keep_every_identity(seed):
    season = play_random(seed, (12, 24, 60)[seed % 3])
    assert season.status is SeasonStatus.FINISHED
    season.check_invariants()
    for e in season.executions:
        assert e.quantity >= 1


def test_same_inputs_give_the_same_run():
    first, second = play_random(3, 24), play_random(3, 24)
    assert first.ledger == second.ledger
    assert first.executions == second.executions
    assert first.turns == second.turns


def test_random_streams_reach_every_branch():
    codes: Counter = Counter()
    closed = reopened = 0
    for seed in range(25):
        season = play_random(seed, (12, 24, 60)[seed % 3], codes)
        closed += sum(1 for sp in season.spans if sp.end_turn is not None)
        seen = [sp.asset for sp in season.spans]
        reopened += len(seen) - len(set(seen))
    assert closed > 20 and reopened > 5
    for code in (
        "ACCEPTED",
        "ASSET_NOT_IN_POOL",
        "QUANTITY_INVALID",
        "INSUFFICIENT_HOLDING",
        "INSUFFICIENT_CASH",
        "POSITION_REQUIRED",
        "POSITION_CONFLICT",
        "SINGLE_ASSET_LIMIT",
    ):
        assert codes[code] > 0, code


def big_inputs() -> SimInputs:
    big = PoolAsset("KR:BIG", "KRW", D(1_000_000), D(1))
    steps = tuple(
        PathStep(t, {**s.local_returns, "KR:BIG": D(0)}, s.fx_change)
        for t, s in enumerate(inputs().path, 1)
    )
    return SimInputs(Settings(), (*pool(), big), steps)


def test_cash_screen_answers_insufficient_cash_where_the_fee_would_overflow():
    season = Season(big_inputs())
    verdict = season.place_order(buy("KR:BIG", 2**31 - 1))
    assert verdict.code == "INSUFFICIENT_CASH"


def test_buy_check_order_cash_then_position_then_limit():
    poor = Season(inputs(initial_cash=D(1000)))
    assert poor.place_order(buy(KR_A, 1, position=None)).code == "INSUFFICIENT_CASH"
    both = Season(inputs(single_asset_limit=D("0.3"), fee_rate_kr=D(0)))
    both.place_order(buy(KR_A, 1, Position.DEFENDER))
    over = buy(KR_A, 9000, Position.MIDFIELDER)  # also breaks the 30% limit
    assert both.place_order(over).code == "POSITION_CONFLICT"
    assert both.place_order(buy(KR_A, 9000, Position.DEFENDER)).code == "SINGLE_ASSET_LIMIT"


def test_sell_with_zero_quantity_is_quantity_invalid_before_holding():
    season = Season(inputs())
    assert season.place_order(sell(KR_A, 0)).code == "QUANTITY_INVALID"
    assert season.place_order(sell(KR_A, -1)).code == "QUANTITY_INVALID"


def test_single_asset_limit_counts_the_spend_the_fee_and_other_holdings():
    spend = Season(inputs(single_asset_limit=D("0.5"), fee_rate_kr=D(0)))
    assert spend.place_order(buy(KR_A, 5001)).code == "SINGLE_ASSET_LIMIT"
    other = Season(inputs(single_asset_limit=D("0.6"), fee_rate_kr=D(0)))
    other.place_order(buy(KR_B, 20000))
    assert other.place_order(buy(KR_A, 4000)).accepted
    fee = Season(inputs(single_asset_limit=D("0.5")))
    assert fee.place_order(buy(KR_A, 5000)).code == "SINGLE_ASSET_LIMIT"  # fee 7,500 shrinks total


def test_feed_reference_ranges_come_before_the_pool_check():
    season = Season(inputs())
    future = OrderSource(SourceKind.FEED, 3, 2)
    assert season.place_order(buy("KR:NOPE", 1, source=future)).code == "SOURCE_NOT_VISIBLE"
    assert season.place_order(buy(KR_A, 1, source=future)).code == "SOURCE_NOT_VISIBLE"
    wide = OrderSource(SourceKind.ALERT, 1, 32768)
    season.advance()
    assert season.place_order(buy(KR_A, 1, source=wide)).code == "SOURCE_NOT_VISIBLE"
    assert season.place_order(buy(KR_A, 1, source=OrderSource(SourceKind.ALERT, 1, 7))).accepted
    with pytest.raises(SimShapeError):
        OrderSource(SourceKind.FEED, 0, 1)
    with pytest.raises(SimShapeError):
        OrderSource(SourceKind.ALERT, 1, 0)


def test_a_turn_zero_price_that_rounds_to_zero_refuses_the_season():
    tiny = PoolAsset("KR:TINY", "KRW", D("0.4"), D(1))
    steps = tuple(
        PathStep(s.turn, {**s.local_returns, "KR:TINY": D(0)}, s.fx_change) for s in inputs().path
    )
    with pytest.raises(SimInputError):
        Season(SimInputs(Settings(), (*pool(), tiny), steps))


def test_invariants_catch_a_duplicate_open_span_and_a_wrong_snapshot(monkeypatch):
    season = Season(inputs())
    season.place_order(buy(KR_A, 10))
    season.spans.append(LineupSpan(KR_A, Position.DEFENDER, 0, None))
    with pytest.raises(SimInvariantError):
        season.check_invariants()
    season.spans.pop()
    season.check_invariants()
    real = season._snapshot
    monkeypatch.setattr(
        season, "_snapshot", lambda t, p: replace(real(t, p), holdings_value=D(1), total=D(2))
    )
    with pytest.raises(SimInvariantError):
        season.advance()

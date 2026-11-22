"""Formation changes, lineup changes and abandon: Java `FormationChangeService`,
`LineupChangeService`, `SeasonAbandonMarker` and `AbandonRules`, checked by hand values."""

from decimal import Decimal

import pytest
from sim_support import KR_A, KR_B, US_X, inputs

from antlab_engine.sim.model import (
    Formation,
    Order,
    OrderSource,
    Position,
    SeasonStatus,
    Side,
    SimInvariantError,
    SimShapeError,
    SimStateError,
)
from antlab_engine.sim.season import Season

D = Decimal
START = Formation(10, 30, 30, 30)


def buy(asset, quantity, position=Position.DEFENDER):
    return Order(asset, Side.BUY, quantity, position, OrderSource())


def sell(asset, quantity):
    return Order(asset, Side.SELL, quantity)


def at_turn(turn: int, **settings) -> Season:
    season = Season(inputs(**settings), strict=True)
    for _ in range(turn):
        season.advance()
    return season


# --- formation change ------------------------------------------------------------------------


def test_turn_zero_formation_change_is_refused_before_every_other_check():
    season = at_turn(0)
    assert season.change_formation(20, 30, 30, 20).code == "TURN_ZERO_FORMATION"
    # shares that break the rules and a bad reason still answer the turn-0 code
    assert season.change_formation(1, 1, 1, 1, "x" * 500).code == "TURN_ZERO_FORMATION"
    assert season.formation == START and season.formation_changes == []


def test_formation_check_order_invalid_then_reason_then_unchanged():
    season = at_turn(1)
    assert season.change_formation(10, 30, 30, 29).code == "FORMATION_INVALID"  # sum 99
    assert season.change_formation(0, 40, 30, 30).code == "FORMATION_INVALID"  # goalkeeper 0
    assert season.change_formation(101, 0, 0, -1).code == "FORMATION_INVALID"  # range
    # the maximum 32-bit shares do not overflow the sum; the range check answers first
    assert season.change_formation(2**31 - 1, 2**31 - 1, 0, 0).code == "FORMATION_INVALID"
    assert season.change_formation(-(2**31), 100, 30, 30).code == "FORMATION_INVALID"
    assert season.change_formation(10, 30, 30, 30, "x" * 201).code == "FORMATION_INVALID"
    assert season.change_formation(10, 30, 30, 30, "a\x00b").code == "FORMATION_INVALID"
    # a bad reason on an unchanged formation is a reason refusal, not UNCHANGED
    assert season.change_formation(10, 30, 30, 30, "x" * 201).code == "FORMATION_INVALID"
    assert season.change_formation(10, 30, 30, 30).code == "FORMATION_UNCHANGED"
    assert season.change_formation(10, 30, 30, 30, "x" * 200).code == "FORMATION_UNCHANGED"
    assert season.formation_changes == []


def test_accepted_change_is_recorded_and_chains_to_the_next():
    season = at_turn(1)
    assert season.change_formation(20, 30, 30, 20, "shift").accepted
    assert season.formation == Formation(20, 30, 30, 20)
    season.advance()
    # the comparison is with the current formation, so the start value is a valid change again
    assert season.change_formation(10, 30, 30, 30).accepted
    first, second = season.formation_changes
    assert (first.turn, first.before, first.after, first.reason) == (
        1,
        START,
        Formation(20, 30, 30, 20),
        "shift",
    )
    assert (second.turn, second.before, second.after) == (2, Formation(20, 30, 30, 20), START)


@pytest.mark.parametrize("bad", [20.0, True, 2**31, "20"])
def test_non_integer_or_out_of_range_shares_are_shape_errors(bad):
    season = at_turn(1)
    with pytest.raises(SimShapeError):
        season.change_formation(bad, 30, 30, 10)
    assert season.formation_changes == []


def test_a_broken_formation_chain_is_an_invariant_error_not_a_refusal():
    season = at_turn(1)
    season.change_formation(20, 30, 30, 20)
    season.formation = Formation(10, 30, 30, 30)  # history says 20/30/30/20
    with pytest.raises(SimInvariantError):
        season.change_formation(40, 20, 20, 20)


def test_order_is_bound_to_the_last_change_of_its_turn():
    season = at_turn(1)
    before = season.place_order(buy(KR_A, 10))
    season.change_formation(20, 30, 30, 20)
    middle = season.place_order(buy(KR_B, 10))
    season.change_formation(30, 30, 20, 20)
    last = season.place_order(buy(US_X, 1))
    season.advance()
    later = season.place_order(sell(KR_A, 5))
    bound = [v.execution.formation_change for v in (before, middle, last, later)]
    assert bound == [None, 0, 1, None]


def test_refused_change_binds_nothing():
    season = at_turn(1)
    assert season.change_formation(10, 30, 30, 30).code == "FORMATION_UNCHANGED"
    assert season.place_order(buy(KR_A, 10)).execution.formation_change is None


# --- lineup change ---------------------------------------------------------------------------


def test_lineup_change_is_refused_at_turn_zero_even_when_the_asset_is_held():
    season = at_turn(0)
    season.place_order(buy(KR_A, 100))
    assert season.change_lineup(KR_A, Position.MIDFIELDER).code == "TURN_MISMATCH"
    assert [(s.position, s.start_turn, s.end_turn) for s in season.spans] == [
        (Position.DEFENDER, 0, None)
    ]


def test_turn_zero_is_checked_before_the_holding():
    assert at_turn(0).change_lineup(KR_A, Position.MIDFIELDER).code == "TURN_MISMATCH"


def test_a_repeat_abandon_after_an_auto_turn_keeps_the_first_turn():
    season = at_turn(2)
    season.abandon()
    season.advance_abandoned()
    season.abandon()
    assert season.abandoned_turn == 2 and season.status is SeasonStatus.ABANDONING


def test_lineup_change_needs_a_holding():
    season = at_turn(1)
    assert season.change_lineup(KR_A, Position.MIDFIELDER).code == "INSUFFICIENT_HOLDING"
    assert season.change_lineup("KR:NOPE", Position.MIDFIELDER).code == "INSUFFICIENT_HOLDING"
    season.place_order(buy(KR_A, 100))
    season.place_order(sell(KR_A, 100))
    assert season.change_lineup(KR_A, Position.MIDFIELDER).code == "INSUFFICIENT_HOLDING"


def test_same_position_is_an_accepted_no_op():
    season = at_turn(1)
    season.place_order(buy(KR_A, 100))
    before = list(season.spans)
    assert season.change_lineup(KR_A, Position.DEFENDER).accepted
    assert season.spans == before and len(season.spans) == 1


def test_lineup_change_moves_the_value_to_the_new_position_from_the_next_snapshot():
    season = at_turn(0)
    season.place_order(buy(KR_A, 100))  # 10,000 won each, defender from turn 0
    first = season.advance()  # price 11,000
    assert (first.defender, first.midfielder) == (D(1_100_000), D(0))
    assert season.change_lineup(KR_A, Position.MIDFIELDER).accepted
    second = season.advance()  # price 12,100
    assert (second.defender, second.midfielder) == (D(0), D(1_210_000))
    old, new = season.spans
    assert (old.position, old.start_turn, old.end_turn) == (Position.DEFENDER, 0, 1)
    assert (new.position, new.start_turn, new.end_turn) == (Position.MIDFIELDER, 1, None)


def test_two_changes_in_one_turn_leave_a_zero_length_span():
    season = at_turn(1)
    season.place_order(buy(KR_A, 100))
    season.change_lineup(KR_A, Position.MIDFIELDER)
    season.change_lineup(KR_A, Position.FORWARD_BREAKOUT)
    assert [(s.position, s.start_turn, s.end_turn) for s in season.spans] == [
        (Position.DEFENDER, 1, 1),
        (Position.MIDFIELDER, 1, 1),
        (Position.FORWARD_BREAKOUT, 1, None),
    ]
    season.advance()
    season.check_invariants()


def test_a_buy_must_agree_with_the_changed_position():
    season = at_turn(1)
    season.place_order(buy(KR_A, 100))
    season.change_lineup(KR_A, Position.MIDFIELDER)
    assert season.place_order(buy(KR_A, 10, Position.DEFENDER)).code == "POSITION_CONFLICT"
    assert season.place_order(buy(KR_A, 10, None)).accepted
    assert season.place_order(buy(KR_A, 10, Position.MIDFIELDER)).accepted


def test_sell_out_after_a_change_closes_the_new_span():
    season = at_turn(1)
    season.place_order(buy(KR_A, 100))
    season.change_lineup(KR_A, Position.MIDFIELDER)
    season.advance()
    season.place_order(sell(KR_A, 100))
    assert [(s.position, s.end_turn) for s in season.spans] == [
        (Position.DEFENDER, 1),
        (Position.MIDFIELDER, 2),
    ]
    season.check_invariants()


def test_lineup_change_needs_a_position_value():
    season = at_turn(1)
    season.place_order(buy(KR_A, 100))
    with pytest.raises(SimShapeError):
        season.change_lineup(KR_A, "MIDFIELDER")  # type: ignore[arg-type]


def test_a_holding_without_a_span_is_an_invariant_error():
    season = at_turn(1)
    season.place_order(buy(KR_A, 100))
    del season._open[KR_A]
    with pytest.raises(SimInvariantError):
        season.change_lineup(KR_A, Position.MIDFIELDER)


# --- abandon ---------------------------------------------------------------------------------


def played_to(turn: int, **settings) -> Season:
    season = at_turn(0, **settings)
    season.place_order(buy(KR_A, 100))
    season.place_order(buy(US_X, 2, Position.FORWARD_TARGET))
    for _ in range(turn):
        season.advance()
    return season


def test_abandon_marks_the_turn_and_blocks_everything_but_auto_turns():
    season = played_to(2)
    season.abandon()
    assert (season.status, season.abandoned_turn) == (SeasonStatus.ABANDONING, 2)
    with pytest.raises(SimStateError):
        season.place_order(sell(KR_A, 1))
    with pytest.raises(SimStateError):
        season.change_formation(20, 30, 30, 20)
    with pytest.raises(SimStateError):
        season.change_lineup(KR_A, Position.MIDFIELDER)
    with pytest.raises(SimStateError):
        season.advance()


def test_auto_turns_hold_every_position_to_the_end():
    held = played_to(2)
    twin = played_to(2)  # same history, played out with plain advances and no trades
    held.abandon()
    ledger, holdings = list(held.ledger), dict(held.holdings)
    snapshots = held.run_abandoned()
    while twin.status is SeasonStatus.IN_PROGRESS:
        twin.advance()
    assert [s.turn for s in snapshots] == list(range(3, 13))
    assert held.status is SeasonStatus.FINISHED and held.abandoned_turn == 2
    assert held.current_turn == 12
    assert held.ledger == ledger and held.holdings == holdings  # no trades, no fees
    assert held.turns == twin.turns
    first = snapshots[0]
    # price(KR_A, turn 3) = 10000 * 1.1^3 = 13,310; holdings value is quantity times price
    assert held.turn_prices[3][KR_A] == D(13_310)
    assert first.holdings_value == D(100) * D(13_310) + D(2) * held.turn_prices[3][US_X]
    held.check_invariants()


def test_abandon_at_turn_zero_with_no_trades_ends_at_the_initial_cash():
    season = at_turn(0)
    season.abandon()
    snapshots = season.run_abandoned()
    assert len(snapshots) == 12 and season.abandoned_turn == 0
    assert all(s.total == D(100_000_000) and s.drawdown == D(0) for s in snapshots)


def test_abandon_on_the_second_to_last_turn_runs_one_auto_turn():
    season = at_turn(11)
    season.abandon()
    assert [s.turn for s in season.run_abandoned()] == [12]
    assert season.status is SeasonStatus.FINISHED


def test_abandon_repeats_change_nothing_and_a_finished_season_cannot_start_one():
    season = played_to(4)
    season.abandon()
    season.abandon()
    assert season.abandoned_turn == 4
    season.run_abandoned()
    season.abandon()  # a finished abandoned season: a repeat
    assert season.abandoned_turn == 4 and season.status is SeasonStatus.FINISHED
    done = at_turn(12)
    with pytest.raises(SimStateError):
        done.abandon()


def test_auto_turns_need_an_abandoning_season():
    season = at_turn(1)
    with pytest.raises(SimStateError):
        season.advance_abandoned()
    assert season.run_abandoned() == []


@pytest.mark.parametrize(
    ("kind", "turn", "expected"),
    [
        ("HUMAN", 0, True),
        ("HUMAN", 1, True),
        ("HUMAN", 2, True),
        ("HUMAN", 3, False),
        ("AGENT", 0, False),
        ("AGENT", 2, False),
        ("AGENT", 3, False),
    ],
)
def test_not_started_is_a_human_abandon_before_turn_three(kind, turn, expected):
    season = at_turn(turn, member_kind=kind)
    season.abandon()
    assert season.not_started is expected


def test_a_season_that_was_not_abandoned_is_never_not_started():
    assert at_turn(0).not_started is False
    assert at_turn(12).not_started is False

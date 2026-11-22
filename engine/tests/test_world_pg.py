"""Pure helpers of the database world reader: month choice and assembly (no database)."""

from datetime import date
from decimal import Decimal

import pytest

from antlab_engine.bootstrap.simple import generate_months
from antlab_engine.bots.world_pg import WorldRows, assemble, pick_months
from antlab_engine.engine_errors import InvalidArgument, PathTooLong
from antlab_engine.sim.feed.rules import Failure, FeedGenerationError
from antlab_engine.sim.model import Position

BASE = date(2026, 1, 2)
START = date(2007, 1, 1)
STORED = [date(2006, 12, 1)] + [date(2007 + i // 12, i % 12 + 1, 1) for i in range(72)]

POOL = (
    (
        2,
        "KR",
        "AAA",
        "KRW",
        "Alpha",
        "G_ONE",
        "Group one",
        Decimal("1000.5000"),
        Decimal("1.0000"),
        "DEFENDER",
    ),
    (
        1,
        "US",
        "BBB",
        "USD",
        "Beta",
        "G_TWO",
        "Group two",
        Decimal("50.0000"),
        Decimal("1350.0000"),
        None,
    ),
)


BLOCKS = tuple(range(10, 22))


def rows(**changes):
    returns = {(2, b): Decimal("0.01000000") for b in BLOCKS}
    returns |= {(1, b): Decimal("0.02000000") for b in BLOCKS}
    returns[(2, 11)] = Decimal("-0.02000000")
    base = {
        "base_date": BASE,
        "path_blocks": BLOCKS,
        "reviewed": (True,) * 12,
        "pool": POOL,
        "returns": returns,
        "fx": {(b, "USD"): Decimal("0.00500000") for b in BLOCKS},
        "attention": ((11, 1, Decimal("1.50000000")), (10, 2, Decimal("0.25000000"))),
        "events": (
            (11, "fakea1-x", "TRADE", Decimal("0.02000000"), 2, False),
            (10, "fakeb1-x", "POLICY", Decimal("-0.01000000"), 3, True),
        ),
    }
    base.update(changes)
    return WorldRows(**base)


def test_the_world_carries_pool_returns_fx_attention_and_events_by_turn():
    world = assemble(rows(), seed=7)
    assert world.seed == 7 and world.base_date == BASE
    assert [a.asset for a in world.inputs.pool] == ["KR:AAA", "US:BBB"]
    kr, us = world.inputs.pool
    assert (kr.currency, kr.base_price, kr.base_fx_rate) == ("KRW", Decimal("1000.5000"), 1)
    assert kr.system_position is Position.DEFENDER and us.system_position is None
    assert [s.turn for s in world.inputs.path] == list(range(1, 13))
    assert world.inputs.path[0].local_returns == {
        "KR:AAA": Decimal("0.01000000"),
        "US:BBB": Decimal("0.02000000"),
    }
    assert world.inputs.path[1].local_returns["KR:AAA"] == Decimal("-0.02000000")
    assert world.inputs.path[1].fx_change == {"USD": Decimal("0.00500000")}
    feed = world.feed
    assert feed is not None
    assert feed["assets"][1] == {
        "asset": "US:BBB",
        "name": "Beta",
        "groupCode": "G_TWO",
        "groupName": "Group two",
    }
    assert feed["attention"][:4] == [
        {"turn": 0, "values": {}},
        {"turn": 1, "values": {"KR:AAA": "0.25000000"}},
        {"turn": 2, "values": {"US:BBB": "1.50000000"}},
        {"turn": 3, "values": {}},
    ]
    assert len(feed["attention"]) == 13
    assert feed["events"] == [
        {
            "turn": 1,
            "key": "fakeb1-x",
            "category": None,
            "hidden": True,
            "reaction": "-0.01000000",
            "strength": 3,
        },
        {
            "turn": 2,
            "key": "fakea1-x",
            "category": "TRADE",
            "hidden": False,
            "reaction": "0.02000000",
            "strength": 2,
        },
    ]
    assert feed["reviewed"] == list(range(1, 13))


def test_a_world_without_a_foreign_asset_has_no_fx_column():
    only_kr = rows(
        pool=(POOL[0],),
        returns={(2, b): Decimal("0.01") for b in BLOCKS},
        attention=((10, 2, Decimal("0.25")),),
    )
    world = assemble(only_kr, seed=1)
    assert all(step.fx_change == {} for step in world.inputs.path)


def test_an_unreviewed_path_month_refuses_with_the_java_code_and_names_nothing():
    with pytest.raises(FeedGenerationError) as caught:
        assemble(rows(reviewed=(True,) * 11 + (False,)), seed=1)
    assert caught.value.failure is Failure.FEED_EVENTS_UNREVIEWED
    assert "2026" not in str(caught.value)


def test_a_missing_return_refuses_without_naming_asset_or_month():
    returns = dict(rows().returns)
    del returns[(1, 15)]
    with pytest.raises(FeedGenerationError) as caught:
        assemble(rows(returns=returns), seed=1)
    assert caught.value.failure is Failure.FEED_INPUT
    assert "BBB" not in str(caught.value) and "15" not in str(caught.value)


def test_a_missing_fx_change_refuses_when_a_usd_asset_is_in_the_pool():
    with pytest.raises(FeedGenerationError) as caught:
        assemble(rows(fx={(b, "USD"): Decimal("0.001") for b in BLOCKS[:-1]}), seed=1)
    assert caught.value.failure is Failure.FEED_INPUT


def test_no_float_reaches_the_world():
    doc = assemble(rows(), seed=1).to_doc()
    assert "e-" not in str(doc)


def test_given_months_are_kept_in_order():
    months = [date(2010 + (i + 6) // 12, (i + 6) % 12 + 1, 1) for i in range(12)]
    months[0], months[1] = months[1], months[0]
    assert pick_months(STORED, BASE, START, months=months, length=None, path_seed=None) == months


def _twelve(first: date, replace: date | None = None, twice: bool = False) -> list[date]:
    months = [
        date(first.year + (first.month - 1 + i) // 12, (first.month - 1 + i) % 12 + 1, 1)
        for i in range(12)
    ]
    if replace is not None:
        months[5] = replace
    if twice:
        months[5] = months[4]
    return months


@pytest.mark.parametrize(
    "months",
    [
        [],
        _twelve(date(2010, 1, 1))[:6],
        _twelve(date(2010, 1, 1), twice=True),
        _twelve(date(2010, 1, 1), replace=date(2010, 3, 2)),
        _twelve(date(2010, 1, 1), replace=date(2006, 12, 1)),
        _twelve(date(2010, 1, 1), replace=date(2026, 1, 1)),
        _twelve(date(2010, 1, 1), replace=date(2013, 1, 1)),
    ],
)
def test_bad_given_months_refuse_and_name_nothing(months):
    with pytest.raises(InvalidArgument) as caught:
        pick_months(STORED, BASE, START, months=months, length=None, path_seed=None)
    assert "2010" not in str(caught.value)


def test_a_replay_start_after_a_given_month_refuses():
    with pytest.raises(InvalidArgument):
        pick_months(
            STORED,
            BASE,
            date(2009, 6, 1),
            months=_twelve(date(2009, 1, 1)),
            length=None,
            path_seed=None,
        )


def test_months_or_a_length_and_a_seed_are_needed():
    with pytest.raises(InvalidArgument):
        pick_months(STORED, BASE, START, months=None, length=None, path_seed=None)
    with pytest.raises(InvalidArgument):
        pick_months(STORED, BASE, START, months=None, length=12, path_seed=None)


def test_a_drawn_path_follows_the_engine_month_selection():
    full = [date(2007 + i // 12, i % 12 + 1, 1) for i in range(12 * 19)]
    first = pick_months(full, BASE, START, months=None, length=12, path_seed=5)
    again = pick_months(full, BASE, START, months=None, length=12, path_seed=5)
    assert first == again and len(first) == 12
    assert all(START <= m < date(2026, 1, 1) for m in first)
    with pytest.raises(PathTooLong):
        pick_months(STORED[:14], BASE, START, months=None, length=60, path_seed=5)


def test_events_and_world_id_do_not_depend_on_surrogate_block_ids():
    first = assemble(rows(), seed=1)
    renumber = {b: 1000 - b for b in BLOCKS}
    permuted = rows(
        path_blocks=tuple(renumber[b] for b in BLOCKS),
        returns={(a, renumber[b]): v for (a, b), v in rows().returns.items()},
        fx={(renumber[b], c): v for (b, c), v in rows().fx.items()},
        attention=tuple((renumber[b], a, v) for b, a, v in rows().attention),
        events=tuple((renumber[e[0]], *e[1:]) for e in reversed(rows().events)),
    )
    second = assemble(permuted, seed=1)
    assert second.feed["events"] == first.feed["events"]
    assert second.world_id == first.world_id


def test_an_event_without_a_reaction_refuses_the_world_up_front():
    events = (*rows().events, (12, "fakec1-x", "TRADE", None, 1, False))
    with pytest.raises(FeedGenerationError) as caught:
        assemble(rows(events=events), seed=1)
    assert caught.value.failure is Failure.FEED_REACTION_MISSING
    assert "fakec1" not in str(caught.value)


def test_months_and_a_length_together_refuse():
    months = pick_months(STORED, BASE, START, months=None, length=12, path_seed=1)
    with pytest.raises(InvalidArgument):
        pick_months(STORED, BASE, START, months=months, length=12, path_seed=1)


def test_a_drawn_path_is_what_generate_months_gives_and_the_replay_start_changes_it():
    full = [date(2007 + i // 12, i % 12 + 1, 1) for i in range(12 * 19)]
    for seed in (1, 5, 9):
        assert pick_months(full, BASE, START, months=None, length=12, path_seed=seed) == (
            generate_months(full, BASE, 12, seed, START)
        )
    late = date(2016, 1, 1)
    drawn = pick_months(full, BASE, late, months=None, length=12, path_seed=5)
    assert drawn == generate_months(full, BASE, 12, 5, late)
    assert all(m >= late for m in drawn)
    assert drawn != pick_months(full, BASE, START, months=None, length=12, path_seed=5)

"""Per-order trade flags: parity with the score item inputs, turn 0 excluded, event coverage."""

from dataclasses import replace
from decimal import Decimal

from antlab_engine.bots.random_bot import buy_unit, sell_unit
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim import behaviour, money
from antlab_engine.sim.model import PathStep, SimInputs
from antlab_engine.sim.report import plain
from antlab_engine.sim.score import discipline_facts_of
from antlab_engine.validation.trade_flags import FLAG_NAMES, event_reactions, flags_of

MASTER = 303


def parity_runs():
    for seed in range(6):
        world = synthetic_world(seed, assets=14, length=(12, 24)[seed % 2], feed=True)
        for kind, count in (("trait", 7), ("random", 2), ("news", 1)):
            for index in range(count):
                yield run_bot(world, make_bot(kind, index, MASTER, world.seed))


def test_flag_sums_equal_the_inputs_of_the_noise_discipline_and_nudge_items():
    checked = noisy = nudged = disciplined = 0
    for run in parity_runs():
        season, signals = run.season, run.signals
        t = season.current_turn
        flags = flags_of(season, signals)
        noise = behaviour.noise_of_season(
            t,
            signals,
            [
                behaviour.NoiseOrder(e.turn, e.side, e.asset, e.notional)
                for e in season.executions
                if e.turn < t
            ],
        )
        discipline = behaviour.discipline(discipline_facts_of(season))
        assert flags.noise == int(noise.followed_buy_notional + noise.followed_sell_notional)
        assert flags.discipline == int(
            discipline.panic_sell_notional + discipline.chase_buy_notional
        )
        assert flags.traded == int(discipline.trade_notional) == int(noise.trade_notional)
        share = run.record["report"]["nudgeShare"]
        if flags.traded:
            ratio = money.exact_rate(money.ratio(Decimal(flags.nudge), Decimal(flags.traded)))
            assert plain(ratio) == share
        else:
            assert share is None
        assert flags.both <= min(flags.noise, flags.discipline)
        assert flags.nudge_noise <= min(flags.nudge, flags.noise)
        assert flags.nudge_discipline <= min(flags.nudge, flags.discipline)
        assert flags.event_caught <= flags.event_direction == 0
        checked += 1
        noisy += flags.noise > 0
        nudged += flags.nudge > 0
        disciplined += flags.discipline > 0
    assert checked >= 50 and noisy > 10 and nudged > 10 and disciplined > 10


def test_turn_zero_is_not_flagged_and_a_bot_that_never_trades_has_empty_flags():
    world = synthetic_world(1, assets=12, length=12, feed=True)
    run = run_bot(world, make_bot("static", 0, MASTER, world.seed))
    assert any(e.turn == 0 for e in run.season.executions)
    flags = flags_of(run.season, run.signals, {1: Decimal("-0.01")})
    assert set(flags.to_wire()) == set(FLAG_NAMES)
    assert all(value == "0" for value in flags.to_wire().values())


def test_a_season_without_signals_flags_no_noise_but_still_flags_the_rest():
    world = synthetic_world(2, assets=12, length=12, feed=True)
    run = run_bot(world, make_bot("random", 3, MASTER, world.seed))
    flags = flags_of(run.season, None)
    assert flags.noise == 0 and flags.traded > 0


class Script:
    kind = "script"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self, assets):
        self.assets = assets

    def decide(self, view):
        by_name = {a.asset: a for a in view.pool}
        a, b, c, d = self.assets
        units = []
        if view.turn == 0:
            units = [buy_unit(view, by_name[n], 10_000_000)[0] for n in self.assets]
        elif view.turn == 1:
            units = [sell_unit(view, n, view.holdings[n])[0] for n in (a, b)]
        elif view.turn == 2:
            units = [buy_unit(view, by_name[n], 5_000_000)[0] for n in (c, d)]
        elif view.turn == 4:
            units = [buy_unit(view, by_name[b], 1_000_000)[0]]
        return tuple(units)


def scripted_run():
    base = synthetic_world(1, assets=8, length=12)
    names = ("KR:S000", "KR:S002", "KR:S004", "KR:069500")
    zero = {a.asset: Decimal(0) for a in base.inputs.pool}
    steps = []
    for turn in range(1, 13):
        returns = dict(zero)
        if turn == 1:
            returns[names[0]] = Decimal("-0.10")
        if turn == 2:
            returns[names[2]] = Decimal("0.10")
        steps.append(PathStep(turn, returns, {"USD": Decimal(0)}))
    inputs = SimInputs(base.inputs.settings, base.inputs.pool, tuple(steps))
    world = replace(base, inputs=inputs)
    return run_bot(world, Script(names)), names


def notional(season, turn, asset):
    return sum(int(e.notional) for e in season.executions if e.turn == turn and e.asset == asset)


def test_event_coverage_counts_orders_in_the_direction_of_the_turns_reaction():
    run, (a, b, c, d) = scripted_run()
    season = run.season
    reactions = {1: Decimal("-0.03"), 2: Decimal("0.02"), 4: Decimal("-0.01")}
    flags = flags_of(season, None, reactions)
    # turn 1 sells (A dropped: caught; B flat), turn 2 buys (C rose: caught; D flat),
    # turn 4 buys B in a negative turn: not in the direction of the event
    assert flags.event_direction == (
        notional(season, 1, a)
        + notional(season, 1, b)
        + notional(season, 2, c)
        + notional(season, 2, d)
    )
    assert flags.event_caught == notional(season, 1, a) + notional(season, 2, c)
    assert flags.discipline == flags.event_caught
    assert flags.event_caught < flags.event_direction


def test_a_zero_or_missing_reaction_gives_no_direction():
    run, _ = scripted_run()
    assert flags_of(run.season, None, {1: Decimal(0)}).event_direction == 0
    assert flags_of(run.season, None, {}).event_direction == 0
    assert flags_of(run.season, None).event_direction == 0


def test_the_sign_decides_the_side():
    run, (a, b, c, d) = scripted_run()
    wrong_way = flags_of(run.season, None, {1: Decimal("0.03"), 2: Decimal("-0.02")})
    assert wrong_way.event_direction == 0
    only_buy = flags_of(run.season, None, {4: Decimal("0.01")})
    assert only_buy.event_direction == notional(run.season, 4, b)


def test_event_reactions_sum_per_turn_and_skip_hidden_values():
    feed = {
        "events": [
            {"turn": 2, "reaction": "0.01"},
            {"turn": 2, "reaction": "-0.03"},
            {"turn": 3, "reaction": None},
            {"turn": 5, "reaction": "0.02"},
        ]
    }
    assert event_reactions(feed) == {2: Decimal("-0.02"), 5: Decimal("0.02")}
    assert event_reactions(None) == {} and event_reactions({}) == {}


def test_discipline_flags_match_the_item_on_random_bots_that_change_formation():
    for seed in range(40):
        world = synthetic_world(seed, assets=12, length=24, feed=True)
        run = run_bot(world, make_bot("random", seed, MASTER, world.seed))
        facts = discipline_facts_of(run.season)
        result = behaviour.discipline(facts)
        flags = flags_of(run.season, run.signals)
        assert flags.discipline == int(result.panic_sell_notional + result.chase_buy_notional)


def test_the_flags_wire_form_is_whole_won_text():
    run, _ = scripted_run()
    wire = flags_of(run.season, None).to_wire()
    assert tuple(wire) == FLAG_NAMES
    assert all(isinstance(v, str) and v.isascii() and v.isdigit() for v in wire.values())


class Seen:
    kind = "seen"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self):
        self.rows = {}

    def decide(self, view):
        self.rows[view.turn] = sum(
            (i.reaction for i in view.feed if i.kind == "EVENT" and i.reaction is not None),
            Decimal(0),
        )
        return ()


def test_the_reactions_the_flags_use_equal_the_event_rows_the_bots_see():
    events = 0
    for seed in range(12):
        world = synthetic_world(seed, assets=12, length=24, feed=True)
        seen = Seen()
        run_bot(world, seen)
        expected = event_reactions(world.feed)
        events += len(expected)
        for turn in range(1, 24):
            assert seen.rows[turn] == expected.get(turn, Decimal(0)), (seed, turn)
    assert events > 0

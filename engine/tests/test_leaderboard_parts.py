"""Team components, edge rules, percentiles, parts and parity checks of the manager parts."""

import math
from dataclasses import replace
from decimal import Decimal

import pytest
from leaderboard_support import (
    TEST_SIZE,
    FakeWorldReader,
    identity_of,
    team_record,
    team_series,
)
from validation_support import comp

from antlab_engine.engine_errors import InvalidArgument, ReferenceMismatch
from antlab_engine.leaderboard.parts import (
    Percentiles,
    TeamComponents,
    TeamSeries,
    checked_components,
    parts_of,
    percentiles,
    season_parts,
    team_components,
)
from antlab_engine.leaderboard.reference import ReferenceService, reference_from_components
from antlab_engine.sim import money
from antlab_engine.sim.score import _cap_sharpe, sharpe_exact, team_returns_of
from antlab_engine.validation.components import RATIO_CAP, components_of
from antlab_engine.validation.composite import s_parts

D = Decimal
KEY = "0" * 64


def series(totals, benchmark=None, risk_score="87.50", initial="100") -> TeamSeries:
    initial = D(initial)
    values = tuple(D(t) for t in totals[1:])  # the lists below start with turn 0 for reading
    turns = len(values)
    returns = tuple(team_returns_of(initial, values))
    bench = tuple(D(r) for r in (benchmark or ["0.01"] * turns))
    return TeamSeries(
        initial,
        values,
        returns,
        bench,
        _cap_sharpe(sharpe_exact(returns)),
        _cap_sharpe(sharpe_exact(bench)),
        None if risk_score is None else D(risk_score),
    )


# 12 turns: flat, a 20 percent fall at turn 6, recovery at turn 7, +20 percent at turn 12.
HAND = ["100"] * 6 + ["80", "100", "100", "100", "100", "100", "120"]


def toy_reference(**overrides):
    """Three bots with hand-set metrics (see validation_support.comp)."""
    bots = [
        comp(
            index=0,
            excess_cagr="-0.1",
            sharpe_diff="-1",
            sortino_diff="-1",
            calmar_diff="-1",
            risk="80",
            mdd="-0.3",
        ),
        comp(
            index=1,
            excess_cagr="0",
            sharpe_diff="0",
            sortino_diff="0",
            calmar_diff="0",
            risk=overrides.get("risk", "87.4950"),
            mdd="-0.2",
        ),
        comp(
            index=2,
            excess_cagr="0.1",
            sharpe_diff="1",
            sortino_diff="1",
            calmar_diff="1",
            risk="90",
            mdd="-0.1",
        ),
    ]
    return reference_from_components(bots, (), key=KEY, size=3)


def components(**values) -> TeamComponents:
    zero = D(0)
    base = dict.fromkeys(TeamComponents.__dataclass_fields__, zero)
    base.update({k: D(v) for k, v in values.items()})
    return TeamComponents(**base)


# ---------------------------------------------------------------------------- components


def test_hand_series_components():
    c = team_components(series(HAND), 12)
    assert c.cagr == D("0.2")  # 12 turns: the total return
    assert c.mdd == D("-0.2")
    assert c.calmar == D(1)
    expected_sortino = 0.25 / 12 / math.sqrt(0.04 / 11)  # downside over n - 1
    assert abs(float(c.sortino) - expected_sortino) < 1e-12
    assert c.bench_mdd == 0
    assert c.bench_calmar == RATIO_CAP  # no drawdown: capped with the numerator's sign
    assert c.bench_sortino == RATIO_CAP  # no negative month
    assert c.sortino_diff == money.EXACT.subtract(c.sortino, RATIO_CAP)
    assert c.excess_cagr == money.EXACT.subtract(c.cagr, c.bench_cagr)


def test_sortino_without_a_negative_month_takes_the_sign_and_zero_mean_gives_zero():
    rising = team_components(series(["100"] + [str(100 + i) for i in range(1, 13)]), 12)
    assert rising.sortino == RATIO_CAP
    falling = ["100", "99", "100"] * 4 + ["100"]
    c = team_components(series(falling, benchmark=["0"] * 12), 12)
    assert c.bench_sortino == 0  # mean 0 and no downside
    assert c.bench_calmar == 0  # CAGR 0 over a drawdown of 0


def test_a_season_ending_at_zero_has_cagr_minus_one():
    totals = ["100"] * 12 + ["0"]
    c = team_components(series(totals), 12)
    assert c.cagr == D(-1)
    assert c.mdd == D(-1)
    assert c.calmar == D(-1)


def test_a_flat_season_has_calmar_zero_and_mdd_zero():
    c = team_components(series(["100"] * 13), 12)
    assert (c.cagr, c.mdd, c.calmar, c.sortino) == (0, 0, 0, 0)


# ---------------------------------------------------------------------------- percentiles


def test_toy_reference_percentiles_and_parts():
    ref = toy_reference()
    team = components(
        excess_cagr="0.05", sharpe_diff="0", sortino_diff="2", calmar_diff="-2", mdd="-0.2"
    )
    p = percentiles(team, D("87.50"), ref)
    assert p.excess_cagr == D("66.666666666666666666666666666667")
    assert p.sharpe_diff == D(50)  # one below, one equal: (2 + 1) / 6
    assert p.sortino_diff == D(100)  # above every bot
    assert p.calmar_diff == D(0)  # below every bot
    assert p.risk_item == D(50)  # 87.50 equals the bot at 87.4950 after rounding
    assert p.mdd == D(50)
    parts = parts_of(p)
    assert parts.ret == p.excess_cagr
    assert parts.risk_adjusted == D(50)
    assert parts.risk == D(50)


def test_the_risk_item_is_compared_at_two_places_on_both_sides():
    ref = toy_reference()
    p = percentiles(components(), D("87.50"), ref)
    assert p.risk_item == D(50)
    exact = toy_reference(risk="87.4949")
    assert percentiles(components(), D("87.50"), exact).risk_item == D(
        "66.666666666666666666666666666667"
    )


def test_all_equal_gives_fifty_below_all_zero_above_all_hundred():
    bots = [comp(index=i, excess_cagr="0.1") for i in range(3)]
    ref = reference_from_components(bots, (), key=KEY, size=3)
    assert ref.metrics.percentile("excess_cagr", D("0.1")) == D(50)
    assert ref.metrics.percentile("excess_cagr", D("0.0")) == D(0)
    assert ref.metrics.percentile("excess_cagr", D("0.2")) == D(100)


def test_a_season_without_a_risk_item_takes_the_drawdown_percentile_alone():
    ref = toy_reference()
    p = percentiles(components(mdd="-0.05"), None, ref)
    assert p.risk_item is None
    assert parts_of(p).risk == p.mdd == D(100)


def test_the_risk_adjusted_part_is_the_unrounded_mean():
    p = Percentiles(D(0), D(100), D(0), D(0), None, D(0))
    assert parts_of(p).risk_adjusted == D("33.333333333333333333333333333333")


def test_a_reference_of_the_wrong_size_is_refused():
    bots = [comp(index=i) for i in range(3)]
    with pytest.raises(ValueError, match="reference set has"):
        reference_from_components(bots, (), key=KEY, size=4)
    with pytest.raises(ValueError, match="reference set has"):
        reference_from_components(bots, (), key=KEY, size=2)


# ---------------------------------------------------------------------------- parity


@pytest.fixture(scope="module")
def played():
    reader = FakeWorldReader()
    service = ReferenceService(reader, size=TEST_SIZE)
    identity = identity_of()
    reference = service.reference(identity)
    record = team_record(service, reader, identity)
    return reference, record


def test_a_bot_record_gives_the_m303_parts_with_the_risk_item_at_two_places(played):
    reference, record = played
    team = team_series(record)
    result = season_parts(team, checked_components(team, 12), reference)
    bot = components_of(record)
    stored = replace(bot, items={**bot.items, "risk": money.round2(bot.items["risk"])})
    expected = s_parts(stored, reference.metrics)
    assert result.parts.ret == expected.ret
    assert result.parts.risk_adjusted == expected.risk_adjusted
    assert result.parts.risk == expected.risk
    assert result.components.excess_cagr == bot.excess_cagr
    assert result.components.calmar_diff == bot.calmar_diff
    assert result.components.sortino_diff == bot.sortino_diff
    assert result.components.mdd == bot.mdd


def _last_place(value: Decimal) -> Decimal:
    return money.EXACT.add(value, D(1).scaleb(value.as_tuple().exponent))


def test_one_unit_in_the_last_place_of_a_benchmark_return_is_a_mismatch(played):
    reference, record = played
    team = team_series(record)
    bench = list(team.benchmark_returns)
    bench[4] = _last_place(bench[4])
    changed = replace(team, benchmark_returns=tuple(bench))
    # the sent bench Sharpe follows the changed returns, so only the benchmark check can see it
    changed = replace(changed, bench_sharpe=_cap_sharpe(sharpe_exact(changed.benchmark_returns)))
    components_ = checked_components(changed, 12)
    with pytest.raises(ReferenceMismatch) as error:
        season_parts(changed, components_, reference)
    assert (
        str(error.value)
        == "The engine's benchmark or Sharpe for this season differs from the sent values."
    )


@pytest.mark.parametrize("field", ["sharpe", "bench_sharpe"])
def test_one_unit_in_the_last_place_of_a_sharpe_is_a_mismatch(played, field):
    _, record = played
    team = team_series(record)
    changed = replace(team, **{field: _last_place(getattr(team, field))})
    with pytest.raises(ReferenceMismatch):
        checked_components(changed, 12)


def test_returns_that_do_not_follow_from_the_totals_are_invalid(played):
    _, record = played
    team = team_series(record)
    returns = list(team.returns)
    returns[0] = _last_place(returns[0])
    with pytest.raises(InvalidArgument):
        checked_components(replace(team, returns=tuple(returns)), 12)
    with pytest.raises(InvalidArgument):
        checked_components(replace(team, totals=team.totals[:-1]), 12)

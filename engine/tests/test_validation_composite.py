"""Composites: s63 recomposition parity, S at given weights, weight refusals, and L."""

from decimal import Decimal
from itertools import permutations

import pytest
from validation_support import comp, roster, world_12, world_24

from antlab_engine.sim import money
from antlab_engine.validation.components import (
    ComponentError,
    ReferenceSet,
    components_of,
)
from antlab_engine.validation.composite import (
    DRAFT_63,
    DRAFT_S,
    SParts,
    SWeights,
    WeightError,
    Weights63,
    career_l,
    recompose_63,
    recompose_63_wire,
    s_parts,
    s_score,
    sample_stddev,
)

D = Decimal
TINY = D("1e-20")


@pytest.fixture(scope="module")
def all_records():
    return [*roster(world_12()), *roster(world_24())]


# ---------------------------------------------------------------------------- s63 parity


def test_recomposition_at_the_draft_weights_equals_the_stored_score(all_records):
    assert len(all_records) >= 28
    for record in all_records:
        c = components_of(record)
        assert recompose_63_wire(c, DRAFT_63) == record["score"]["score"]


def test_a_record_without_counts_recomposes_to_nothing():
    from validation_support import counted_record

    record = counted_record(world_12(), "random", 0, with_counts=False)
    assert record["score"]["score"] is None
    assert recompose_63(components_of(record)) is None


def test_other_weights_round_the_unrounded_parts_once(all_records):
    differs_from_rounded_parts = 0
    for record in all_records:
        c = components_of(record)
        for result in ("0.30", "0.35", "0.65", "0.70"):
            w = Weights63(D(result), 1 - D(result))
            exact = money.round2(w.result * c.result + w.process * c.process)
            assert recompose_63(c, w) == exact
            early = money.round2(
                w.result * money.round2(c.result) + w.process * money.round2(c.process)
            )
            differs_from_rounded_parts += exact != early
    assert differs_from_rounded_parts > 0


def test_more_result_weight_moves_the_score_toward_the_result(all_records):
    for record in all_records:
        c = components_of(record)
        low = recompose_63(c, Weights63(D("0.30"), D("0.70")))
        high = recompose_63(c, Weights63(D("0.70"), D("0.30")))
        if c.result > c.process:
            assert high >= low
        elif c.result < c.process:
            assert high <= low


# ---------------------------------------------------------------------------- weights


@pytest.mark.parametrize("result", ["0.30", "0.5", "0.70"])
def test_result_weights_inside_the_constraint_are_accepted(result):
    assert Weights63(D(result), 1 - D(result)).result == D(result)


@pytest.mark.parametrize(
    ("result", "process"),
    [("0.29", "0.71"), ("0.71", "0.29"), ("0.5", "0.4"), ("0.5", "0.6"), ("1", "0")],
)
def test_result_weights_outside_the_constraint_are_refused(result, process):
    with pytest.raises(WeightError):
        Weights63(D(result), D(process))


@pytest.mark.parametrize(
    "weights",
    [
        ("0.30", "0.30", "0.15", "0.25"),
        ("0.05", "0.05", "0.40", "0.50"),
        ("0.40", "0.30", "0.15", "0.15"),
        ("0.25", "0.25", "0.25", "0.25"),
    ],
)
def test_s_weights_inside_the_constraints_are_accepted(weights):
    assert sum(SWeights(*map(D, weights)).__dict__.values()) == 1


@pytest.mark.parametrize(
    "weights",
    [
        ("0.30", "0.30", "0.15", "0.30"),
        ("0.30", "0.30", "0.15", "0.20"),
        ("0.04", "0.41", "0.15", "0.40"),
        ("0.40", "0.30", "0.16", "0.15"),
        ("0.45", "0.30", "0.11", "0.14"),
        ("0.20", "0.20", "0.09", "0.51"),
        ("0.30", "0.30", "0.40", "0.00"),
        ("0.50", "0.50", "0.00", "0.00"),
        ("0.30", "0.30", "0.04", "0.36"),
    ],
)
def test_s_weights_breaking_a_constraint_are_refused(weights):
    with pytest.raises(WeightError):
        SWeights(*map(D, weights))


def test_the_draft_weights_are_the_plan_values():
    assert (DRAFT_63.result, DRAFT_63.process) == (D("0.5"), D("0.5"))
    assert (DRAFT_S.ret, DRAFT_S.risk_adjusted, DRAFT_S.risk, DRAFT_S.process) == (
        D("0.30"),
        D("0.30"),
        D("0.15"),
        D("0.25"),
    )


# ---------------------------------------------------------------------------- S


def toy_reference() -> ReferenceSet:
    bots = [
        comp(
            index=0,
            excess_cagr="0.01",
            sharpe_diff="0.1",
            sortino_diff="0.2",
            calmar_diff="0.3",
            risk="80",
            mdd="-0.30",
        ),
        comp(
            index=1,
            excess_cagr="0.02",
            sharpe_diff="0.2",
            sortino_diff="0.4",
            calmar_diff="0.6",
            risk="90",
            mdd="-0.20",
        ),
        comp(
            index=2,
            excess_cagr="0.03",
            sharpe_diff="0.3",
            sortino_diff="0.6",
            calmar_diff="0.9",
            risk="100",
            mdd="-0.10",
        ),
    ]
    return ReferenceSet.from_components(bots)


def test_s_on_a_three_bot_reference_at_the_draft_weights():
    bot = comp(
        "disciplined",
        0,
        excess_cagr="0.025",  # below 2 of 3: 66.67
        sharpe_diff="0.05",  # below 0: 0
        sortino_diff="0.4",  # below 1, equal 1: 50
        calmar_diff="1.0",  # above all: 100
        risk="90",  # below 1, equal 1: 50
        mdd="-0.25",  # below 1 of 3: 33.33
        process="70",
    )
    parts = s_parts(bot, toy_reference())
    assert abs(parts.ret - D("66.666666666666666666666666666667")) < TINY
    assert parts.risk_adjusted == 50
    assert abs(parts.risk - D("41.666666666666666666666666666667")) < TINY
    assert parts.process == 70
    # 0.30 x 66.67 + 0.30 x 50 + 0.15 x 41.67 + 0.25 x 70
    assert abs(s_score(parts, DRAFT_S) - D("58.75")) < TINY


def test_s_follows_the_weights():
    parts = SParts(D(80), D(60), D(40), D(20))
    assert (
        s_score(parts, DRAFT_S) == D("0.30") * 80 + D("0.30") * 60 + D("0.15") * 40 + D("0.25") * 20
    )
    heavy = SWeights(D("0.20"), D("0.15"), D("0.15"), D("0.50"))
    assert (
        s_score(parts, heavy) == D("0.20") * 80 + D("0.15") * 60 + D("0.15") * 40 + D("0.50") * 20
    )


def test_the_best_and_worst_bots_hit_the_ends():
    best = comp(
        excess_cagr="1",
        sharpe_diff="1",
        sortino_diff="1",
        calmar_diff="1",
        risk="101",
        mdd="0",
        process="100",
    )
    worst = comp(
        excess_cagr="-1",
        sharpe_diff="-1",
        sortino_diff="-1",
        calmar_diff="-1",
        risk="-1",
        mdd="-1",
        process="0",
    )
    assert s_score(s_parts(best, toy_reference())) == 100
    assert s_score(s_parts(worst, toy_reference())) == 0


def test_a_bot_from_another_world_is_not_ranked():
    with pytest.raises(ComponentError):
        s_parts(comp(world_id="other"), toy_reference())
    with pytest.raises(ComponentError):
        s_parts(comp(path_length=24), toy_reference())


def test_s_on_real_records_stays_within_0_to_100(all_records):
    for records in (all_records[: len(all_records) // 2], all_records[len(all_records) // 2 :]):
        comps = [components_of(r) for r in records]
        reference = ReferenceSet.from_components([c for c in comps if c.kind == "random"])
        for c in comps:
            value = s_score(s_parts(c, reference))
            assert 0 <= value <= 100


# ---------------------------------------------------------------------------- L

TEN = ["5", "6", "3", "9", "7", "1", "5", "8", "2", "20"]


def test_l_trims_one_at_each_end_and_subtracts_half_the_sample_sd():
    values = [D(v) for v in TEN]
    # trimmed: 2 3 5 5 6 7 8 9 -> 45 / 8; sample variance (10 x 694 - 66^2) / 90
    expected = D(45) / 8 - D("0.5") * (D(2584) / 90).sqrt()
    assert abs(career_l(values) - expected) < TINY


def test_l_does_not_depend_on_the_order():
    values = [D(v) for v in TEN]
    base = career_l(values)
    for shifted in (values[::-1], values[3:] + values[:3]):
        assert career_l(shifted) == base


def test_l_of_equal_scores_is_that_score():
    assert career_l([D(40)] * 10) == 40


def test_l_with_ties_at_the_ends_drops_exactly_one_each():
    values = [D(v) for v in ["1", "1", "1", "5", "5", "5", "5", "9", "9", "9"]]
    # kept: 1 1 5 5 5 5 9 9 -> mean 5; sample variance of all ten 96 / 9
    assert abs(career_l(values) - (5 - D("0.5") * (D(96) / 9).sqrt())) < TINY


def test_l_needs_exactly_ten_seasons():
    for n in (0, 1, 9, 11):
        with pytest.raises(ComponentError):
            career_l([D(1)] * n)


def test_sample_stddev_divides_by_n_minus_one():
    assert abs(sample_stddev([D(1), D(3)]) - D(2).sqrt()) < TINY
    assert sample_stddev([D(2), D(2), D(2)]) == 0
    with pytest.raises(ComponentError):
        sample_stddev([D(1)])


def test_permutations_of_a_small_career_agree_on_the_sd():
    values = [D(1), D(2), D(4)]
    assert len({sample_stddev(list(p)) for p in permutations(values)}) == 1

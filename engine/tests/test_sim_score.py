"""Season score s63-v3, replayed from the Java hand fixtures, and the request assembly."""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from antlab_engine.sim import money, report, score
from antlab_engine.sim.counts import PercentileRequest, RankCounts, WirePortfolio
from antlab_engine.sim.model import SimReportError, SimShapeError

D = Decimal
TEAM = tuple(
    map(D, "0.02 -0.01 0.03 0.01 -0.02 0.04 0.00 0.01 -0.01 0.02 0.03 -0.03".split())  # noqa: SIM905
)
BENCH = tuple(
    map(D, "0.01 0.00 0.02 0.01 -0.01 0.03 0.01 0.00 -0.01 0.01 0.02 -0.02".split())  # noqa: SIM905
)


def inputs(**kw) -> score.ScoreInputs:
    """The worked s63-v3 fixture; a test changes one field at a time."""
    base = score.ScoreInputs(
        TEAM,
        BENCH,
        12,
        3,
        D("-0.05"),
        D("-0.20"),
        D("0.30"),
        D("0.5"),
        D("0.10"),
        D("0.6"),
        D("0.25"),
        None,
        score.DisciplineRatios(D("0.2"), D("0.4")),
        RankCounts(1200, 100, 2000),
        RankCounts(900, 0, 2000),
    )
    return replace(base, **kw)


def p(value: Decimal | None) -> str | None:
    return None if value is None else report.plain(value)


def test_the_worked_fixture_gives_every_stored_value():
    r = score.calculate(inputs())
    assert r.score_version == "s63-v3"
    assert p(r.sharpe) == "0.34412360"
    assert p(r.bench_sharpe) == "0.40414519"
    assert p(r.sharpe_diff) == "-0.06002159"
    assert p(r.sharpe_diff_percentile) == "0.62500000"
    assert p(r.luck_percentile) == "0.45000000"
    assert p(r.risk_score) == "75.00"
    assert p(r.discipline_score) == "70.00"
    assert p(r.diversification_score) == "50.00"
    assert p(r.turnover_score) == "75.00"
    assert r.consistency_score is None
    assert p(r.formation_score) == "80.00"
    assert p(r.role_score) == "60.00"
    assert r.noise_score is None
    assert p(r.nudge_score) == "75.00"
    assert r.process_items == 7
    assert p(r.process_score) == "69.29"
    assert p(r.result_score) == "62.50"
    assert p(r.score) == "65.89"


def test_the_score_is_rounded_once_from_the_unrounded_parts():
    r = score.calculate(inputs())
    assert D("0.5") * r.result_score + D("0.5") * r.process_score == D("65.895")
    assert r.score == D("65.89")  # parts first would give 65.90


def test_the_score_has_the_java_nineteen_keys_in_order():
    wire = score.calculate(inputs()).to_wire()
    assert list(wire) == [
        "scoreVersion",
        "score",
        "resultScore",
        "processScore",
        "sharpe",
        "benchSharpe",
        "sharpeDiff",
        "sharpeDiffPercentile",
        "luckPercentile",
        "riskScore",
        "disciplineScore",
        "diversificationScore",
        "turnoverScore",
        "consistencyScore",
        "formationScore",
        "roleScore",
        "noiseScore",
        "nudgeScore",
        "processItems",
    ]
    assert wire["score"] == "65.89" and wire["consistencyScore"] is None


def test_equal_returns_give_a_sharpe_of_zero_without_a_division_error():
    r = score.calculate(inputs(returns=(D("0.01"),) * 12, benchmark_returns=(D(0),) * 12))
    assert (r.sharpe, r.bench_sharpe, r.sharpe_diff) == (0, 0, 0)
    third = money.score_divide(D(1), D(3))
    assert score.sharpe_exact([third] * 4) == 0


@pytest.mark.parametrize(
    ("weight", "expected"),
    [("0.00", "100.00"), ("0.10", "100.00"), ("0.30", "50.00"), ("0.50", "0.00"), ("1.00", "0.00")],
)
def test_diversification_full_to_ten_percent_zero_from_fifty(weight, expected):
    assert p(score.calculate(inputs(max_single_weight=D(weight))).diversification_score) == expected


@pytest.mark.parametrize(
    ("turnover", "expected"),
    [("0", "100.00"), ("0.5", "75.00"), ("1", "50.00"), ("2", "0.00"), ("3.5", "0.00")],
)
def test_turnover_reaches_zero_at_two_hundred_percent(turnover, expected):
    assert p(score.calculate(inputs(turnover_annual=D(turnover))).turnover_score) == expected


@pytest.mark.parametrize(
    ("deviation", "expected"),
    [("0", "100.00"), ("0.25", "50.00"), ("0.50", "0.00"), ("1.40", "0.00"), ("2", "0.00")],
)
def test_formation_reaches_zero_at_half(deviation, expected):
    r = score.calculate(inputs(measured_formation_deviation=D(deviation)))
    assert p(r.formation_score) == expected


@pytest.mark.parametrize(
    ("turns", "depth", "expected"),
    [
        (0, "0", "100.00"),
        (12, "-0.20", "0.00"),
        (12, "-0.90", "0.00"),
        (6, "-0.10", "50.00"),
        (0, "-0.40", "50.00"),
    ],
)
def test_risk_combines_breach_turns_and_clamped_depth(turns, depth, expected):
    r = score.calculate(inputs(risk_breach_turns=turns, risk_breach_depth=D(depth)))
    assert p(r.risk_score) == expected


def test_role_nudge_and_noise_follow_their_ratios_at_both_ends():
    low = score.calculate(inputs(role_fit=D(0), nudge_share=D(1)))
    high = score.calculate(inputs(role_fit=D(1), nudge_share=D(0)))
    assert (p(low.role_score), p(low.nudge_score)) == ("0.00", "0.00")
    assert (p(high.role_score), p(high.nudge_score)) == ("100.00", "100.00")
    for share, expected in (("0", "100.00"), ("0.25", "75.00"), ("1", "0.00")):
        assert p(score.calculate(inputs(noise_share=D(share))).noise_score) == expected


def test_a_noise_item_joins_the_mean_and_a_missing_one_leaves_it_one_lower():
    without = score.calculate(inputs())
    with_noise = score.calculate(inputs(noise_share=D("0.25")))
    assert (without.process_items, without.noise_score) == (7, None)
    assert with_noise.process_items == 8 and p(with_noise.process_score) == "70.00"


def test_discipline_averages_only_the_measured_ratios():
    one = score.calculate(inputs(discipline=score.DisciplineRatios(D("0.5"), None)))
    neither = score.calculate(inputs(discipline=score.DisciplineRatios(None, None)))
    assert p(one.discipline_score) == "50.00" and neither.discipline_score is None


def test_a_missing_input_drops_its_item_and_the_mean_runs_over_the_rest():
    r = score.calculate(inputs(discipline=None, role_fit=None, nudge_share=None))
    assert (r.discipline_score, r.role_score, r.nudge_score) == (None, None, None)
    assert r.process_items == 4 and p(r.process_score) == "70.00"  # (75 + 50 + 75 + 80) / 4
    lone = score.calculate(
        inputs(
            risk_breach_turns=None,
            discipline=None,
            max_single_weight=None,
            turnover_annual=None,
            measured_formation_deviation=None,
            role_fit=None,
        )
    )
    assert lone.process_items == 1 and p(lone.process_score) == "75.00" and lone.score is not None


def test_no_distribution_leaves_result_score_and_percentiles_empty_together():
    r = score.calculate(inputs(sharpe_diff_counts=None, end_multiple_counts=None))
    assert (r.result_score, r.score, r.sharpe_diff_percentile, r.luck_percentile) == (None,) * 4
    assert p(r.process_score) == "69.29"


def test_a_tiny_nonzero_deviation_is_capped_to_the_column_range():
    spiky = (D("0.003"),) * 11 + (D("0.0030000000000000001"),)
    bench = (D("-0.003"),) * 11 + (D("-0.0030000000000000001"),)
    r = score.calculate(inputs(returns=spiky, benchmark_returns=bench))
    assert p(r.sharpe) == "999999.99999999"
    assert p(r.bench_sharpe) == "-999999.99999999"
    assert p(r.sharpe_diff) == "999999.99999999"


def test_exclusion_is_not_started_before_no_score_and_empty_for_a_normal_season():
    scored = score.calculate(inputs())
    unscored = score.calculate(inputs(sharpe_diff_counts=None, end_multiple_counts=None))
    assert score.exclusion(False, scored) is None
    assert score.exclusion(False, unscored) is score.RatingExclusion.NO_SCORE
    assert score.exclusion(True, unscored) is score.RatingExclusion.NOT_STARTED
    assert score.exclusion(True, scored) is score.RatingExclusion.NOT_STARTED


def test_decimal_arithmetic_keeps_one_tenth_plus_two_tenths_exact():
    r = score.calculate(inputs(turnover_annual=D("0.1") + D("0.2")))
    assert p(r.turnover_score) == "85.00"


def test_inputs_outside_their_ranges_are_refused():
    refusals = [
        inputs(report_turns=0, returns=(), benchmark_returns=()),
        inputs(returns=TEAM[:11]),
        inputs(report_turns=1, returns=TEAM[:1], benchmark_returns=BENCH[:1], risk_breach_turns=0),
        inputs(sharpe_diff_counts=RankCounts(1, 0, 10), end_multiple_counts=RankCounts(1, 0, 20)),
        inputs(end_multiple_counts=None),
        inputs(sharpe_diff_counts=None),
        inputs(risk_breach_turns=13),
        inputs(risk_breach_turns=-1),
        inputs(risk_breach_depth=D("0.01")),
        inputs(risk_limit=D(0)),
        inputs(max_single_weight=D("1.01")),
        inputs(max_single_weight=D("-0.01")),
        inputs(turnover_annual=D("-0.5")),
        inputs(measured_formation_deviation=D("2.01")),
        inputs(role_fit=D("1.01")),
        inputs(nudge_share=D("1.5")),
        inputs(noise_share=D("-0.01")),
        inputs(
            risk_breach_turns=None,
            discipline=None,
            max_single_weight=None,
            turnover_annual=None,
            measured_formation_deviation=None,
            role_fit=None,
            nudge_share=None,
        ),
    ]
    for bad in refusals:
        with pytest.raises(SimShapeError):
            score.calculate(bad)
    with pytest.raises(SimShapeError):
        score.calculate(inputs(returns=(*TEAM[:11], None)))
    for ratio in (D("1.1"), D("-0.1")):
        with pytest.raises(SimShapeError):
            score.DisciplineRatios(ratio, None)
        with pytest.raises(SimShapeError):
            score.DisciplineRatios(None, ratio)


def test_sharpe_uses_the_sample_deviation_and_signs_follow_the_mean():
    assert p(money.round8(score.sharpe_exact([D("0.01"), D("0.03")]))) == "1.41421356"
    assert p(money.round8(score.sharpe_exact([D("-0.01"), D("-0.03")]))) == "-1.41421356"
    for bad in ([D("0.01")], []):
        with pytest.raises(SimShapeError):
            score.sharpe_exact(bad)


@pytest.mark.parametrize(
    ("below", "equal", "n", "expected"),
    [
        (0, 0, 2000, "0.00000000"),
        (2000, 0, 2000, "1.00000000"),
        (0, 2000, 2000, "0.50000000"),
        (1200, 100, 2000, "0.62500000"),
        (1, 0, 3, "0.33333333"),
        (2, 0, 3, "0.66666667"),
        (0, 1, 3, "0.16666667"),
        (4, 3, 7, "0.78571429"),
    ],
)
def test_mid_rank_counts_a_tie_as_half(below, equal, n, expected):
    assert p(score.percentile_stored(RankCounts(below, equal, n))) == expected


@pytest.mark.parametrize(
    ("below", "equal", "n"),
    [(-1, 0, 10), (0, -1, 10), (6, 5, 10), (0, 0, 0), (0, 0, -5), (11, 0, 10)],
)
def test_impossible_counts_are_refused(below, equal, n):
    with pytest.raises(SimShapeError):
        RankCounts(below, equal, n)


# ------------------------------------------------------------ request assembly


def kr_us_team() -> score.StartTeam:
    # cash 100 000, 100 x KR:069500 at 5 000, 4 x US:SPY at 100 000: total 1 000 000
    return score.make_start_team(
        D(100_000), [("KR:069500", 100, D(5000)), ("US:SPY", 4, D(100_000))]
    )


def test_team_returns_use_the_initial_cash_as_the_first_base():
    got = score.team_returns_of(D(1_000_000), [D(1_100_000), D(990_000), D(1_089_000)])
    assert got == [D("0.1"), D("-0.1"), D("0.1")]
    fee = score.team_returns_of(D(1_000_000), [D(999_000), D(999_000)])
    assert fee == [D("-0.001"), D(0)]


def test_the_start_team_is_shares_rounded_down_with_cash_as_the_remainder():
    team = kr_us_team()
    assert team.total_value == D(1_000_000)
    assert [(a, p(w)) for a, w in team.shares] == [
        ("KR:069500", "0.500000000000"),
        ("US:SPY", "0.400000000000"),
    ]
    assert p(team.cash_share) == "0.100000000000"
    third = score.make_start_team(D(1), [("KR:A", 1, D(1)), ("KR:B", 1, D(1)), ("KR:C", 1, D(1))])
    # 1/4 rounds down at 12 digits: cash keeps the remainder, so the shares add up to exactly 1
    assert sum(w for _, w in third.shares) + third.cash_share == 1
    cash_only = score.make_start_team(D(500), [("KR:A", 0, D(5))])
    assert (cash_only.shares, p(cash_only.cash_share)) == ((), "1")
    with pytest.raises(SimReportError):
        score.make_start_team(D(0), [])


def observation(end_value: str = "1250000", bench=("0.02", "0.02", "0.04", "0.04")):
    totals = [D(1_100_000), D(990_000), D(1_089_000), D(980_100)]
    returns = score.team_returns_of(D(1_000_000), totals)
    return score.observe(returns, [D(x) for x in bench], kr_us_team(), D(end_value))


def test_the_static_end_multiple_is_the_start_team_held_to_the_last_turn():
    o = observation()
    assert o.static_end_multiple == D("1.25")
    assert o.sharpe == 0  # 0.1, -0.1, 0.1, -0.1: mean 0
    assert p(money.round12(o.bench_sharpe)) == "2.598076211353"
    assert o.sharpe_diff == money.EXACT.subtract(o.sharpe, o.bench_sharpe)


def request(o: score.Observation, seed: int = -4611686018427387904) -> PercentileRequest:
    return score.build_request(
        kr_us_team(), o, season_seed=seed, base_date=date(2026, 1, 2), horizon=12, path_count=2000
    )


def test_the_request_is_the_java_body():
    wire = request(observation()).to_wire()
    assert wire == {
        "seasonSeed": "-4611686018427387904",
        "baseDate": "2026-01-02",
        "horizonTurns": 12,
        "n": 2000,
        "team": {
            "cash": "0.100000000000",
            "weights": [
                {"asset": "KR:069500", "weight": "0.500000000000"},
                {"asset": "US:SPY", "weight": "0.400000000000"},
            ],
        },
        "benchmark": {
            "cash": "0",
            "weights": [
                {"asset": "KR:069500", "weight": "0.5"},
                {"asset": "US:SPY", "weight": "0.5"},
            ],
        },
        "observed": {"endMultiple": "1.250000000000", "sharpeDiff": "-2.598076211353"},
    }


def test_a_huge_sharpe_difference_is_capped_to_what_the_wire_holds():
    tiny = D("0.010000000000000000000000001")
    o = observation(bench=("0.01", str(tiny), "0.01", str(tiny)))
    assert abs(o.bench_sharpe) > 1_000_000 and o.sharpe_diff < 0
    assert request(o).sharpe_diff == "-999999.999999999999"


def test_the_end_multiple_is_rounded_once_at_twelve_digits():
    o = observation(end_value="1234567.890123656789")
    assert request(o).end_multiple == "1.234567890124"


def test_a_negative_zero_never_reaches_the_wire():
    returns = [D("0.01"), D("0.03")]
    o = score.observe(returns, returns, kr_us_team(), D(1_000_000))
    assert request(o).sharpe_diff == "0.000000000000"
    nearly = replace(o, sharpe_diff=D("-1E-20"))
    assert request(nearly).sharpe_diff == "0.000000000000"


def test_request_shape_rules():
    o = observation()
    good = request(o)
    for bad in (
        {"horizon_turns": 36},
        {"n": 99},
        {"n": 5001},
        {"season_seed": 2**63},
        {"end_multiple": "-1"},
        {"sharpe_diff": "1e5"},
    ):
        with pytest.raises(SimShapeError):
            replace(good, **bad)
    with pytest.raises(SimShapeError):
        WirePortfolio("0.5", (("KR:A", "0.4"),))
    with pytest.raises(SimShapeError):
        WirePortfolio("0", (("KR:A", "0.5"), ("KR:A", "0.5")))
    with pytest.raises(SimShapeError):
        WirePortfolio("1.5", ())


def test_input_checks_run_in_the_java_order():
    chain = [
        (
            inputs(report_turns=0, returns=(), benchmark_returns=(), risk_breach_turns=99),
            "at least 1",
        ),
        (inputs(returns=TEAM[:3], sharpe_diff_counts=None), "one value per report turn"),
        (inputs(sharpe_diff_counts=None, risk_breach_turns=99), "together or not at all"),
        (
            inputs(end_multiple_counts=RankCounts(1, 0, 9), risk_breach_turns=99),
            "same number of paths",
        ),
        (inputs(risk_breach_turns=99, risk_breach_depth=D(1)), "breach turns out of range"),
        (inputs(risk_breach_depth=D(1), risk_limit=D(0)), "depth must not be positive"),
        (inputs(risk_limit=D(0), max_single_weight=D(9)), "risk limit must not be zero"),
        (inputs(max_single_weight=D(9)), "item input out of range"),
    ]
    for bad, message in chain:
        with pytest.raises(SimShapeError, match=message):
            score.calculate(bad)


def test_the_request_rejects_values_the_wire_cannot_hold_at_the_edges():
    o = observation()
    good = request(o)
    assert replace(good, n=100).n == 100 and replace(good, n=5000).n == 5000
    assert replace(good, season_seed=-(2**63)).season_seed == -(2**63)
    assert replace(good, horizon_turns=120).horizon_turns == 120
    assert replace(good, end_multiple="999999999999.999999999999").end_multiple
    with pytest.raises(SimShapeError):
        replace(good, end_multiple="1000000000000")
    with pytest.raises(SimShapeError):
        replace(good, sharpe_diff="1000000")
    # a static end multiple of 10^12 or more cannot be sent, as in Java
    huge = replace(o, static_end_multiple=D(10) ** 12)
    with pytest.raises(SimShapeError):
        request(huge)


def test_huge_counts_stay_exact():
    n = 2**40
    assert score.percentile_exact(RankCounts(n, 0, n)) == 1
    assert score.percentile_exact(RankCounts(0, n, n)) == D("0.5")

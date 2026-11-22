"""Checks C1-C8 at their boundaries, bootstrap over worlds, calibration, correlation, careers."""

import numpy as np
import pytest
from validation_support import comp
from validation_tables import (
    HALF_AND_HALF,
    RESULT_ONLY,
    D,
    prow,
    selection_world,
    tables,
    world_rows,
)

from antlab_engine.bots.rng import BotRng, mix
from antlab_engine.sim import money
from antlab_engine.validation.checks import (
    BOOTSTRAP_TAG,
    FORMULA_63,
    FORMULA_S,
    INTERVAL_RULE,
    RESAMPLES,
    ROLE_EVAL,
    ROLE_NAMED,
    ROLE_REF,
    Bootstrap,
    BotRow,
    CheckError,
    career_checks,
    career_subsets,
    evaluate_checks,
    mean_of,
    median_of,
    mid_rank,
    num,
    parts_rows,
    rank_correlation,
    ranks,
    spearman,
    static_calibration,
    world_tables,
)
from antlab_engine.validation.composite import career_l

SEED = 11


def run(worlds, weights=RESULT_ONLY, formula=FORMULA_63, seed=SEED):
    return evaluate_checks(worlds, formula, weights, Bootstrap(seed, len(worlds)))


def random_world(world, result, **kwargs):
    kwargs.setdefault("disciplined", None)
    kwargs.setdefault("news", None)
    return world_rows(world, random=(result, 0), **kwargs)


# ---------------------------------------------------------------------------- arithmetic


def test_mean_median_and_mid_rank_hand_values():
    assert mean_of([D(1), D(2), D(6)]) == D(3)
    assert median_of([D(3), D(1), D(2)]) == D(2)
    assert median_of([D(1), D(2), D(3), D(10)]) == D("2.5")
    assert mid_rank(D(5), [D(1), D(5), D(5), D(9)]) == D(50)
    assert mid_rank(D(0), [D(1), D(2)]) == D(0)
    assert mid_rank(D(9), [D(1), D(2)]) == D(100)
    assert mid_rank(D(1), [D(1), D(1)]) == D(50)


def test_ranks_share_the_average_for_ties():
    assert ranks([D(10), D(20), D(20), D(5)]) == [D(2), D("3.5"), D("3.5"), D(1)]


def test_spearman_hand_values():
    assert spearman([D(1), D(2), D(3)], [D(1), D(3), D(2)]) == D("0.5")
    assert spearman([D(1), D(2), D(3)], [D(9), D(8), D(7)]) == D(-1)
    assert spearman([D(1), D(1), D(2)], [D(1), D(2), D(3)]) == D("0.866025403784")
    assert spearman([D(1), D(1), D(1)], [D(1), D(2), D(3)]) is None


# ---------------------------------------------------------------------------- bootstrap


def test_bootstrap_is_deterministic_and_seeded():
    a, b = Bootstrap(5, 6), Bootstrap(5, 6)
    assert (a.counts == b.counts).all()
    assert not (Bootstrap(6, 6).counts == a.counts).all()
    assert (a.counts.sum(axis=1) == 6).all()
    assert a.counts.shape == (RESAMPLES, 6)


def test_bootstrap_matches_the_documented_draws():
    boot = Bootstrap(SEED, 4, resamples=50)
    rng = BotRng(mix(SEED, BOOTSTRAP_TAG, 4))
    expected = np.zeros((50, 4), dtype=np.int64)
    for row in range(50):
        for _ in range(4):
            expected[row, rng.below(4)] += 1
    assert (boot.counts == expected).all()


def test_bootstrap_resamples_worlds_so_sums_are_multiples_of_the_world_stat():
    boot = Bootstrap(SEED, 4)
    stats = np.array([0, 0, 0, 12], dtype=np.int64)
    sums = boot.counts @ stats
    assert set(np.unique(sums % 12)) == {0}
    assert sums.min() == 0 and sums.max() == 48
    assert boot.worlds == 4


def test_bootstrap_interval_of_equal_stats_is_the_stat():
    lower, upper = Bootstrap(SEED, 3).interval([D(7), D(7), D(7)])
    assert (lower, upper) == (D(7), D(7))


def test_checks_refuse_a_bootstrap_for_another_world_count():
    worlds = tables(random_world(0, 50), random_world(1, 50))
    with pytest.raises(CheckError):
        evaluate_checks(worlds, FORMULA_63, RESULT_ONLY, Bootstrap(SEED, 5))


def test_checks_use_the_world_bootstrap_interval():
    results = ["30", "40", "50", "60"]
    worlds = tables(*(random_world(i, r) for i, r in enumerate(results)))
    checks = run(worlds)
    lower, upper = Bootstrap(SEED, 4).interval([D(r) for r in results])
    assert checks["C1"]["ci"] == [num(lower), num(upper)]
    assert lower > D(30) and upper < D(60)


# ---------------------------------------------------------------------------- C1


@pytest.mark.parametrize(
    ("result", "passed"),
    [("45", True), ("44.99", False), ("55", True), ("55.01", False), ("50", True)],
)
def test_c1_mean_boundaries(result, passed):
    worlds = tables(*(random_world(i, result) for i in range(3)))
    assert run(worlds)["C1"]["passed"] is passed


def test_c1_interval_must_stay_inside_40_60():
    inside = tables(random_world(0, "40"), random_world(1, "60"))
    outside = tables(random_world(0, "39.99"), random_world(1, "60.01"))
    assert [D(x) for x in run(inside)["C1"]["ci"]] == [D(40), D(60)]
    assert run(inside)["C1"]["passed"] is True
    assert run(outside)["C1"]["passed"] is False


# ---------------------------------------------------------------------------- C2


def static_world(world, delta):
    return world_rows(
        world, random=(50, 0), disciplined=None, news=None, statics=((50 + D(delta), 0),)
    )


@pytest.mark.parametrize(
    ("delta", "passed"), [("10", True), ("10.01", False), ("-10", True), ("-10.01", False)]
)
def test_c2_static_middle_boundaries(delta, passed):
    worlds = tables(static_world(0, delta), static_world(1, delta))
    assert run(worlds)["C2"]["passed"] is passed


def test_c2_uses_the_static_median():
    rows = world_rows(
        0,
        random=(50, 0),
        disciplined=None,
        news=None,
        statics=((0, 0), (51, 0), (1000, 0)),
    )
    assert D(run(tables(rows))["C2"]["mean"]) == D(1)


# ---------------------------------------------------------------------------- C3 / C4


def disc_world(world, delta, *, random_each=None):
    return world_rows(
        world,
        random=(50, 0),
        random_each=random_each,
        disciplined=(50 + D(delta), 0),
        news=None,
    )


@pytest.mark.parametrize(("delta", "passed"), [("10", True), ("9.99", False), ("30", True)])
def test_c3_mean_boundary(delta, passed):
    worlds = tables(disc_world(0, delta), disc_world(1, delta))
    assert run(worlds)["C3"]["passed"] is passed


def test_c3_interval_lower_bound_at_zero_fails_and_above_zero_passes():
    zero = tables(disc_world(0, "0"), disc_world(1, "20"))
    above = tables(disc_world(0, "0.01"), disc_world(1, "20"))
    assert D(run(zero)["C3"]["ci"][0]) == 0
    assert run(zero)["C3"]["passed"] is False
    assert D(run(above)["C3"]["ci"][0]) == D("0.01")
    assert run(above)["C3"]["passed"] is True


def test_c3_needs_a_median_percentile_of_75():
    four = ((0, 0), (0, 0), (60, 0), (60, 0))
    half = world_rows(0, random_each=four, disciplined=(40, 0), news=None)
    result = run(tables(half))["C3"]
    assert D(result["mean"]) == 10 and D(result["medianPercentile"]) == 50
    assert result["passed"] is False
    spread = ((0, 0), (10, 0), (20, 0), (100, 0))
    exact = world_rows(0, random_each=spread, disciplined=(25, 0), news=None)
    result = run(tables(exact))["C3"]
    assert D(result["mean"]) == 10 and D(result["medianPercentile"]) == 75
    assert result["passed"] is True


def news_world(world, delta, *, random_each=None):
    return world_rows(
        world,
        random=(50, 0),
        random_each=random_each,
        disciplined=None,
        news=(50 + D(delta), 0),
    )


@pytest.mark.parametrize(("delta", "passed"), [("-10", True), ("-9.99", False), ("-30", True)])
def test_c4_mean_boundary(delta, passed):
    worlds = tables(news_world(0, delta), news_world(1, delta))
    assert run(worlds)["C4"]["passed"] is passed


def test_c4_interval_upper_bound_at_zero_fails():
    zero = tables(news_world(0, "0"), news_world(1, "-20"))
    below = tables(news_world(0, "-0.01"), news_world(1, "-20"))
    assert run(zero)["C4"]["passed"] is False
    assert run(below)["C4"]["passed"] is True


def test_c4_needs_a_median_percentile_of_25():
    edge = ((10, 0), (20, 0), (30, 0), (100, 0))
    low = world_rows(0, random_each=edge, disciplined=None, news=(15, 0))
    result = run(tables(low))["C4"]
    assert D(result["mean"]) == -10 and D(result["medianPercentile"]) == 25
    assert result["passed"] is True
    wide = ((0, 0), (0, 0), (60, 0), (100, 0))
    high = world_rows(0, random_each=wide, disciplined=None, news=(5, 0))
    result = run(tables(high))["C4"]
    assert D(result["mean"]) == -25 and D(result["medianPercentile"]) == 50
    assert result["passed"] is False


# ---------------------------------------------------------------------------- C5 / C6


def dose_world(world, scores):
    """Disciplined, newsmix-25/50/75, news with the given result parts."""
    d, a, b, c, n = scores
    return world_rows(
        world,
        random=(50, 0),
        disciplined=(d, 0),
        news=(n, 0),
        mixes={25: (a, 0), 50: (b, 0), 75: (c, 0)},
    )


def test_c5_needs_strictly_decreasing_world_means():
    good = tables(dose_world(0, (80, 70, 60, 50, 40)), dose_world(1, (80, 70, 60, 50, 40)))
    c5 = run(good)["C5"]
    assert c5["passed"] is True and D(c5["rho"]) == -1 and c5["doses"] == [0, 25, 50, 75, 100]
    flat = tables(dose_world(0, (80, 70, 60, 60, 40)), dose_world(1, (80, 70, 60, 60, 40)))
    assert run(flat)["C5"]["passed"] is False
    bump = tables(dose_world(0, (80, 70, 75, 50, 40)), dose_world(1, (80, 70, 75, 50, 40)))
    assert run(bump)["C5"]["passed"] is False
    rising = tables(dose_world(0, (40, 50, 60, 70, 80)), dose_world(1, (40, 50, 60, 70, 80)))
    assert D(run(rising)["C5"]["rho"]) == 1


def test_c5_uses_world_means_not_single_worlds():
    worlds = tables(dose_world(0, (90, 40, 60, 50, 10)), dose_world(1, (70, 90, 60, 50, 70)))
    c5 = run(worlds)["C5"]
    assert [D(m) for m in c5["means"][:2]] == [D(80), D(65)]


def test_c5_with_fewer_than_three_points_is_unavailable():
    worlds = tables(world_rows(0, random=(50, 0), disciplined=(60, 0), news=(40, 0)))
    c5 = run(worlds)["C5"]
    assert c5["available"] is False and c5["passed"] is None


def test_c5_accepts_the_ci_profile_points():
    rows = world_rows(0, random=(50, 0), disciplined=(80, 0), news=(20, 0), mixes={50: (50, 0)})
    assert run(tables(rows))["C5"]["passed"] is True


@pytest.mark.parametrize(("delta", "passed"), [("0.01", True), ("0", False), ("-1", False)])
def test_c6_noguard_strictly_above_random(delta, passed):
    rows = world_rows(0, random=(50, 0), disciplined=None, news=None, noguard=(50 + D(delta), 0))
    assert run(tables(rows))["C6"]["passed"] is passed


def test_c6_and_c8_are_unavailable_without_their_bots():
    rows = world_rows(0, random=(50, 0), disciplined=None, news=None)
    checks = run(tables(rows))
    for check in ("C2", "C3", "C4", "C6", "C7", "C8"):
        assert checks[check]["available"] is False and checks[check]["passed"] is None


# ---------------------------------------------------------------------------- C7 / C8


def test_c7_flags_a_news_bot_that_wins_on_the_result_parts():
    def world(i, result):
        return world_rows(i, random=(50, 0), disciplined=None, news=(result, 0))

    flagged = run(tables(world(0, 60), world(1, 70)))["C7"]
    clean = run(tables(world(0, 40), world(1, 70)))["C7"]
    assert flagged["flagged"] is True and flagged["passed"] is False
    assert clean["flagged"] is False and clean["passed"] is True
    assert D(clean["ci"][0]) == -10


def test_c7_for_s_averages_return_and_risk_adjusted_parts_only():
    random = (50, 50, 50, 50)
    worlds = tables(
        world_rows(0, random=random, disciplined=None, news=(60, 40, 0, 0)),
        world_rows(1, random=random, disciplined=None, news=(60, 40, 0, 0)),
    )
    weights = (D("0.25"),) * 4
    c7 = run(worlds, weights, FORMULA_S)["C7"]
    assert D(c7["mean"]) == 0
    assert c7["passed"] is True


def test_c7_ignores_the_weights():
    worlds = tables(
        *(world_rows(i, random=(50, 0), disciplined=None, news=(60, 0)) for i in (0, 1))
    )
    one = run(worlds, (D(1), D(0)))["C7"]
    other = run(worlds, (D("0.3"), D("0.7")))["C7"]
    assert D(one["mean"]) == D(other["mean"]) == 10


def pair_world(world, delta, spread=0):
    return world_rows(
        world,
        random=(50, 0),
        disciplined=None,
        news=None,
        redeclare=((50 + D(delta) + spread, 0), (50 + D(delta) - spread, 0)),
        random_bots=2,
    )


def test_c8_pairs_each_redeclare_bot_with_its_random_bot():
    rows = pair_world(0, 5)
    assert [(a.index, b.index) for a, b in tables(rows)[0].redeclare] == [(100, 100), (101, 101)]
    assert D(run(tables(rows))["C8"]["mean"]) == 5


@pytest.mark.parametrize(
    ("delta", "flagged"), [("3", False), ("3.01", True), ("5", True), ("0", False), ("-5", False)]
)
def test_c8_flags_a_mean_above_three_with_a_positive_interval(delta, flagged):
    worlds = tables(pair_world(0, delta), pair_world(1, delta))
    c8 = run(worlds)["C8"]
    assert c8["flagged"] is flagged and c8["passed"] is (not flagged)


def test_c8_interval_lower_bound_at_zero_is_not_flagged():
    worlds = tables(pair_world(0, "0"), pair_world(1, "10"))
    c8 = run(worlds)["C8"]
    assert D(c8["mean"]) == 5 and D(c8["ci"][0]) == 0 and c8["flagged"] is False


def test_a_redeclare_bot_without_a_paired_random_bot_is_refused():
    rows = world_rows(0, random_bots=1, redeclare=((50, 0), (50, 0)))
    with pytest.raises(CheckError):
        world_tables(rows)


def test_named_bots_must_exist_in_every_world_or_none():
    with_news = world_rows(0, news=(20, 0))
    without = world_rows(1, news=None)
    with pytest.raises(CheckError):
        run(tables(with_news, without))


def test_duplicate_named_bots_are_refused():
    rows = [*world_rows(0), prow(0, "news", 1, 1, 1)]
    with pytest.raises(CheckError):
        world_tables(rows)


# ---------------------------------------------------------------------------- weights


def test_scores_are_the_weighted_sum_of_the_parts():
    rows = world_rows(0, random=(80, 20), disciplined=(100, 40), news=(0, 20))
    checks = run(tables(rows), (D("0.3"), D("0.7")))
    assert D(checks["C1"]["mean"]) == 38  # 0.3 x 80 + 0.7 x 20
    assert D(checks["C3"]["mean"]) == 20  # 0.3 x 100 + 0.7 x 40 - 38
    assert D(checks["C4"]["mean"]) == -24  # 0.7 x 20 - 38


def test_weights_must_match_the_parts():
    with pytest.raises(CheckError):
        run(tables(world_rows(0)), (D(1),))


# ---------------------------------------------------------------------------- parts rows


def bot(world, role, kind, index, **kwargs):
    return BotRow(world, role, comp(kind, index, **kwargs))


def reference(world, values):
    return [
        bot(
            world,
            ROLE_REF,
            "random",
            i,
            excess_cagr=str(v),
            sharpe_diff=str(v),
            sortino_diff=str(v),
            calmar_diff=str(v),
            risk="100",
            mdd=str(-v),
            process="50",
        )
        for i, v in enumerate(values)
    ]


def test_s_parts_are_ranked_against_the_reference_bots_only():
    evaluation = [
        bot(0, ROLE_EVAL, "random", 10 + i, excess_cagr="1000", process="40") for i in range(2)
    ]
    static = bot(
        0,
        ROLE_EVAL,
        "static",
        0,
        excess_cagr="5",
        sharpe_diff="15",
        sortino_diff="5",
        calmar_diff="25",
        risk="100",
        mdd="-15",
        process="60",
    )
    parts = {
        (p.kind, p.index): p.parts
        for p in parts_rows(FORMULA_S, [*reference(0, [0, 10, 20]), *evaluation, static])
    }
    assert len(parts) == 3
    third = money.score_divide(D(100), D(3))
    # 5 sits above one of the three reference bots; the two evaluation bots are not in the set
    assert parts[("static", 0)][0] == third
    assert parts[("random", 10)][0] == D(100)
    assert parts[("static", 0)][3] == D(60)


def test_a_reference_bot_cannot_also_be_an_evaluation_bot():
    rows = [*reference(0, [0, 10]), bot(0, ROLE_EVAL, "random", 1)]
    with pytest.raises(CheckError):
        parts_rows(FORMULA_S, rows)


def test_reference_bots_must_be_random_bots_and_sized_as_expected():
    bad = [*reference(0, [0, 10]), bot(0, ROLE_REF, "static", 7)]
    with pytest.raises(CheckError):
        parts_rows(FORMULA_S, bad)
    with pytest.raises(Exception, match="has 5 bots"):
        parts_rows(FORMULA_S, reference(0, [0, 1, 2]), reference_size=5)


def test_s63_parts_are_result_and_process_and_need_a_result():
    rows = [bot(0, ROLE_EVAL, "random", 3, result="61.5", process="40")]
    assert parts_rows(FORMULA_63, rows)[0].parts == (D("61.5"), D(40))
    with pytest.raises(CheckError):
        parts_rows(FORMULA_63, [bot(0, ROLE_EVAL, "random", 3, process="40")])


def test_unknown_roles_and_duplicate_bots_are_refused():
    with pytest.raises(CheckError):
        parts_rows(FORMULA_63, [BotRow(0, "other", comp(result="1"))])
    dup = [bot(0, ROLE_NAMED, "news", 0, result="1"), bot(0, ROLE_NAMED, "news", 0, result="1")]
    with pytest.raises(CheckError):
        parts_rows(FORMULA_63, dup)


# ---------------------------------------------------------------------------- calibration


def static_row(world, result, index=0):
    return bot(world, ROLE_EVAL, "static", index, result=str(result))


def test_static_calibration_passes_for_uniform_results():
    rows = [static_row(i, 5 + 10 * i) for i in range(10)]
    cal = static_calibration(rows)
    assert cal["available"] and D(cal["mean"]) == 50
    assert cal["decileCounts"] == [1] * 10
    assert D(cal["ks"]["statistic"]) == D("0.05")
    assert cal["passed"] is True


def test_static_calibration_fails_for_a_skewed_static_bot():
    cal = static_calibration([static_row(i, 5) for i in range(10)])
    assert D(cal["mean"]) == 5 and cal["passed"] is False
    assert cal["decileCounts"][0] == 10 and cal["ks"]["passed"] is False


def test_static_calibration_ks_uses_the_first_static_per_world():
    rows = [static_row(i, 5 + 10 * i, index=1) for i in range(10)]
    rows += [static_row(i, 99, index=2) for i in range(10)]
    rows += [static_row(i, 5 + 10 * i, index=0) for i in range(10)]
    cal = static_calibration(rows)
    assert D(cal["ks"]["statistic"]) == D("0.05")
    assert cal["worlds"] == 10 and cal["statics"] == 30


def test_static_calibration_without_statics_is_unavailable():
    assert static_calibration([bot(0, ROLE_EVAL, "random", 0, result="50")])["available"] is False


# ---------------------------------------------------------------------------- correlation


def test_rank_correlation_of_a_formula_with_itself_and_its_reverse():
    rows = tuple(prow(0, "random", i, i, 10 - i) for i in range(6))
    same = rank_correlation(rows, (D(1), D(0)), rows, (D(1), D(0)))
    assert same["n"] == 6 and D(same["rho"]) == 1
    reverse = rank_correlation(rows, (D(1), D(0)), rows, (D(0), D(1)))
    assert reverse["n"] == 6 and D(reverse["rho"]) == -1


def test_rank_correlation_only_counts_bots_both_formulas_scored():
    a = tuple(prow(0, "random", i, i, 0) for i in range(4))
    b = tuple(prow(0, "random", i, i, 0) for i in range(2, 6))
    assert rank_correlation(a, (D(1), D(0)), b, (D(1), D(0)))["n"] == 2


# ---------------------------------------------------------------------------- careers


def career_tables(n_worlds=12, bots=3):
    worlds = []
    for w in range(n_worlds):
        each = tuple(((7 * w + 13 * i) % 41 + 30, 0) for i in range(bots))
        worlds.append(
            world_rows(
                w,
                random_each=each,
                disciplined=((w * 5) % 23 + 70, 0),
                news=((w * 3) % 17 + 10, 0),
            )
        )
    return tables(*worlds)


def test_career_subsets_are_seeded_distinct_and_deterministic():
    a = career_subsets(3, 12, 30)
    assert (a == career_subsets(3, 12, 30)).all()
    assert not (a == career_subsets(4, 12, 30)).all()
    assert a.shape == (30, 10)
    assert all(len(set(row)) == 10 for row in a.tolist())
    assert a.min() >= 0 and a.max() < 12
    with pytest.raises(CheckError):
        career_subsets(3, 9, 30)


def test_career_analogue_matches_the_exact_career_l():
    worlds = career_tables(bots=1)
    result = career_checks(worlds, RESULT_ONLY, SEED, subsets=40)
    chosen = career_subsets(SEED, len(worlds), 40)
    scores = [D(str(w.eval_random[0].parts[0])) for w in worlds]
    exact = sorted(career_l([scores[i] for i in row]) for row in chosen.tolist())
    median = (exact[19] + exact[20]) / 2
    assert abs(D(result["medianL"]["random"]) - median) < D("0.000001")
    disc = [D(str(w.named["disciplined"].parts[0])) for w in worlds]
    exact_d = sorted(career_l([disc[i] for i in row]) for row in chosen.tolist())
    assert abs(D(result["medianL"]["disciplined"]) - (exact_d[19] + exact_d[20]) / 2) < D(
        "0.000001"
    )


def test_career_analogue_reports_c1_c3_c4_on_l():
    worlds = tables(
        *(
            world_rows(w, random_bots=3, random=(50, 0), disciplined=(70, 0), news=(30, 0))
            for w in range(12)
        )
    )
    result = career_checks(worlds, RESULT_ONLY, SEED, subsets=50)
    assert result["available"] and result["subsets"] == 50 and result["bots"] == 3
    assert {c: v["passed"] for c, v in result["checks"].items()} == {
        "C1": True,
        "C3": True,
        "C4": True,
    }
    assert D(result["medianL"]["random"]) == 50
    assert D(result["medianL"]["disciplined"]) == 70
    assert D(result["medianL"]["news"]) == 30


def test_career_analogue_needs_ten_worlds():
    result = career_checks(career_tables(9), HALF_AND_HALF, SEED)
    assert result["available"] is False


def test_career_analogue_is_deterministic():
    a = career_checks(career_tables(), HALF_AND_HALF, SEED, subsets=30)
    b = career_checks(career_tables(), HALF_AND_HALF, SEED, subsets=30)
    assert a == b


def test_selection_world_helper_has_the_intended_shape():
    world = tables(selection_world(0, disciplined=(60, 0), news=(40, 0)))[0]
    assert set(world.named) == {"disciplined", "news", "disciplined-noguard", "newsmix-50"}
    assert world.named["newsmix-50"].parts == (D(50), D(0))


def test_a_dose_kind_missing_in_the_first_world_is_not_ignored():
    first = world_rows(0, random=(50, 0), disciplined=(80, 0), news=(20, 0))
    second = world_rows(1, random=(50, 0), disciplined=(80, 0), news=(20, 0), mixes={50: (50, 0)})
    with pytest.raises(CheckError):
        run(tables(first, second))


def test_career_analogue_does_not_depend_on_world_order():
    worlds = career_tables()
    a = career_checks(worlds, HALF_AND_HALF, SEED, subsets=30)
    b = career_checks(tuple(reversed(worlds)), HALF_AND_HALF, SEED, subsets=30)
    assert a == b


def test_interval_rule_matches_the_bootstrap_ranks():
    boot = Bootstrap(SEED, 3)
    assert INTERVAL_RULE["orderStatistics"] == [boot.tail + 1, RESAMPLES - boot.tail]

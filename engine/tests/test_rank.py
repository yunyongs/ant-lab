"""Library `rank`: integer counts of an observed season among N held-still paths."""

import importlib
from decimal import Decimal

import numpy as np
import pytest
from synthetic_months import BASE_DATE, EXT348_REPLAY_START, tiny
from synthetic_panel import REPLAY_MONTHS, asset_names, make_panel, portfolio, random_panel

from antlab_engine.engine_errors import InvalidArgument, PathTooLong
from antlab_engine.projection import (
    Portfolio,
    Rank,
    derive_path_seeds,
    draw_paths,
    project,
    rank,
)
from antlab_engine.projection.evaluate import evaluate
from antlab_engine.projection.rank import sharpe, stable_values

# the package re-exports the function `rank`, which shadows the module of the same name
rank_module = importlib.import_module("antlab_engine.projection.rank")

ASSETS = asset_names(4)
PANEL = random_panel(REPLAY_MONTHS, ASSETS, 11)
TEAM = portfolio("TEAM", ASSETS[:2])
BENCH = portfolio("BENCH", ASSETS[2:])
CASH = Portfolio("CASH", {}, Decimal(1))
LIMIT = Decimal("-0.20")


def run(team=TEAM, bench=BENCH, end="1", diff="0", **kwargs) -> Rank:
    args = {
        "season_seed": 7,
        "horizon": 24,
        "n": 100,
        "replay_start": EXT348_REPLAY_START,
    }
    return rank(
        PANEL,
        BASE_DATE,
        team=team,
        benchmark=bench,
        observed_end_multiple=Decimal(end),
        observed_sharpe_diff=Decimal(diff),
        **{**args, **kwargs},
    )


def brute_values(horizon=24, n=100, seed=7):
    """Team and benchmark value multiples per path, built through the projection library."""
    result = project(
        PANEL,
        BASE_DATE,
        season_seed=seed,
        horizon=horizon,
        portfolios=[TEAM, BENCH],
        risk_limit=LIMIT,
        n=n,
        replay_start=EXT348_REPLAY_START,
    )
    return [p.end_values for p in result.portfolios]


def stable_end_values(horizon=24, n=100, seed=7) -> np.ndarray:
    paths = draw_paths(
        PANEL,
        BASE_DATE,
        derive_path_seeds(seed, n),
        horizon,
        sorted({a for p in (TEAM, BENCH) for a, _ in p.active}),
        EXT348_REPLAY_START,
        rank_module.stationary.DEFAULT_PARAMS,
    )
    column = {a: i for i, a in enumerate(PANEL.assets)}
    book = (tuple((column[a], float(w)) for a, w in TEAM.active), float(TEAM.cash))
    return stable_values(PANEL.factors, paths, [book])[0][:, -1]


def brute_sharpe(values: np.ndarray) -> np.ndarray:
    steps = values[:, 1:] / values[:, :-1] - 1.0
    return steps.mean(axis=1) / steps.std(axis=1, ddof=1)


def between(sorted_values: np.ndarray, k: int) -> Decimal:
    """A decimal strictly between the k-th and (k+1)-th smallest value (k values lie below)."""
    mid = (float(sorted_values[k - 1]) + float(sorted_values[k])) / 2
    assert sorted_values[k - 1] < mid < sorted_values[k]
    return Decimal(mid)


def test_e1_the_same_inputs_give_the_same_counts():
    assert run(end="1.05", diff="0.01") == run(end="1.05", diff="0.01")
    assert run(season_seed=8) != run(season_seed=7)


def test_e2_the_end_multiple_counts_equal_a_brute_force_count_over_the_projection_values():
    team_values, _ = brute_values()
    ends = np.sort(team_values[:, -1])
    for k in (1, 17, 50, 83, 99):
        got = run(end=str(between(ends, k)))
        assert (got.end_multiple.below, got.end_multiple.equal) == (k, 0)
    exact_ends = np.sort(stable_end_values())
    exact = Decimal(float(exact_ends[40]))
    got = run(end=str(exact))
    assert got.end_multiple.below == int(np.count_nonzero(exact_ends < exact_ends[40]))
    assert got.end_multiple.equal == int(np.count_nonzero(exact_ends == exact_ends[40])) >= 1


def test_e2_the_sharpe_difference_counts_equal_a_brute_force_count_with_an_independent_formula():
    team_values, bench_values = brute_values()
    diffs = np.sort(brute_sharpe(team_values) - brute_sharpe(bench_values))
    for k in (1, 30, 51, 99):
        got = run(diff=str(between(diffs, k)))
        assert (got.sharpe_diff.below, got.sharpe_diff.equal) == (k, 0)


def test_e2_sharpe_is_the_sample_mean_over_the_sample_deviation_of_step_returns():
    values = np.array([[1.0, 1.1, 0.99, 1.089, 1.089]])
    steps = np.array([0.1, -0.1, 0.1, 0.0])
    expected = steps.mean() / steps.std(ddof=1)
    assert sharpe(values)[0] == pytest.approx(expected, rel=1e-12)
    # ddof 0 would give a different number
    assert sharpe(values)[0] != pytest.approx(steps.mean() / steps.std(ddof=0), rel=1e-6)


def test_e2_sharpe_is_the_same_whatever_the_other_rows_are():
    team_values, _ = brute_values()
    alone = sharpe(team_values[7:8])
    assert alone[0] == sharpe(team_values)[7]


def test_e3_an_observed_value_outside_the_distribution_ranks_at_the_ends():
    low = run(end="0", diff="-100000")
    assert (low.end_multiple.below, low.end_multiple.equal) == (0, 0)
    assert (low.sharpe_diff.below, low.sharpe_diff.equal) == (0, 0)
    high = run(end="999999999999", diff="100000")
    assert (high.end_multiple.below, high.end_multiple.equal) == (100, 0)
    assert (high.sharpe_diff.below, high.sharpe_diff.equal) == (100, 0)


def test_e4_an_all_cash_team_has_sharpe_zero_and_never_crashes_on_zero_deviation():
    flat = np.ones((5, 13))
    assert sharpe(flat).tolist() == [0.0] * 5
    got = run(team=CASH, bench=CASH, end="1", diff="0")
    assert got.end_multiple == rank_module.Counts(0, 100)
    assert got.sharpe_diff == rank_module.Counts(0, 100)
    # against a risky benchmark the difference is minus the benchmark's Sharpe
    _, bench_values = brute_values()
    against = run(team=CASH, bench=BENCH, end="1", diff="0")
    assert against.sharpe_diff.below + against.sharpe_diff.equal <= 100
    assert against.sharpe_diff.below == int(np.count_nonzero(-brute_sharpe(bench_values) < 0))


def test_e5_a_team_equal_to_the_benchmark_has_difference_zero_on_every_path():
    same = run(team=TEAM, bench=TEAM, diff="0")
    assert (same.sharpe_diff.below, same.sharpe_diff.equal) == (0, 100)
    shifted = run(team=TEAM, bench=TEAM, diff="0.000000000001")
    assert (shifted.sharpe_diff.below, shifted.sharpe_diff.equal) == (100, 0)
    negative = run(team=TEAM, bench=TEAM, diff="-0.000000000001")
    assert (negative.sharpe_diff.below, negative.sharpe_diff.equal) == (0, 0)


def test_e5_the_order_of_the_weights_does_not_matter():
    reversed_weights = Portfolio("TEAM", dict(reversed(list(TEAM.weights.items()))), TEAM.cash)
    assert run(team=reversed_weights, end="1.02", diff="0.01") == run(end="1.02", diff="0.01")


def test_e6_the_paths_are_the_projection_paths_and_never_the_season_path(monkeypatch):
    seen = []
    real = rank_module.draw_paths

    def spy(panel, base_date, seeds, *args, **kwargs):
        seen.append(tuple(seeds))
        return real(panel, base_date, seeds, *args, **kwargs)

    monkeypatch.setattr(rank_module, "draw_paths", spy)
    for season_seed in (-5, 0, 7, 123):
        run(season_seed=season_seed)
        assert seen[-1] == derive_path_seeds(season_seed, 100)
        assert season_seed not in seen[-1]


@pytest.mark.parametrize("horizon", [12, 24, 60, 120])
def test_e7_the_four_horizons_are_accepted(horizon):
    got = run(horizon=horizon)
    assert got.horizon == horizon
    assert got.end_multiple.below + got.end_multiple.equal <= 100


@pytest.mark.parametrize("horizon", [0, 6, 36, 48, 121, True, 12.0, "12"])
def test_e7_other_horizons_are_rejected(horizon):
    with pytest.raises(InvalidArgument):
        run(horizon=horizon)


@pytest.mark.parametrize("n", [0, 99, 5001, True, 100.0, "100"])
def test_the_path_count_is_between_100_and_5000(n):
    with pytest.raises(InvalidArgument):
        run(n=n)


def test_the_path_count_bounds_are_accepted():
    assert run(n=100).n == 100
    assert run(n=5000, horizon=12).n == 5000


def test_invalid_arguments_are_rejected_before_any_path_is_drawn(monkeypatch):
    monkeypatch.setattr(rank_module, "draw_paths", pytest.fail)
    unknown = Portfolio("TEAM", {"KR:ZZZ9": Decimal("0.5")}, Decimal("0.5"))
    with pytest.raises(InvalidArgument):
        run(team=unknown)
    with pytest.raises(InvalidArgument):
        run(bench=unknown)
    with pytest.raises(InvalidArgument):
        run(team="TEAM")
    with pytest.raises(InvalidArgument):
        run(season_seed=2**63)
    for bad in (Decimal("NaN"), Decimal("Infinity"), Decimal("1E+400"), "1", 1, 1.0):
        with pytest.raises(InvalidArgument):
            rank(
                PANEL,
                BASE_DATE,
                season_seed=7,
                horizon=24,
                team=TEAM,
                benchmark=BENCH,
                observed_end_multiple=bad,
                observed_sharpe_diff=Decimal(0),
                replay_start=EXT348_REPLAY_START,
            )
        with pytest.raises(InvalidArgument):
            rank(
                PANEL,
                BASE_DATE,
                season_seed=7,
                horizon=24,
                team=TEAM,
                benchmark=BENCH,
                observed_end_multiple=Decimal(1),
                observed_sharpe_diff=bad,
                replay_start=EXT348_REPLAY_START,
            )


def test_a_history_too_short_for_the_horizon_is_path_too_long():
    panel = random_panel(tiny(), ASSETS, 3)
    with pytest.raises(PathTooLong):
        rank(
            panel,
            BASE_DATE,
            season_seed=1,
            horizon=120,
            team=TEAM,
            benchmark=BENCH,
            observed_end_multiple=Decimal(1),
            observed_sharpe_diff=Decimal(0),
            n=100,
            replay_start=tiny()[0],
        )


def test_a_value_that_reaches_zero_stays_zero_and_has_step_return_zero():
    values = np.array([[1.0, 0.5, 0.0, 0.0, 0.0]])
    result = sharpe(values)
    assert np.isfinite(result).all()
    steps = np.array([-0.5, -1.0, 0.0, 0.0])
    assert result[0] == pytest.approx(steps.mean() / steps.std(ddof=1), rel=1e-12)


def test_the_counts_never_exceed_n_and_the_result_has_no_path_values():
    got = run(end="1.0", diff="0.0")
    for counts in (got.end_multiple, got.sharpe_diff):
        assert counts.below >= 0
        assert counts.equal >= 0
        assert counts.below + counts.equal <= got.n
    assert set(vars(got)) == {
        "projection_version",
        "engine_version",
        "n",
        "horizon",
        "end_multiple",
        "sharpe_diff",
    }


def test_rank_values_equal_the_projection_values_up_to_rounding():
    # rank: 1 + sum(w * (g - 1)); project: cash + sum(w * g). Same numbers, last bits apart.
    team_values, bench_values = brute_values()
    paths = draw_paths(
        PANEL,
        BASE_DATE,
        derive_path_seeds(7, 100),
        24,
        sorted({a for p in (TEAM, BENCH) for a, _ in p.active}),
        EXT348_REPLAY_START,
        rank_module.stationary.DEFAULT_PARAMS,
    )
    column = {a: i for i, a in enumerate(PANEL.assets)}
    books = [
        (tuple((column[a], float(w)) for a, w in p.active), float(p.cash)) for p in (TEAM, BENCH)
    ]
    first, second = stable_values(PANEL.factors, paths, books)
    assert np.allclose(first, team_values, rtol=1e-12, atol=1e-12)
    assert np.allclose(second, bench_values, rtol=1e-12, atol=1e-12)
    assert (first[:, 0] == 1.0).all()


def still_panel():
    assets = [f"KR:S{i:03d}" for i in range(10)]
    table = {a: dict.fromkeys(REPLAY_MONTHS, "0") for a in assets}
    return assets, make_panel(REPLAY_MONTHS, table)


def test_c1_ten_assets_that_never_move_keep_value_one_and_rank_in_the_middle():
    assets, panel = still_panel()
    tenth = {a: Decimal("0.1") for a in assets}
    team = Portfolio("TEAM", tenth, Decimal(0))
    for horizon in (12, 24):
        got = rank(
            panel,
            BASE_DATE,
            season_seed=3,
            horizon=horizon,
            team=team,
            benchmark=CASH,
            observed_end_multiple=Decimal(1),
            observed_sharpe_diff=Decimal(0),
            n=100,
            replay_start=EXT348_REPLAY_START,
        )
        assert got.end_multiple == rank_module.Counts(0, 100)
        assert got.sharpe_diff == rank_module.Counts(0, 100)


def test_c1_the_old_summation_would_have_drifted_but_the_stable_value_does_not():
    assets, panel = still_panel()
    book = (tuple((i, 0.1) for i in range(10)), 0.0)
    paths = np.zeros((3, 12), dtype=np.int64)
    (values,) = stable_values(panel.factors, paths, [book])
    assert (values == 1.0).all()
    assert sharpe(values).tolist() == [0.0, 0.0, 0.0]
    (plain,) = evaluate(panel.factors, paths, [book])
    assert not (plain == 1.0).all()  # the cash + sum(w * g) form drifts on this input


def test_c3_a_step_return_that_overflows_is_rejected_not_turned_into_zero():
    with pytest.raises(InvalidArgument):
        sharpe(np.array([[1.0, 1e-300, 1e10, 1.0, 1.0]]))


def test_a_history_that_gives_every_seed_the_same_path_is_path_too_long(monkeypatch):
    real = rank_module.draw_paths

    def same(panel, base_date, seeds, *args, **kwargs):
        paths = real(panel, base_date, seeds, *args, **kwargs)
        return np.tile(paths[:1], (paths.shape[0], 1))

    monkeypatch.setattr(rank_module, "draw_paths", same)
    with pytest.raises(PathTooLong):
        run()


def test_a_total_loss_stays_exactly_zero_and_never_goes_negative():
    assets = [f"KR:L{i:03d}" for i in range(3)]
    panel = make_panel(REPLAY_MONTHS, {a: dict.fromkeys(REPLAY_MONTHS, "-1") for a in assets})
    book = (((0, 0.3), (1, 0.3), (2, 0.4)), 0.0)
    (values,) = stable_values(panel.factors, np.zeros((2, 12), dtype=np.int64), [book])
    assert (values[:, 1:] == 0.0).all()
    assert (values[:, 0] == 1.0).all()

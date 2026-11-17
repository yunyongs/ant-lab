"""Probability, pool and parameter properties (TB9)."""

import math
import random

import pytest

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import (
    DEFAULT_PARAMS,
    Direction,
    Phase,
    Pool,
    RegimeParams,
    phase_of,
    pool_of,
    pool_probabilities,
)


def test_probabilities_sum_to_one_and_stay_in_range():
    rng = random.Random(7)  # noqa: S311
    for _ in range(2000):
        r, g = rng.uniform(-8, 8), rng.uniform(-8, 8)
        p = pool_probabilities(r, g, rng.uniform(0.1, 6))
        assert sum(p) == pytest.approx(1.0, abs=1e-12)
        assert all(0.0 <= x <= 1.0 for x in p)


@pytest.mark.parametrize("extreme", [1e6, -1e6, 1e300, -1e300])
def test_the_logistic_is_stable_at_extremes(extreme):
    p = pool_probabilities(extreme, -extreme)
    assert all(math.isfinite(x) and 0.0 <= x <= 1.0 for x in p)
    assert sum(p) == pytest.approx(1.0, abs=1e-12)


def test_rate_down_and_growth_down_is_p1():
    assert pool_probabilities(-50.0, -50.0)[0] == pytest.approx(1.0, abs=1e-9)
    assert pool_probabilities(50.0, -50.0)[3] == pytest.approx(1.0, abs=1e-9)
    assert pool_probabilities(50.0, 50.0)[2] == pytest.approx(1.0, abs=1e-9)
    assert pool_probabilities(-50.0, 50.0)[1] == pytest.approx(1.0, abs=1e-9)


@pytest.mark.parametrize("k", [0.0, -1.0, math.nan, math.inf])
def test_k_must_be_positive_and_finite(k):
    with pytest.raises(InvalidArgument):
        pool_probabilities(0.1, 0.1, k)


@pytest.mark.parametrize("bad", [math.nan, math.inf, -math.inf])
def test_scores_must_be_finite(bad):
    with pytest.raises(InvalidArgument):
        pool_probabilities(bad, 0.0)
    with pytest.raises(InvalidArgument):
        pool_probabilities(0.0, bad)


def test_pool_quadrants():
    up, down = Direction.UP, Direction.DOWN
    assert pool_of(down, down) is Pool.P1
    assert pool_of(down, up) is Pool.P2
    assert pool_of(up, up) is Pool.P3
    assert pool_of(up, down) is Pool.P4
    with pytest.raises(InvalidArgument):
        pool_of(Direction.NEUTRAL, up)


@pytest.mark.parametrize("bad", [math.nan, -0.01, 1.01])
def test_phase_needs_a_probability(bad):
    with pytest.raises(InvalidArgument):
        phase_of(bad)


def test_default_parameters_are_the_documented_starting_values():
    p = DEFAULT_PARAMS
    assert (p.k, p.half_life_steps, p.dead_band, p.confirm_steps, p.min_hold_steps) == (
        2.0,
        4,
        0.25,
        2,
        4,
    )
    assert p.phase_cuts == (0.35, 0.50, 0.65)
    assert p.rate_weights == (0.5, 0.5) and p.growth_weights == (0.4, 0.6)
    assert p.quality_max_stale == 4
    assert p.alpha == pytest.approx(1 - 0.5**0.25, abs=1e-15)
    assert phase_of(0.4, p.phase_cuts) is Phase.ALERT


@pytest.mark.parametrize(
    "bad",
    [
        {"k": 0.0},
        {"k": math.nan},
        {"half_life_steps": 0},
        {"half_life_steps": 2.5},
        {"dead_band": -0.1},
        {"confirm_steps": 0},
        {"min_hold_steps": 0},
        {"phase_cuts": (0.5, 0.4, 0.65)},
        {"phase_cuts": (0.0, 0.5, 0.65)},
        {"rate_weights": (0.0, 0.0)},
        {"growth_weights": (-1.0, 2.0)},
        {"growth_weights": (math.nan, 1.0)},
        {"quality_max_stale": 11},
    ],
)
def test_parameters_are_validated(bad):
    with pytest.raises(InvalidArgument):
        RegimeParams(**bad)

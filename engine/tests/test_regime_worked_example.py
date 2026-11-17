"""MACRO_ETF §2.7: the worked example, reproduced (TB1–TB4)."""

import math

import pytest

from antlab_engine.regime import (
    Direction,
    Phase,
    Pool,
    combine_axes,
    phase_of,
    pool_probabilities,
)
from antlab_engine.regime.hysteresis import AxisState, LabelState, advance_axis, advance_label
from antlab_engine.regime.probability import logistic

R, G = 0.20, -0.38


def test_axes_combine_as_in_the_example():
    r, g = combine_axes(0.6, -0.2, -0.5, -0.3)
    assert r == pytest.approx(0.20, abs=1e-12)
    assert g == pytest.approx(-0.38, abs=1e-12)


def test_probabilities_match_the_document_and_the_closed_form():
    p = pool_probabilities(R, G, 2.0)
    assert p == pytest.approx((0.273, 0.128, 0.191, 0.408), abs=5e-4)
    p_down = 1 / (1 + math.exp(2 * R))
    p_up = 1 / (1 + math.exp(-2 * G))
    closed = (
        p_down * (1 - p_up),
        p_down * p_up,
        (1 - p_down) * p_up,
        (1 - p_down) * (1 - p_up),
    )
    assert p == pytest.approx(closed, abs=1e-12)
    assert p_down == pytest.approx(0.401, abs=5e-4)
    assert p_up == pytest.approx(0.319, abs=5e-4)
    assert max(range(4), key=p.__getitem__) == 3


def test_the_top_pool_is_in_the_alert_phase():
    assert phase_of(pool_probabilities(R, G)[3]) is Phase.ALERT


@pytest.mark.parametrize(
    ("value", "phase"),
    [
        (0.0, Phase.WATCH),
        (0.3499999, Phase.WATCH),
        (0.35, Phase.ALERT),
        (0.4999999, Phase.ALERT),
        (0.50, Phase.TRANSITION),
        (0.6499999, Phase.TRANSITION),
        (0.65, Phase.CONVICTION),
        (1.0, Phase.CONVICTION),
    ],
)
def test_phase_cut_points_are_half_open(value, phase):
    assert phase_of(value) is phase


def test_inside_the_dead_band_the_label_stays():
    rate = advance_axis(AxisState(Direction.UP, 0), R, 0.25, 2)
    growth = advance_axis(AxisState(Direction.DOWN, 0), G, 0.25, 2)
    assert (rate.direction, growth.direction) == (Direction.UP, Direction.DOWN)
    kept = advance_label(LabelState(Pool.P4, 1), rate.direction, growth.direction, 30, 4)
    assert kept == LabelState(Pool.P4, 1)


def test_logistic_reference_values():
    assert logistic(0.0) == 0.5
    assert logistic(-0.40) == pytest.approx(0.401312, abs=1e-6)
    assert logistic(-0.76) == pytest.approx(0.318646, abs=1e-6)

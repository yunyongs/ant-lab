"""Hysteresis tables H1–H4 (TB5–TB8): smoothed values fed straight into the state machines."""

from antlab_engine.regime import Direction, Pool
from antlab_engine.regime.hysteresis import (
    AxisState,
    LabelState,
    advance_axis,
    advance_axis_if_fresh,
    advance_label,
)

UP, DOWN = Direction.UP, Direction.DOWN


def run(rates, growths, band=0.25, confirm=2, hold=4):
    rate = growth = label = None
    rows = []
    for step, (r, g) in enumerate(zip(rates, growths, strict=True), start=1):
        rate = advance_axis(rate, r, band, confirm)
        growth = advance_axis(growth, g, band, confirm)
        label = advance_label(label, rate.direction, growth.direction, step, hold)
        rows.append((rate.direction, growth.direction, label.label, label.since_step))
    return rows


H1_R = [0.40, 0.10, -0.30, -0.20, -0.30, -0.40, -0.50]


def test_h1_a_flip_needs_two_consecutive_steps_beyond_the_band():
    rows = run(H1_R, [0.50] * 7)
    assert [r[0] for r in rows] == [UP] * 5 + [DOWN] * 2  # step 4 reset the count
    assert [r[2] for r in rows] == [Pool.P3] * 5 + [Pool.P2] * 2
    assert rows[5][3] == 6 and rows[6][3] == 6
    assert [r[3] for r in rows[:5]] == [1] * 5


H2_R = H1_R + [-0.50] * 5
H2_G = [0.50] * 6 + [-0.30, -0.30, -0.40, -0.40, -0.40, -0.40]


def test_h2_the_label_waits_for_the_hold_and_then_follows_the_quadrant():
    rows = run(H2_R, H2_G)
    assert [r[1] for r in rows] == [UP] * 7 + [DOWN] * 5  # growth flips at step 8
    labels = [r[2] for r in rows]
    assert labels[5:9] == [Pool.P2] * 4  # held through steps 6-9
    assert labels[9:] == [Pool.P1] * 3  # steps 10-12
    assert rows[9][3] == 10


def test_h3_a_change_that_reverts_before_the_lock_ends_never_shows():
    growths = H2_G[:8] + [0.30, 0.30, 0.30, 0.30]
    rows = run(H2_R, growths)
    assert [r[1] for r in rows[7:]] == [DOWN, DOWN, UP, UP, UP]  # back up at step 10
    assert {r[2] for r in rows[5:]} == {Pool.P2}
    assert {r[3] for r in rows[5:]} == {6}


def test_h4_the_dead_band_is_strict():
    rows = run([0.10, -0.25, -0.25, -0.25], [0.5] * 4)
    assert {r[0] for r in rows} == {UP}
    flipped = run([0.10, -0.2501, -0.2501], [0.5] * 3)
    assert flipped[-1][0] is DOWN


def test_the_first_value_sets_the_direction_and_zero_is_up():
    assert advance_axis(None, 0.0, 0.25, 2) == AxisState(UP, 0)
    assert advance_axis(None, -0.0001, 0.25, 2) == AxisState(DOWN, 0)
    assert advance_label(None, DOWN, UP, 9, 4) == LabelState(Pool.P2, 9)


def test_down_flips_up_symmetrically():
    rows = run([-0.5, 0.30, 0.30], [-0.5] * 3)
    assert [r[0] for r in rows] == [DOWN, DOWN, UP]


def test_a_value_back_inside_the_band_resets_the_count():
    rows = run([0.5, -0.30, 0.0, -0.30], [0.5] * 4)
    assert {r[0] for r in rows} == {UP}


def test_confirmation_and_hold_are_parameters():
    rows = run([0.5, -0.30], [0.5, 0.5], confirm=1)
    assert rows[-1][0] is DOWN
    rows = run([0.5, -0.30, -0.30, -0.30], [0.5] * 4, hold=1)
    assert rows[-1][2] is Pool.P2


def test_a_carried_step_neither_counts_nor_resets_the_confirmation():
    one_against = AxisState(UP, 1)
    assert advance_axis_if_fresh(one_against, -0.5, False, 0.25, 2) == one_against
    assert advance_axis_if_fresh(one_against, 0.5, False, 0.25, 2) == one_against
    assert advance_axis_if_fresh(one_against, -0.5, True, 0.25, 2) == AxisState(DOWN, 0)
    assert advance_axis_if_fresh(one_against, 0.5, True, 0.25, 2) == AxisState(UP, 0)
    assert advance_axis_if_fresh(None, None, False, 0.25, 2) is None
    assert advance_axis_if_fresh(None, 0.3, True, 0.25, 2) == AxisState(UP, 0)

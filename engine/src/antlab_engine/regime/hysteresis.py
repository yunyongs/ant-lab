"""Axis direction and pool label with dead band, confirmation and minimum hold (§2.4 step 7)."""

from dataclasses import dataclass

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.model import Direction, Pool
from antlab_engine.regime.probability import pool_of


@dataclass(frozen=True, slots=True)
class AxisState:
    direction: Direction  # UP or DOWN
    against: int  # consecutive steps beyond the dead band on the other side


@dataclass(frozen=True, slots=True)
class LabelState:
    label: Pool
    since_step: int  # first step of the current label


def advance_axis(
    state: AxisState | None, value: float, dead_band: float, confirm_steps: int
) -> AxisState:
    """One step. The first value sets the direction (UP at 0); a flip needs `confirm_steps`
    consecutive values strictly beyond the dead band on the other side, and any other value
    resets the count."""
    if state is None:
        return AxisState(Direction.UP if value >= 0 else Direction.DOWN, 0)
    if state.direction is Direction.UP:
        beyond = value < -dead_band
        other = Direction.DOWN
    elif state.direction is Direction.DOWN:
        beyond = value > dead_band
        other = Direction.UP
    else:
        raise InvalidArgument("an axis direction is UP or DOWN")
    if not beyond:
        return AxisState(state.direction, 0)
    count = state.against + 1
    return AxisState(other, 0) if count >= confirm_steps else AxisState(state.direction, count)


def advance_axis_if_fresh(
    state: AxisState | None,
    value: float | None,
    fresh: bool,
    dead_band: float,
    confirm_steps: int,
) -> AxisState | None:
    """`advance_axis` only when this step brought new data: a carried value is not evidence, so
    the confirmation count neither grows nor resets on a carried step."""
    if not fresh or value is None:
        return state
    return advance_axis(state, value, dead_band, confirm_steps)


def advance_label(
    state: LabelState | None, rate_dir: Direction, growth_dir: Direction, step: int, hold: int
) -> LabelState:
    """One step. The first label has no lock. A new label is held for `hold` steps counting its
    own first step; a quadrant change inside the lock is delayed, not cancelled."""
    quadrant = pool_of(rate_dir, growth_dir)
    if state is None:
        return LabelState(quadrant, step)
    if quadrant is state.label or step - state.since_step < hold:
        return state
    return LabelState(quadrant, step)

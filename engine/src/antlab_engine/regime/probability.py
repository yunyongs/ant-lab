"""Pool probabilities and phase (MACRO_ETF §2.4 steps 6 and 8)."""

import math

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.model import Direction, Phase, Pool


def logistic(x: float) -> float:
    """1 / (1 + exp(-x)) without overflow for either sign."""
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


def pool_probabilities(r: float, g: float, k: float = 2.0) -> tuple[float, float, float, float]:
    """P1..P4 from the smoothed rate score R and growth score G; the four always sum to 1.

    p_down = sigma(-kR) is the chance that rates fall, p_up = sigma(kG) that growth rises. The
    complements are computed as sigma of the opposite sign, which stays exact at the extremes.
    """
    if not (math.isfinite(r) and math.isfinite(g)):
        raise InvalidArgument("R and G must be finite")
    if not (math.isfinite(k) and k > 0):
        raise InvalidArgument("k must be finite and positive")
    p_down, p_up = logistic(-k * r), logistic(k * g)
    not_down, not_up = logistic(k * r), logistic(-k * g)
    return (p_down * not_up, p_down * p_up, not_down * p_up, not_down * not_up)


def pool_of(rate_dir: Direction, growth_dir: Direction) -> Pool:
    """The quadrant of the two axis directions (rate down / growth down is P1)."""
    if Direction.NEUTRAL in (rate_dir, growth_dir):
        raise InvalidArgument("a pool needs an UP or DOWN direction on both axes")
    if rate_dir is Direction.DOWN:
        return Pool.P1 if growth_dir is Direction.DOWN else Pool.P2
    return Pool.P3 if growth_dir is Direction.UP else Pool.P4


def phase_of(probability: float, cuts: tuple[float, float, float] = (0.35, 0.50, 0.65)) -> Phase:
    """Half-open intervals: below the first cut WATCH, [c1, c2) ALERT, [c2, c3) TRANSITION."""
    if not (math.isfinite(probability) and 0.0 <= probability <= 1.0):
        raise InvalidArgument("a probability must be inside [0, 1]")
    if probability < cuts[0]:
        return Phase.WATCH
    if probability < cuts[1]:
        return Phase.ALERT
    if probability < cuts[2]:
        return Phase.TRANSITION
    return Phase.CONVICTION

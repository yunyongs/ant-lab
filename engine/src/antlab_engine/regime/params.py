"""Scoring parameters: the MACRO_ETF §2.4 starting values, fixed (no tuning hooks).

The z-score window, warm-up and clip are constants of `zscore.py`, not parameters.
"""

import math
from dataclasses import dataclass

from antlab_engine.engine_errors import InvalidArgument

REGIME_VERSION = "m2-rg-v1"  # bump on any parameter or rule change


def _weights(name: str, weights: tuple[float, float]) -> None:
    ok = len(weights) == 2 and all(math.isfinite(w) and w >= 0 for w in weights)
    if not ok or sum(weights) <= 0:
        raise InvalidArgument(f"{name} needs two finite non-negative weights that are not both 0")


@dataclass(frozen=True, slots=True)
class RegimeParams:
    k: float = 2.0
    half_life_steps: int = 4
    dead_band: float = 0.25
    confirm_steps: int = 2
    min_hold_steps: int = 4
    phase_cuts: tuple[float, float, float] = (0.35, 0.50, 0.65)
    rate_weights: tuple[float, float] = (0.5, 0.5)  # US, KR
    growth_weights: tuple[float, float] = (0.4, 0.6)  # US, KR
    quality_max_stale: int = 4  # more stale rate/growth indicators than this fails the gate

    def __post_init__(self) -> None:
        if not (math.isfinite(self.k) and self.k > 0):
            raise InvalidArgument("k must be finite and positive")
        if not (isinstance(self.half_life_steps, int) and self.half_life_steps >= 1):
            raise InvalidArgument("half_life_steps must be an integer of at least 1")
        if not (math.isfinite(self.dead_band) and self.dead_band >= 0):
            raise InvalidArgument("dead_band must be finite and not negative")
        for name in ("confirm_steps", "min_hold_steps"):
            value = getattr(self, name)
            if not (isinstance(value, int) and value >= 1):
                raise InvalidArgument(f"{name} must be an integer of at least 1")
        cuts = self.phase_cuts
        if len(cuts) != 3 or not (0 < cuts[0] < cuts[1] < cuts[2] < 1):
            raise InvalidArgument("phase_cuts must be three increasing values inside (0, 1)")
        _weights("rate_weights", self.rate_weights)
        _weights("growth_weights", self.growth_weights)
        if not (isinstance(self.quality_max_stale, int) and 0 <= self.quality_max_stale <= 10):
            raise InvalidArgument("quality_max_stale must be an integer from 0 to 10")

    @property
    def alpha(self) -> float:
        return 1.0 - 0.5 ** (1.0 / self.half_life_steps)


DEFAULT_PARAMS = RegimeParams()

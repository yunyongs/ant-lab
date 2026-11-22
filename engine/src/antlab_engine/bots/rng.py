"""Integer-only random stream of the reference bots, over SplitMix64.

No floats and no `random` module: every draw is an integer, so a stream is the same on every
platform and a bot's decisions can be replayed from its seed.
"""

from antlab_engine.bootstrap.splitmix64 import GAMMA, MASK, SplitMix64

KIND_RANDOM = 1
KIND_STATIC = 2  # reserved: the static bot reuses the random bot seed of its index
KIND_DISCIPLINED = 3
KIND_NEWS = 4

_SIGN = 1 << 63


def to_signed(value: int) -> int:
    value &= MASK
    return value - (1 << 64) if value >= _SIGN else value


def mix(*parts: int) -> int:
    """Signed 64-bit seed from integers (master seed, world seed, kind code, index).

    Every part goes through one SplitMix64 step, so neighbouring indexes share no stream.
    """
    state = GAMMA
    for part in parts:
        state = SplitMix64(to_signed(state ^ (part & MASK))).next()
    return to_signed(state)


class BotRng:
    def __init__(self, seed: int) -> None:
        self._gen = SplitMix64(seed)

    def below(self, n: int) -> int:
        """Uniform value in [0, n) by rejection over the full 64 bits (no modulo bias)."""
        if n < 1:
            raise ValueError("n must be at least 1")
        limit = (1 << 64) - ((1 << 64) % n)
        while True:
            r = self._gen.next()
            if r < limit:
                return r % n

    def between(self, low: int, high: int) -> int:
        """Uniform value in [low, high]."""
        return low + self.below(high - low + 1)

    def chance(self, numerator: int, denominator: int) -> bool:
        return self.below(denominator) < numerator

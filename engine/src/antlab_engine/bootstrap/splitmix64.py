"""SplitMix64 generator, a bit-exact port of the Java reference (antlab.engine.SplitMix64).

Python integers are unbounded, so every step that can exceed 64 bits is masked back.
"""

MASK = (1 << 64) - 1
GAMMA = 0x9E3779B97F4A7C15
MULTIPLIER_1 = 0xBF58476D1CE4E5B9
MULTIPLIER_2 = 0x94D049BB133111EB
INT_LIMIT = 1 << 31


def _require_int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{name} must be an int")
    return value


class SplitMix64:
    def __init__(self, seed: int) -> None:
        _require_int(seed, "seed")
        if not -(1 << 63) <= seed < (1 << 63):
            raise ValueError("seed must fit in a signed 64-bit integer")
        self._state = seed & MASK

    def next(self) -> int:
        """Next unsigned 64-bit value, 0 <= v < 2**64."""
        self._state = (self._state + GAMMA) & MASK
        z = self._state
        z = ((z ^ (z >> 30)) * MULTIPLIER_1) & MASK
        z = ((z ^ (z >> 27)) * MULTIPLIER_2) & MASK
        return z ^ (z >> 31)

    def next_int(self, n: int) -> int:
        """Uniform value in [0, n) for 1 <= n < 2**31, using rejection sampling."""
        _require_int(n, "n")
        if not 1 <= n < INT_LIMIT:
            raise ValueError("n must satisfy 1 <= n < 2**31")
        while True:
            r = self.next() >> 33
            v = r % n
            if r - v + (n - 1) < INT_LIMIT:
                return v

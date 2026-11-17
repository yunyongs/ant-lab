"""Path seeds derived from the season seed with domain separation (part of m2-pj-v1)."""

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection.model import MAX_PATHS

PROJECTION_DOMAIN = 0x50524F4A45435431
SEED_MIN = -(1 << 63)
SEED_MAX = (1 << 63) - 1
_HALF = 1 << 63
_FULL = 1 << 64


def check_seed(seed: object) -> int:
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise InvalidArgument("seed must be an integer")
    if not SEED_MIN <= seed <= SEED_MAX:
        raise InvalidArgument("seed must fit in a signed 64-bit integer")
    return seed


def derive_path_seeds(season_seed: int, n: int) -> tuple[int, ...]:
    """`n` (at most MAX_PATHS) distinct signed 64-bit seeds, none equal to the season seed.

    Distinct seeds do not guarantee distinct paths; `project` rejects a history that allows one.
    """
    check_seed(season_seed)
    if isinstance(n, bool) or not isinstance(n, int) or n < 0:
        raise InvalidArgument("n must be a non-negative integer")
    if n > MAX_PATHS:
        raise InvalidArgument(f"n must be at most {MAX_PATHS}")
    rng = SplitMix64(season_seed ^ PROJECTION_DOMAIN)
    seen: set[int] = {season_seed}
    result: list[int] = []
    while len(result) < n:
        value = rng.next()
        signed = value - _FULL if value >= _HALF else value
        if signed in seen:
            continue
        seen.add(signed)
        result.append(signed)
    return tuple(result)

from fractions import Fraction

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.bootstrap.stationary import DEFAULT_PARAMS, BootstrapParams, _draw_length

DRAWS = 10_000
SEED = 20261117
P_CONTINUE = Fraction(5, 6)


def sample(params: BootstrapParams = DEFAULT_PARAMS, room: int = 120) -> list[int]:
    rng = SplitMix64(SEED)
    return [_draw_length(rng, params, room) for _ in range(DRAWS)]


def truncated_mean(cap: int) -> Fraction:
    return 6 * (1 - P_CONTINUE**cap)


def test_mean_and_frequencies_follow_the_truncated_geometric():
    lengths = sample()
    mean = Fraction(sum(lengths), DRAWS)
    expected = truncated_mean(24)
    assert abs(mean - expected) <= expected * Fraction(5, 100)
    for k in range(1, 13):
        observed = Fraction(lengths.count(k), DRAWS)
        pmf = P_CONTINUE ** (k - 1) / 6
        assert abs(observed - pmf) <= Fraction(15, 1000), k
    top = Fraction(lengths.count(24), DRAWS)
    assert abs(top - P_CONTINUE**23) <= Fraction(5, 1000)
    assert max(lengths) == 24


def test_one_turn_of_room_gives_one_and_draws_nothing():
    rng = SplitMix64(7)
    assert [_draw_length(rng, DEFAULT_PARAMS, 1) for _ in range(20)] == [1] * 20
    assert rng.next() == SplitMix64(7).next()


def test_room_caps_the_length():
    assert max(sample(room=5)) == 5
    assert min(sample(room=5)) == 1


def test_mean_one_always_gives_one():
    assert set(sample(BootstrapParams(mean_block_length=1))) == {1}


def test_the_cap_is_never_exceeded():
    assert max(sample(BootstrapParams(max_block_length=8))) == 8

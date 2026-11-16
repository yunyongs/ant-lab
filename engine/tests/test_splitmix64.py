import pytest

from antlab_engine.bootstrap.splitmix64 import MASK, SplitMix64

JAVA_SEED_1234567 = [
    6457827717110365317,
    3203168211198807973,
    9817491932198370423,
    4593380528125082431,
    16408922859458223821,
]


def test_reference_values_from_java():
    rng = SplitMix64(1234567)
    assert [rng.next() for _ in JAVA_SEED_1234567] == JAVA_SEED_1234567


def test_rejection_branch_consumes_two_values():
    # Seed 0, n = 2**30 + 1: the first 31-bit value 1896895516 is rejected (a plain % n would give
    # 823153691), so next_int draws twice and the following next() is the third value.
    rng = SplitMix64(0)
    assert rng.next_int(2**30 + 1) == 926699317
    assert rng.next() == 487617019471545679


def scripted(values):
    """A generator whose next() returns the given raw 64-bit values in order."""
    rng = SplitMix64(0)
    queue = iter(values)
    rng.next = lambda: next(queue)
    return rng


def test_rejection_boundary_for_n_3():
    # n = 3 divides 2**31 + 1, so the last block [2**31 - 3, 2**31 - 1] is complete but
    # [2**31 - 2, 2**31] is not: r = 2**31 - 3 is accepted, r = 2**31 - 2 and above is redrawn.
    accepted = scripted([(2**31 - 3) << 33])
    assert accepted.next_int(3) == (2**31 - 3) % 3
    redrawn = scripted([(2**31 - 2) << 33, 5 << 33])
    assert redrawn.next_int(3) == 5 % 3
    redrawn_top = scripted([(2**31 - 1) << 33, 7 << 33])
    assert redrawn_top.next_int(3) == 7 % 3


def test_uses_the_top_31_bits():
    assert scripted([(1 << 33) | 0x1FFFFFFFF]).next_int(2**30) == 1
    assert scripted([(123456 << 33) | 0x1FFFFFFFF]).next_int(2**30) == 123456


def test_next_int_one_is_always_zero():
    rng = SplitMix64(7)
    assert {rng.next_int(1) for _ in range(100)} == {0}


@pytest.mark.parametrize("bad", [0, -3, 2**31, 2**40])
def test_next_int_rejects_out_of_range_n(bad):
    with pytest.raises(ValueError, match="n must"):
        SplitMix64(7).next_int(bad)


@pytest.mark.parametrize("bad", [True, 2.0, "3", None])
def test_next_int_rejects_non_int_n(bad):
    with pytest.raises(TypeError):
        SplitMix64(7).next_int(bad)


def test_next_int_stays_in_range_and_hits_every_value():
    for n in range(1, 18):
        rng = SplitMix64(n)
        seen = set()
        for _ in range(10_000):
            v = rng.next_int(n)
            assert 0 <= v < n
            seen.add(v)
        assert seen == set(range(n))


def test_same_seed_same_sequence_different_seed_differs():
    a, b, c = SplitMix64(-99), SplitMix64(-99), SplitMix64(-98)
    first = [a.next() for _ in range(50)]
    assert first == [b.next() for _ in range(50)]
    assert first != [c.next() for _ in range(50)]


def test_values_are_unsigned_64_bit():
    for seed in (0, 1, -1, -(2**63), 2**63 - 1, 1234567):
        rng = SplitMix64(seed)
        for _ in range(1000):
            assert 0 <= rng.next() <= MASK


@pytest.mark.parametrize(
    ("seed", "first"),
    [
        (-1, 16490336266968443936),
        (-(2**63), 5196802822362493915),
        (2**63 - 1, 3055647633038352039),
    ],
)
def test_boundary_seeds_match_java_first_value(seed, first):
    # First values taken from the Java reference (long arithmetic, unsigned print).
    assert SplitMix64(seed).next() == first


@pytest.mark.parametrize("seed", [-(2**63) - 1, 2**63, 2**64])
def test_out_of_range_seed_is_rejected(seed):
    with pytest.raises(ValueError, match="seed"):
        SplitMix64(seed)


@pytest.mark.parametrize("seed", [True, False, 1.0, "1", None])
def test_non_int_seed_is_rejected(seed):
    with pytest.raises(TypeError):
        SplitMix64(seed)

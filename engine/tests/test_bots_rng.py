"""BotRng: integer draws, no modulo bias, and seeds that separate bots."""

import inspect

import pytest

from antlab_engine.bots import rng as rng_module
from antlab_engine.bots.rng import BotRng, mix

MASK = (1 << 64) - 1


def test_mix_is_pinned_and_separates_every_part():
    assert mix(1, 2, 3, 4) == -4465474967912709124
    assert mix(-5, 2**63 - 1, 1, 0) == 3602706631490656224
    base = mix(1, 2, 3, 4)
    assert len({base, mix(2, 2, 3, 4), mix(1, 3, 3, 4), mix(1, 2, 4, 4), mix(1, 2, 3, 5)}) == 5
    assert -(1 << 63) <= base < (1 << 63)


def test_neighbouring_indexes_share_no_leading_draws():
    firsts = [BotRng(mix(1, 2, 1, i)).below(10**6) for i in range(200)]
    assert len(set(firsts)) > 190


def test_stream_is_pinned():
    r = BotRng(42)
    assert [r.below(10) for _ in range(8)] == [3, 1, 8, 4, 0, 2, 5, 8]


def test_below_rejects_the_biased_tail():
    # 2**64 % 3 == 1, so the single value 2**64 - 1 must be redrawn instead of reduced.
    r = BotRng(0)
    draws = iter([MASK, 5])
    r._gen.next = lambda: next(draws)  # type: ignore[method-assign]
    assert r.below(3) == 5 % 3


def test_below_range_and_arguments():
    r = BotRng(9)
    assert {r.below(1) for _ in range(20)} == {0}
    assert all(0 <= r.below(7) < 7 for _ in range(500))
    assert {r.between(3, 5) for _ in range(200)} == {3, 4, 5}
    with pytest.raises(ValueError):
        r.below(0)


def test_below_is_roughly_uniform():
    r = BotRng(123)
    counts = [0, 0, 0]
    for _ in range(6000):
        counts[r.below(3)] += 1
    assert all(1700 < c < 2300 for c in counts)


def test_chance_uses_an_integer_threshold():
    r = BotRng(5)
    hits = sum(r.chance(1, 10) for _ in range(5000))
    assert 350 < hits < 650
    assert not any(BotRng(5).chance(0, 7) for _ in range(50))
    assert all(BotRng(5).chance(7, 7) for _ in range(50))


def test_no_floats_and_no_random_module():
    source = inspect.getsource(rng_module)
    assert "import random" not in source
    assert "float(" not in source
    assert " / " not in source


def test_chance_stream_is_pinned():
    r = BotRng(42)
    bits = "".join("1" if r.chance(1, 3) else "0" for _ in range(40))
    assert bits == "0011010000000000011110100100010000000000"

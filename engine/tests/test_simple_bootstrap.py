import random
import re
from datetime import date, datetime

import pytest
from path_rules import violations
from synthetic_months import BASE_DATE, month_range, sample_shape

from antlab_engine.bootstrap.simple import generate_months, replay_upper_bound
from antlab_engine.engine_errors import InvalidArgument, PathTooLong

SEEDS = [*range(-500, 500), -(2**63), 2**63 - 1]
CUT = date(2012, 4, 1)
RESUME = date(2023, 1, 1)


def following(d: date) -> date:
    return date(d.year + d.month // 12, d.month % 12 + 1, 1)


def test_input_order_does_not_change_the_result():
    shape = sample_shape()
    first = generate_months(shape, BASE_DATE, 24, 42)
    assert generate_months(shape, BASE_DATE, 24, 42) == first
    shuffled = list(shape)
    random.Random(5).shuffle(shuffled)  # noqa: S311 - test-only shuffle
    assert generate_months(shuffled, BASE_DATE, 24, 42) == first


def test_1000_seeds_times_three_lengths_have_no_rule_violation():
    shape = sample_shape()
    present = set(shape)
    for length in (12, 24, 60):
        for seed in SEEDS:
            path = generate_months(shape, BASE_DATE, length, seed)
            where = f"length={length} seed={seed}"
            assert len(path) == length, where
            assert violations(path, BASE_DATE, length, 6) == [], where
            assert len(set(path)) == length, where
            for k, m in enumerate(path):
                assert m.month == k % 12 + 1, where
                assert date(2007, 1, 1) <= m < date(2026, 1, 1), where
                assert m in present, where
                if k % 6 != 0:
                    assert m == following(path[k - 1]), where
                    assert not (path[k - 1] == CUT and m == RESUME), where
                elif k > 0:
                    assert m != following(path[k - 1]), where


def test_different_seeds_give_different_paths_and_all_first_slot_candidates_appear():
    shape = sample_shape()
    paths = {tuple(generate_months(shape, BASE_DATE, 12, seed)) for seed in SEEDS}
    first_blocks = {p[0] for p in paths}
    assert len(paths) >= 50
    assert len(first_blocks) == 8


def test_months_outside_the_replay_range_do_not_change_the_result():
    wide = [*month_range(date(2006, 1, 1), date(2006, 11, 1)), *sample_shape()]
    wide += month_range(date(2026, 2, 1), date(2026, 6, 1))
    plain = sample_shape()
    for length in (12, 24, 60):
        for seed in SEEDS:
            got = generate_months(wide, BASE_DATE, length, seed)
            assert got == generate_months(plain, BASE_DATE, length, seed)


def test_a_block_needs_all_six_months():
    # 2009-01 has only five months present, 2010-01 has exactly six: only 2010-01 is a candidate.
    months = [
        *month_range(date(2009, 1, 1), date(2009, 5, 1)),
        *month_range(date(2010, 1, 1), date(2010, 6, 1)),
        *month_range(date(2011, 7, 1), date(2011, 12, 1)),
    ]
    expected = [*month_range(date(2010, 1, 1), date(2010, 6, 1))]
    expected += month_range(date(2011, 7, 1), date(2011, 12, 1))
    for seed in SEEDS:
        assert generate_months(months, BASE_DATE, 12, seed) == expected


def test_base_date_year_never_appears():
    shape = sample_shape()
    base_2025 = date(2025, 1, 2)
    for seed in SEEDS:
        for m in generate_months(shape, base_2025, 12, seed):
            assert m < date(2025, 1, 1)


def test_replay_upper_bound_is_january_first_of_the_base_year():
    assert replay_upper_bound(date(2026, 1, 2)) == date(2026, 1, 1)
    assert replay_upper_bound(date(2025, 12, 31)) == date(2025, 1, 1)
    with pytest.raises(TypeError):
        replay_upper_bound(datetime(2026, 1, 2))


def test_too_long_and_invalid_lengths():
    shape = sample_shape()
    for bad in (61, 120):
        with pytest.raises(PathTooLong) as info:
            generate_months(shape, BASE_DATE, bad, 1)
        assert info.value.code == "PATH_TOO_LONG"
        assert not re.search(r"20\d\d", str(info.value))
    for bad in (0, -1, 10, 13, 59):
        with pytest.raises(InvalidArgument) as info:
            generate_months(shape, BASE_DATE, bad, 1)
        assert info.value.code == "INVALID_ARGUMENT"


def test_length_exactly_at_the_limit_is_allowed():
    assert len(generate_months(sample_shape(), BASE_DATE, 60, 1)) == 60


def test_not_enough_history_is_path_too_long():
    recent = month_range(date(2023, 1, 1), date(2025, 12, 1))
    with pytest.raises(PathTooLong) as info:
        generate_months(recent, BASE_DATE, 60, 1)
    assert not re.search(r"20\d\d", str(info.value))
    assert len(generate_months(recent, BASE_DATE, 12, 1)) == 12


def test_join_ban_can_exhaust_the_candidates():
    # 2009 only: the second slot's sole candidate 2009-07 directly follows the first block.
    one_year = month_range(date(2009, 1, 1), date(2009, 12, 1))
    with pytest.raises(PathTooLong):
        generate_months(one_year, BASE_DATE, 12, 1)
    with pytest.raises(PathTooLong):
        generate_months([], BASE_DATE, 12, 1)


def test_malformed_month_lists_fail_before_the_length_check():
    shape = sample_shape()
    not_first = [*shape, date(2009, 1, 2)]
    duplicate = [*shape, date(2009, 1, 1)]
    for bad in (not_first, duplicate):
        for length in (12, 61):
            with pytest.raises(ValueError, match="invalid month list") as info:
                generate_months(bad, BASE_DATE, length, 1)
            assert "2009" not in str(info.value)


def test_not_first_of_month_is_rejected_even_outside_the_range():
    with pytest.raises(ValueError, match="day 1"):
        generate_months([*sample_shape(), date(2006, 5, 17)], BASE_DATE, 12, 1)


def test_duplicate_outside_the_range_is_ignored():
    shape = sample_shape()
    assert generate_months([*shape, date(2006, 12, 1)], BASE_DATE, 12, 1) == generate_months(
        shape, BASE_DATE, 12, 1
    )


def test_malformed_months_in_any_position_fail():
    with pytest.raises(TypeError):
        generate_months([*sample_shape(), datetime(2009, 1, 1)], BASE_DATE, 12, 1)
    with pytest.raises(TypeError):
        generate_months([*sample_shape(), "2009-01-01"], BASE_DATE, 12, 1)


@pytest.mark.parametrize("bad_length", [True, 12.0, "12", None])
def test_non_int_length_is_rejected(bad_length):
    with pytest.raises(TypeError):
        generate_months(sample_shape(), BASE_DATE, bad_length, 1)


@pytest.mark.parametrize("bad_seed", [True, 1.5, "1", None])
def test_non_int_seed_is_rejected(bad_seed):
    with pytest.raises(TypeError):
        generate_months(sample_shape(), BASE_DATE, 12, bad_seed)


def test_out_of_range_seed_is_rejected():
    with pytest.raises(ValueError, match="seed"):
        generate_months(sample_shape(), BASE_DATE, 12, 2**63)


def test_non_date_base_date_is_rejected():
    with pytest.raises(TypeError):
        generate_months(sample_shape(), datetime(2026, 1, 2), 12, 1)
    with pytest.raises(TypeError):
        generate_months(sample_shape(), None, 12, 1)

from datetime import date

import pytest
from path_rules import violations as legacy_violations
from synthetic_months import BASE_DATE, month_range, sample_shape

from antlab_engine.bootstrap import rules, simple, stationary
from antlab_engine.bootstrap.simple import generate_months
from antlab_engine.bootstrap.stationary import Block, Tier

M2 = stationary.ENGINE_VERSION
P0 = simple.ENGINE_VERSION
START = date(2007, 1, 1)


def m2(months, blocks, length=None):
    return rules.violations(
        months,
        BASE_DATE,
        len(months) if length is None else length,
        engine_version=M2,
        replay_start=START,
        blocks=blocks,
    )


def two_blocks():
    months = [
        *month_range(date(2010, 1, 1), date(2010, 6, 1)),
        *month_range(date(2012, 7, 1), date(2012, 12, 1)),
    ]
    blocks = [Block(0, 6, 6, Tier.ALIGNED), Block(6, 6, 6, Tier.ALIGNED)]
    return months, blocks


def test_p0_mode_matches_the_java_mirror_on_real_and_broken_paths():
    for seed in range(20):
        path = generate_months(sample_shape(), BASE_DATE, 24, seed)
        assert rules.violations(path, BASE_DATE, 24, engine_version=P0) == []
        assert legacy_violations(path, BASE_DATE, 24, 6) == []
    broken = month_range(date(2010, 1, 1), date(2010, 6, 1))
    broken = [*broken, *broken]
    assert rules.violations(broken, BASE_DATE, 12, engine_version=P0) == ["S4", "REUSE"]
    assert legacy_violations(broken, BASE_DATE, 12, 6) == ["S4", "REUSE"]


def test_p0_mode_names_each_rule():
    good = generate_months(sample_shape(), BASE_DATE, 12, 3)
    assert rules.violations(good[:11], BASE_DATE, 12, engine_version=P0) == ["S1_COUNT"]
    in_base_year = [*good[:11], date(2026, 12, 1)]
    assert "S1_RANGE" in rules.violations(in_base_year, BASE_DATE, 12, engine_version=P0)
    shifted = [*good[1:], good[0]]
    assert "S4" in rules.violations(shifted, BASE_DATE, 12, engine_version=P0)
    twice = [*good[:11], good[0]]
    assert "REUSE" in rules.violations(twice, BASE_DATE, 12, engine_version=P0)


def test_m2_mode_accepts_a_valid_layout():
    months, blocks = two_blocks()
    assert m2(months, blocks) == []


def test_duplicate_month_is_reuse():
    months, _ = two_blocks()
    months[6:] = months[:6]
    blocks = [Block(0, 6, 6, Tier.ALIGNED), Block(6, 6, 6, Tier.FREE)]
    assert m2(months, blocks) == ["REUSE"]


def test_month_in_the_base_year_is_out_of_range():
    months, blocks = two_blocks()
    months[6:] = month_range(date(2026, 7, 1), date(2026, 12, 1))
    assert m2(months, blocks) == ["S1_RANGE"]


def test_count_mismatch_is_named():
    months, blocks = two_blocks()
    assert "S1_COUNT" in m2(months, blocks, length=24)


def test_gap_inside_a_block_is_named():
    months, blocks = two_blocks()
    months[3] = date(2010, 9, 1)
    assert m2(months, blocks) == ["BLOCK_GAP"]


def test_aligned_block_starting_in_the_wrong_month_is_a_tier_violation():
    months, blocks = two_blocks()
    months[6:] = month_range(date(2012, 9, 1), date(2013, 2, 1))
    assert m2(months, blocks) == ["TIER"]
    blocks[1] = Block(6, 6, 6, Tier.FREE)
    assert m2(months, blocks) == []


def test_near_allows_one_month_either_way_and_not_two():
    months, _ = two_blocks()
    near = [Block(0, 6, 6, Tier.NEAR), Block(6, 6, 6, Tier.NEAR)]
    months[6:] = month_range(date(2012, 6, 1), date(2012, 11, 1))
    assert m2(months, near) == []
    months[6:] = month_range(date(2012, 8, 1), date(2013, 1, 1))
    assert m2(months, near) == []
    months[6:] = month_range(date(2012, 9, 1), date(2013, 2, 1))
    assert m2(months, near) == ["TIER"]
    months[6:] = month_range(date(2012, 5, 1), date(2012, 10, 1))
    assert m2(months, near) == ["TIER"]


def test_a_block_continuing_the_previous_one_is_a_join_violation_unless_it_is_a_join_tier():
    months = month_range(date(2010, 1, 1), date(2010, 12, 1))
    blocks = [Block(0, 6, 6, Tier.ALIGNED), Block(6, 6, 6, Tier.FREE)]
    assert m2(months, blocks) == ["BLOCK_JOIN"]
    joined = [Block(0, 6, 6, Tier.ALIGNED), Block(6, 1, 1, Tier.JOIN), Block(7, 5, 5, Tier.FREE)]
    assert "TIER" not in m2(months, joined)
    long_join = [Block(0, 6, 6, Tier.ALIGNED), Block(6, 6, 6, Tier.JOIN)]
    assert m2(months, long_join) == ["TIER"]


def test_a_join_block_that_does_not_follow_the_previous_month_is_a_tier_violation():
    months, _ = two_blocks()
    blocks = [Block(0, 6, 5, Tier.ALIGNED), Block(5, 1, 1, Tier.JOIN), Block(6, 6, 6, Tier.ALIGNED)]
    months[5] = date(2011, 3, 1)
    assert m2(months, blocks) == ["TIER"]


def test_a_broken_block_layout_is_named():
    months, _ = two_blocks()
    assert "BLOCK_LAYOUT" in m2(months, [Block(0, 6, 6, Tier.ALIGNED)])
    assert "BLOCK_LAYOUT" in m2(months, [Block(0, 6, 6, Tier.ALIGNED), Block(7, 5, 5, Tier.FREE)])


def test_unusable_blocks_are_named_without_indexing_the_month_list():
    months, blocks = two_blocks()
    for extra in (
        Block(12, 1, 0, Tier.FREE),  # zero length at the end of the path
        Block(12, 1, -1, Tier.FREE),  # negative length at the end of the path
        Block(13, 1, 1, Tier.FREE),  # starts after the path end
        Block(-1, 1, 1, Tier.FREE),  # negative start
        Block(11, 1, 2, Tier.FREE),  # runs past the path end
    ):
        assert m2(months, [*blocks, extra]) == ["BLOCK_LAYOUT"], extra
    assert m2(months, [Block(0, 6, 0, Tier.ALIGNED), *blocks[1:]]) == ["BLOCK_LAYOUT"]
    assert m2(months, [Block(0, 6, -3, Tier.ALIGNED)]) == ["BLOCK_LAYOUT"]
    assert m2(months, [Block(12, 6, 6, Tier.ALIGNED)]) == ["BLOCK_LAYOUT"]


def test_unknown_engine_version_is_rejected():
    with pytest.raises(ValueError):
        rules.violations([], BASE_DATE, 0, engine_version="other")

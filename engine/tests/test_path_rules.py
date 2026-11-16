from datetime import date

from path_rules import violations
from synthetic_months import BASE_DATE, run


def test_valid_12_turn_path_has_no_violation():
    ok = [*run(2009, 1, 6), *run(2011, 7, 6)]
    assert violations(ok, BASE_DATE, 12, 6) == []
    assert violations(ok, BASE_DATE, 12) == []


def test_hand_golden_shape_passes_without_a_block_length():
    golden = [*run(2009, 1, 4), *run(2010, 5, 3), *run(2008, 8, 3)]
    assert violations(golden, BASE_DATE, 10) == []


def test_wrong_row_count_is_s1_count():
    assert "S1_COUNT" in violations([*run(2009, 1, 6), *run(2011, 7, 6)], BASE_DATE, 24)


def test_out_of_range_month_is_s1_range():
    early = [*run(2006, 12, 1), *run(2007, 1, 5), *run(2011, 7, 6)]
    assert "S1_RANGE" in violations(early, BASE_DATE, 12)
    late = [*run(2009, 1, 6), *run(2025, 7, 6)]
    assert violations(late, BASE_DATE, 12) == []
    assert "S1_RANGE" in violations(late, date(2025, 1, 2), 12)


def test_wrong_calendar_month_at_turn_13_is_s4():
    bad = [*run(2009, 1, 6), *run(2011, 7, 6), *run(2010, 2, 6), *run(2008, 8, 6)]
    assert "S4" in violations(bad, BASE_DATE, 24)


def test_reused_month_is_reuse():
    bad = [*run(2009, 1, 6), *run(2011, 7, 6), *run(2009, 1, 6), *run(2011, 7, 6)]
    assert "REUSE" in violations(bad, BASE_DATE, 24)


def test_crossing_the_history_gap_inside_a_block_is_block_gap():
    bad = [*run(2012, 1, 4), *run(2023, 1, 2), *run(2011, 7, 6)]
    assert "BLOCK_GAP" in violations(bad, BASE_DATE, 12, 6)


def test_joining_blocks_in_history_order_is_block_join():
    bad = run(2009, 1, 12)
    assert violations(bad, BASE_DATE, 12, 6) == ["BLOCK_JOIN"]
    assert violations(bad, BASE_DATE, 12) == []


def test_december_to_january_counts_as_consecutive():
    bad = [*run(2009, 1, 6), *run(2009, 7, 6)]
    assert violations(bad, BASE_DATE, 12, 6) == ["BLOCK_JOIN"]
    across_year = [*run(2008, 7, 6), *run(2009, 1, 6)]
    assert "BLOCK_JOIN" in violations(across_year, BASE_DATE, 12, 6)

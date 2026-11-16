"""Cross-check of the fast generator against the brute-force reference, plus independent
recomputation of candidate sets and exact-boundary overlap cases (audit C2, C3, C4)."""

from datetime import date
from fractions import Fraction

import stationary_reference as ref
from synthetic_months import (
    BASE_DATE,
    ext348,
    half_recent,
    history,
    month_range,
    tiny,
)

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.bootstrap.stationary import DEFAULT_PARAMS, BootstrapParams, generate
from antlab_engine.engine_errors import PathTooLong

NAMES = ("sample", "ext348", "gappy", "tiny", "two_januaries")
THRESHOLDS = (Fraction(1), Fraction(1, 2), Fraction(1, 4), Fraction(0), Fraction(3, 10))
LENGTHS = (12, 24, 60, 120)
CASES = 2000


def fast_tuple(path):
    blocks = tuple((b.start_turn, b.drawn_length, b.length, b.tier.value[0]) for b in path.blocks)
    return path.months, blocks, path.overlap_months, path.overlap_met, path.attempt


def random_case(rng: SplitMix64):
    name = NAMES[rng.next_int(len(NAMES))]
    months, replay_start = history(name)
    drop = rng.next_int(4) * 10
    months = [m for m in months if rng.next_int(100) >= drop]
    length = LENGTHS[rng.next_int(4)]
    seed = rng.next() - (1 << 63) if rng.next_int(5) else rng.next_int(100)
    kind = rng.next_int(4)
    if kind == 0:
        recent = []
    elif kind == 1:
        recent = [m for m in ext348() if rng.next_int(2) == 0]
    elif kind == 2:
        recent = half_recent()
    else:
        recent = [m for m in months if rng.next_int(5) == 0]
    threshold = THRESHOLDS[rng.next_int(5)]
    return months, replay_start, length, seed, recent, threshold


def test_fast_generator_equals_the_brute_force_reference_on_2000_cases():
    rng = SplitMix64(777)
    compared = 0
    tiers = set()
    for case in range(CASES):
        months, replay_start, length, seed, recent, threshold = random_case(rng)
        try:
            expected = ref.generate(
                months, BASE_DATE, length, seed, recent, threshold, replay_start
            )
        except ref.TooLong:
            expected = None
        try:
            got = generate(
                months,
                BASE_DATE,
                length,
                seed,
                recent_months=recent,
                max_overlap=threshold,
                replay_start=replay_start,
            )
        except PathTooLong:
            got = None
        if expected is None or got is None:
            assert expected is None and got is None, (case, "only one side raised")
            continue
        assert fast_tuple(got) == expected, case
        compared += 1
        tiers |= {b.tier.value for b in got.blocks}
    assert compared > 800
    assert {"ALIGNED", "NEAR", "FREE", "JOIN"} <= tiers


def test_reference_agrees_with_the_tracked_vectors_on_a_tiny_history():
    months = tiny()
    for seed in range(20):
        got = generate(months, BASE_DATE, 12, seed)
        assert fast_tuple(got) == ref.generate(months, BASE_DATE, 12, seed)


# ---- independent candidate sets (C3) ----


def traced(months, length, seed, recent=(), threshold=Fraction(1), replay_start=date(2007, 1, 1)):
    trace: list[tuple[int, int, int, tuple[int, ...]]] = []
    path = stationary._generate(
        months, BASE_DATE, length, seed, recent, threshold, replay_start, DEFAULT_PARAMS, trace
    )
    return path, [entry for entry in trace if entry[0] == path.attempt]


def avail_of(months, replay_start):
    lo = ref.idx(replay_start)
    hi = ref.idx(date(BASE_DATE.year, 1, 1))
    return {ref.idx(m) for m in months if lo <= ref.idx(m) < hi}


def test_recorded_candidate_counts_equal_independent_set_operations():
    seen_counts = set()
    for name, length in (("sample", 60), ("gappy", 24), ("ext348", 60), ("two_januaries", 12)):
        months, replay_start = history(name)
        avail = avail_of(months, replay_start)
        for seed in range(60):
            path, entries = traced(months, length, seed, replay_start=replay_start)
            indexes = [ref.idx(m) for m in path.months]
            for block in path.blocks:
                used = set(indexes[: block.start_turn])
                cont = indexes[block.start_turn - 1] + 1 if block.start_turn else None
                rows = [e for e in entries if e[1] == block.start_turn]
                for _, _, ell, counts in rows:
                    sets = ref.candidate_sets(avail, ell, used, cont, block.start_turn % 12 + 1)
                    assert counts == tuple(len(sets[t]) for t in "ANF")[: len(counts)]
                    seen_counts.add(counts)
    assert {1, 2, 3} <= {len(c) for c in seen_counts}


def test_december_counts_as_near_a_january_turn_and_january_a_december_turn():
    # Starts: one January, two Decembers, one February, three Marches (all single months).
    starts = [(2008, 1), (2009, 12), (2010, 12), (2011, 2), (2012, 3), (2013, 3), (2014, 3)]
    months = [date(y, m, 1) for y, m in starts]
    avail = {ref.idx(m) for m in months}
    pool = stationary._pool(sorted(avail), set())
    used = bytearray(len(pool.run) - 1)
    for turn_month0, tier, counts in ((0, "NEAR", [1, 4]), (11, "NEAR", [2, 3])):
        got_counts: list[int] = []
        got_tier, candidates = stationary._choose_tier(
            pool, 1, turn_month0, used, -1, DEFAULT_PARAMS, got_counts
        )
        sets = ref.candidate_sets(avail, 1, set(), None, turn_month0 + 1)
        assert (got_tier.value, got_counts) == (tier, counts)
        assert got_counts == [len(sets[t]) for t in "ANF"][: len(got_counts)]
        assert candidates == sets["N"]


def test_two_and_three_candidates_around_the_minimum_of_three():
    months = [date(y, m, 1) for y, m in ((2008, 1), (2009, 1), (2010, 1), (2011, 4), (2012, 7))]
    pool = stationary._pool(sorted(ref.idx(m) for m in months), set())
    used = bytearray(len(pool.run) - 1)
    counts: list[int] = []
    tier, _ = stationary._choose_tier(pool, 1, 0, used, -1, DEFAULT_PARAMS, counts)
    assert (tier.value, counts) == ("ALIGNED", [3])
    for i, flag in enumerate(used):
        assert not flag, i
    # Mark one January used: two ALIGNED candidates are not enough, NEAR also has 2, FREE has 4.
    used[ref.idx(date(2008, 1, 1)) - pool.base] = 1
    counts = []
    tier, candidates = stationary._choose_tier(pool, 1, 0, used, -1, DEFAULT_PARAMS, counts)
    assert (tier.value, counts, len(candidates)) == ("FREE", [2, 2, 4], 4)
    # With a minimum of two the same state stops at ALIGNED.
    counts = []
    two = BootstrapParams(min_candidates=2)
    tier, candidates = stationary._choose_tier(pool, 1, 0, used, -1, two, counts)
    assert (tier.value, counts, len(candidates)) == ("ALIGNED", [2], 2)


def test_overlap_filter_keeps_only_the_minimum_and_changes_the_candidate_count():
    shrunk = 0
    for name, length in (("ext348", 60), ("sample", 24)):
        months, replay_start = history(name)
        avail = avail_of(months, replay_start)
        recent = {ref.idx(m) for m in half_recent()}
        for seed in range(40):
            path, entries = traced(months, length, seed, half_recent(), replay_start=replay_start)
            indexes = [ref.idx(m) for m in path.months]
            for block in path.blocks:
                if block.tier is stationary.Tier.JOIN:
                    continue
                used = set(indexes[: block.start_turn])
                cont = indexes[block.start_turn - 1] + 1 if block.start_turn else None
                sets = ref.candidate_sets(
                    avail, block.length, used, cont, block.start_turn % 12 + 1
                )
                before = sets[block.tier.value[0]]
                kept = ref.min_overlap(before, block.length, recent)
                assert indexes[block.start_turn] in kept
                shrunk += len(kept) < len(before)
    assert shrunk > 50


# ---- exact thresholds (C4) ----


def tiny_run(recent, threshold, **kwargs):
    return generate(tiny(), BASE_DATE, 12, 5, recent_months=recent, max_overlap=threshold, **kwargs)


def test_overlap_equal_to_the_limit_is_met_on_the_first_attempt():
    # tiny() has exactly 12 months, so every attempt uses all of them: overlap = |recent|.
    recent = month_range(date(2010, 1, 1), date(2010, 6, 1))
    path = tiny_run(recent, Fraction(1, 2))
    assert (path.overlap_months, path.overlap_met, path.attempt) == (6, True, 0)
    full = tiny_run(tiny(), Fraction(1))
    assert (full.overlap_months, full.overlap_met, full.attempt) == (12, True, 0)


def test_overlap_one_above_the_limit_is_not_met():
    recent = month_range(date(2010, 1, 1), date(2010, 6, 1))
    path = tiny_run(recent, Fraction(5, 12))
    assert (path.overlap_months, path.overlap_met, path.attempt) == (6, False, 0)
    assert not tiny_run(tiny(), Fraction(11, 12)).overlap_met


def test_non_integer_limits_are_floored():
    recent = month_range(date(2010, 1, 1), date(2010, 6, 1))
    assert not tiny_run(recent, Fraction(11, 24)).overlap_met  # 5.5 -> 5
    assert tiny_run(recent, Fraction(13, 24)).overlap_met  # 6.5 -> 6
    assert tiny_run(recent, Fraction(6, 12)).overlap_met  # exactly 6
    assert not tiny_run(recent, Fraction(59, 120)).overlap_met  # 5.9 -> 5


def test_no_recent_months_and_a_zero_threshold_is_met_with_zero_overlap():
    path = tiny_run((), Fraction(0))
    assert (path.overlap_months, path.overlap_met, path.attempt) == (0, True, 0)
    outside = tiny_run([date(1999, 5, 1)], Fraction(0))
    assert (outside.overlap_months, outside.overlap_met, outside.attempt) == (0, True, 0)


def test_one_recent_month_with_a_zero_threshold_is_never_met_on_a_full_history():
    path = tiny_run([date(2010, 3, 1)], Fraction(0))
    assert (path.overlap_months, path.overlap_met, path.attempt) == (1, False, 0)


def test_an_attempt_that_equals_the_limit_stops_the_search_even_if_a_later_one_is_lower():
    # Found by search: attempt 1 reaches exactly 15 = floor(1/4 x 60); all 32 attempts reach 14.
    seed = 41
    months, replay_start = history("sample")
    path = generate(
        months,
        BASE_DATE,
        60,
        seed,
        recent_months=half_recent(),
        max_overlap=Fraction(1, 4),
        replay_start=replay_start,
    )
    never_stop = ref.generate(
        months, BASE_DATE, 60, seed, half_recent(), Fraction(-1), replay_start
    )
    assert (path.overlap_months, path.overlap_met, path.attempt) == (15, True, 1)
    assert never_stop[2] == 14  # running all 32 attempts would end lower
    assert fast_tuple(path) == ref.generate(
        months, BASE_DATE, 60, seed, half_recent(), Fraction(1, 4), replay_start
    )


def test_best_effort_result_keeps_the_first_attempt_among_equal_overlaps():
    months, replay_start = history("ext348")
    path = generate(
        months,
        BASE_DATE,
        24,
        3,
        recent_months=ext348(),
        max_overlap=Fraction(0),
        replay_start=replay_start,
    )
    assert (path.overlap_months, path.overlap_met, path.attempt) == (24, False, 0)
    assert path.months == generate(months, BASE_DATE, 24, 3, replay_start=replay_start).months

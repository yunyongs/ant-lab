import json
import os
import subprocess
import sys
from datetime import date, datetime
from fractions import Fraction

import pytest
from conftest import ENGINE_DIR
from synthetic_months import (
    BASE_DATE,
    EXT348_REPLAY_START,
    ext348,
    half_recent,
    history,
    month_range,
    sample_shape,
    tiny,
    two_januaries,
)

from antlab_engine.bootstrap import rules, stationary
from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.bootstrap.stationary import (
    DEFAULT_PARAMS,
    ENGINE_VERSION,
    BootstrapParams,
    Tier,
    generate,
)
from antlab_engine.engine_errors import InvalidArgument, PathTooLong

SEEDS = list(range(200))
EDGE_SEEDS = [-(2**63), -2, -1, 14, 1234567, 2**63 - 1]
CASES = [
    ("sample", 12),
    ("sample", 24),
    ("sample", 60),
    ("ext348", 60),
    ("ext348", 120),
    ("gappy", 12),
    ("gappy", 24),
]
TIERS = (Tier.ALIGNED, Tier.NEAR, Tier.FREE)


def run(name: str, length: int, seed: int, **kwargs):
    months, replay_start = history(name)
    return generate(months, BASE_DATE, length, seed, replay_start=replay_start, **kwargs)


def broken(name: str, path) -> list[str]:
    _, replay_start = history(name)
    return rules.violations(
        path.months,
        BASE_DATE,
        len(path.months),
        engine_version=ENGINE_VERSION,
        replay_start=replay_start,
        blocks=path.blocks,
    )


def following(d: date) -> date:
    return date(d.year + d.month // 12, d.month % 12 + 1, 1)


def virtual_turn_month(turn: int) -> int:
    return turn % 12 + 1


# ---- determinism ----


def test_same_input_gives_the_same_path_for_200_seeds_and_every_history():
    for name, length in CASES:
        for seed in [*SEEDS, *EDGE_SEEDS]:
            assert run(name, length, seed) == run(name, length, seed), (name, length, seed)


def test_input_order_of_months_and_recent_months_does_not_matter():
    recent = half_recent()
    for name, length in CASES:
        months, replay_start = history(name)
        orders = [months[::-1], months[::2] + months[1::2]]
        for seed in range(25):
            first = generate(
                months, BASE_DATE, length, seed, recent_months=recent, replay_start=replay_start
            )
            for order in orders:
                again = generate(
                    order,
                    BASE_DATE,
                    length,
                    seed,
                    recent_months=recent[::-1],
                    replay_start=replay_start,
                )
                assert again == first, (name, length, seed)


def test_other_hash_seeds_give_the_same_json():
    code = (
        "import json, sys\n"
        "from synthetic_months import BASE_DATE, history, half_recent\n"
        "from fractions import Fraction\n"
        "from antlab_engine.bootstrap.stationary import generate\n"
        "out = []\n"
        "for name, length in (('sample', 60), ('ext348', 120), ('gappy', 24), ('tiny', 12)):\n"
        "    months, start = history(name)\n"
        "    for seed in range(5):\n"
        "        p = generate(months, BASE_DATE, length, seed, replay_start=start,\n"
        "                     recent_months=half_recent(), max_overlap=Fraction(1, 2))\n"
        "        blocks = [[b.start_turn, b.drawn_length, b.length, b.tier] for b in p.blocks]\n"
        "        out.append([[m.isoformat() for m in p.months], blocks,\n"
        "                    p.overlap_months, p.attempt])\n"
        "json.dump(out, sys.stdout)\n"
    )
    outputs = []
    for hash_seed in ("1", "2"):
        env = {**os.environ, "PYTHONHASHSEED": hash_seed, "PYTHONPATH": str(ENGINE_DIR / "tests")}
        result = subprocess.run(  # noqa: S603
            [sys.executable, "-c", code], capture_output=True, text=True, check=False, env=env
        )
        assert result.returncode == 0, result.stderr
        outputs.append(result.stdout)
    assert outputs[0] == outputs[1]
    assert len(json.loads(outputs[0])) == 20


# ---- invariants ----


def test_every_generated_path_satisfies_the_rules():
    for name, length in CASES:
        for seed in [*SEEDS, *EDGE_SEEDS]:
            path = run(name, length, seed)
            where = (name, length, seed)
            assert len(path.months) == length, where
            assert path.engine_version == ENGINE_VERSION
            assert broken(name, path) == [], where
            assert path.blocks[0].start_turn == 0, where
            assert sum(b.length for b in path.blocks) == length, where
            assert all(1 <= b.length <= b.drawn_length <= 24 for b in path.blocks), where


def test_consecutive_blocks_never_continue_each_other_except_at_a_join():
    for name, length in CASES:
        for seed in SEEDS:
            path = run(name, length, seed)
            for block in path.blocks[1:]:
                joined = path.months[block.start_turn] == following(
                    path.months[block.start_turn - 1]
                )
                assert joined == (block.tier is Tier.JOIN), (name, length, seed)


def test_every_block_is_a_run_of_real_consecutive_months():
    path = run("ext348", 120, 99)
    for block in path.blocks:
        for t in range(block.start_turn + 1, block.start_turn + block.length):
            assert path.months[t] == following(path.months[t - 1])


# ---- calendar alignment ----


def tier_from_counts(counts: tuple[int, ...]) -> Tier | None:
    for tier, count in zip(TIERS, counts, strict=False):
        if count >= DEFAULT_PARAMS.min_candidates:
            return tier
    for tier, count in zip(TIERS, counts, strict=False):
        if count > 0:
            return tier
    return None


def traced(name: str, length: int, seed: int, **kwargs):
    months, replay_start = history(name)
    trace: list[tuple[int, int, int, tuple[int, ...]]] = []
    path = stationary._generate(
        months,
        BASE_DATE,
        length,
        seed,
        kwargs.get("recent_months", ()),
        kwargs.get("max_overlap", Fraction(1)),
        replay_start,
        DEFAULT_PARAMS,
        trace,
    )
    return path, [entry for entry in trace if entry[0] == path.attempt]


def test_the_recorded_tier_is_the_lowest_one_that_reached_three_candidates():
    seen = set()
    for name, length in [("sample", 60), ("gappy", 24), ("sample", 24), ("ext348", 60)]:
        for seed in range(150):
            path, entries = traced(name, length, seed)
            for block in path.blocks:
                mine = [e for e in entries if e[1] == block.start_turn]
                assert [e[2] for e in mine] == list(range(block.drawn_length, block.length - 1, -1))
                for _, _, _, counts in mine[:-1]:
                    assert tier_from_counts(counts) is None
                expected = tier_from_counts(mine[-1][3]) or Tier.JOIN
                assert block.tier is expected, (name, length, seed, block)
                seen.add(block.tier)
    assert {Tier.ALIGNED, Tier.NEAR, Tier.FREE} <= seen


def test_aligned_share_on_348_months_is_measured_and_high():
    months, replay_start = history("ext348")
    aligned = turns = 0
    for seed in range(1000):
        path = generate(months, BASE_DATE, 60, seed, replay_start=replay_start)
        for turn, month in enumerate(path.months):
            aligned += month.month == virtual_turn_month(turn)
            turns += 1
    share = Fraction(aligned, turns)
    print(f"aligned turn share on ext348, 1000 seeds x 60: {float(share):.4f}")
    assert share >= Fraction(95, 100)


def test_realised_block_length_mean_is_close_to_the_drawn_mean():
    months, replay_start = history("ext348")
    lengths = [
        b.length
        for seed in range(500)
        for b in generate(months, BASE_DATE, 120, seed, replay_start=replay_start).blocks
    ]
    mean = Fraction(sum(lengths), len(lengths))
    print(f"realised block length mean on ext348 (500 x 120): {float(mean):.3f}")
    assert mean >= 5


def test_turn_zero_is_a_january_when_enough_januaries_exist():
    for name, length in [("ext348", 60), ("sample", 60), ("sample", 24)]:
        for seed in range(300):
            assert run(name, length, seed).months[0].month == 1, (name, seed)


def test_ladder_falls_back_to_near_and_free_and_shrinks_on_a_small_history():
    seen_tiers = set()
    shrunk = False
    for seed in range(100):
        path = generate(two_januaries(), BASE_DATE, 12, seed)
        assert len(set(path.months)) == 12
        seen_tiers |= {b.tier for b in path.blocks}
        shrunk |= any(b.length < b.drawn_length for b in path.blocks)
    assert {Tier.NEAR, Tier.FREE} <= seen_tiers
    assert shrunk


def test_hand_traced_paths_on_two_januaries():
    # History: 2010-01..06 and 2011-01..06 (12 months), length 12, no recent months.
    # Raw draws (SplitMix64 next() >> 33), taken in order; "coin" = draw % 6 != 0 extends a block,
    # "pick" = draw % number of kept candidates.
    #
    # seed 0: d1..d9 % 6 = 4 1 2 2 3 2 1 5 0 -> drawn 9; no start has a run of 9, 8 or 7;
    #   length 6: only the two Januaries qualify (ALIGNED 2, NEAR 2, FREE 2, none reaches 3), so
    #   the first non-empty tier ALIGNED is used; d10 % 2 = 0 -> 2010-01..06.
    #   Block 2 (turn 6): coins d11..d15 % 6 = 4 2 1 4 1 -> reaches the room cap 6; July has no
    #   candidate, NEAR none, FREE leaves only 2011-01; pick d16 % 1 -> 2011-01..06.
    # seed 14: d1..d4 % 6 = 4 4 3 0 -> drawn 4; ALIGNED has 2 (the Januaries), NEAR 4 (+ the two
    #   Februaries) -> NEAR; d5 % 4 = 0 -> 2010-01..04.
    #   Block 2 (turn 4, May): coins d6 % 6 = 4, d7 % 6 = 0 -> drawn 2; 2010-05 would continue
    #   the previous block, so ALIGNED has 1 (2011-05), NEAR 2, FREE 5 -> FREE; d8 % 5 = 0 ->
    #   2011-01..02.
    #   Block 3 (turn 6): coins d9..d12 % 6 = 1 3 2 0 -> drawn 4; the only run of 4 is 2011-03,
    #   which continues the previous block -> shrink to 3: FREE has 2011-04 only; pick ->
    #   2011-04..06.
    #   Block 4 (turn 9): coins d14, d15 % 6 = 5 5 -> room cap 3; no run of 3 left -> shrink to 2:
    #   2010-05..06 (FREE). Block 5 (turn 11): room 1, no coin; the last month 2011-03 (FREE).
    expected = {
        0: "2010-01 2010-02 2010-03 2010-04 2010-05 2010-06 "
        "2011-01 2011-02 2011-03 2011-04 2011-05 2011-06",
        14: "2010-01 2010-02 2010-03 2010-04 2011-01 2011-02 "
        "2011-04 2011-05 2011-06 2010-05 2010-06 2011-03",
    }
    blocks = {
        0: [(0, 9, 6, Tier.ALIGNED), (6, 6, 6, Tier.FREE)],
        14: [
            (0, 4, 4, Tier.NEAR),
            (4, 2, 2, Tier.FREE),
            (6, 4, 3, Tier.FREE),
            (9, 3, 2, Tier.FREE),
            (11, 1, 1, Tier.FREE),
        ],
    }
    for seed, months in expected.items():
        path = generate(two_januaries(), BASE_DATE, 12, seed)
        assert " ".join(f"{m:%Y-%m}" for m in path.months) == months, seed
        assert [(b.start_turn, b.drawn_length, b.length, b.tier) for b in path.blocks] == blocks[
            seed
        ]


# ---- capacity ----


def test_tiny_history_can_be_used_up_exactly_once():
    seen = set()
    for seed in range(100):
        path = generate(tiny(), BASE_DATE, 12, seed)
        assert sorted(path.months) == tiny()
        seen |= {b.tier for b in path.blocks}
    assert Tier.JOIN in seen
    with pytest.raises(PathTooLong):
        generate(tiny(), BASE_DATE, 24, 1)


def subset(months: list, size: int, rng: SplitMix64) -> list:
    pool = list(months)
    for i in range(len(pool) - 1, 0, -1):
        j = rng.next_int(i + 1)
        pool[i], pool[j] = pool[j], pool[i]
    return pool[:size]


def test_a_path_exists_exactly_when_enough_months_are_replayable():
    rng = SplitMix64(88)
    base = month_range(EXT348_REPLAY_START, date(2025, 12, 1))
    for length in (12, 24):
        for size in (length - 1, length, length + 5):
            for _ in range(60):
                months = subset(base, size, rng)
                if size < length:
                    with pytest.raises(PathTooLong):
                        generate(months, BASE_DATE, length, 5, replay_start=EXT348_REPLAY_START)
                    continue
                path = generate(months, BASE_DATE, length, 5, replay_start=EXT348_REPLAY_START)
                assert len(set(path.months)) == length
                assert set(path.months) <= set(months)
                assert (
                    rules.violations(
                        path.months,
                        BASE_DATE,
                        length,
                        engine_version=ENGINE_VERSION,
                        replay_start=EXT348_REPLAY_START,
                        blocks=path.blocks,
                    )
                    == []
                )


# ---- look-ahead and range ----


def test_months_of_the_base_year_and_after_are_never_chosen():
    months = [*sample_shape(), *month_range(date(2026, 1, 1), date(2026, 6, 1))]
    for seed in range(300):
        path = generate(months, date(2026, 1, 2), 60, seed)
        assert all(m < date(2026, 1, 1) for m in path.months), seed
    early = generate(ext348(), date(2025, 6, 15), 60, 3, replay_start=EXT348_REPLAY_START)
    assert all(m < date(2025, 1, 1) for m in early.months)


def test_months_before_the_replay_start_are_never_chosen():
    for seed in range(300):
        assert all(
            m >= date(2007, 1, 1) for m in generate(sample_shape(), BASE_DATE, 60, seed).months
        )
        path = generate(ext348(), BASE_DATE, 120, seed, replay_start=EXT348_REPLAY_START)
        assert date(1996, 12, 1) not in path.months


# ---- overlap ----


def test_overlap_threshold_is_met_when_recent_covers_half_the_history():
    months, replay_start = history("ext348")
    recent = half_recent()
    recent_set = set(recent)
    distribution: dict[int, int] = {}
    for seed in range(500):
        path = generate(
            months,
            BASE_DATE,
            60,
            seed,
            recent_months=recent,
            max_overlap=Fraction(1, 2),
            replay_start=replay_start,
        )
        count = sum(m in recent_set for m in path.months)
        assert count == path.overlap_months
        assert path.overlap_met
        assert count <= 30
        distribution[count] = distribution.get(count, 0) + 1
    print(f"overlap_months distribution (ext348, half recent, 1/2): {sorted(distribution.items())}")


def test_block_level_filter_lowers_overlap_below_the_unfiltered_level():
    months, replay_start = history("ext348")
    recent = half_recent()
    recent_set = set(recent)
    filtered = unfiltered = 0
    for seed in range(200):
        a = generate(months, BASE_DATE, 60, seed, recent_months=recent, replay_start=replay_start)
        b = generate(months, BASE_DATE, 60, seed, replay_start=replay_start)
        filtered += a.overlap_months
        unfiltered += sum(m in recent_set for m in b.months)
    assert filtered * 2 < unfiltered


def test_infeasible_threshold_still_returns_the_best_attempt():
    months, replay_start = history("ext348")
    everything = month_range(date(1997, 1, 1), date(2023, 12, 1))
    for seed in range(20):
        path = generate(
            months,
            BASE_DATE,
            60,
            seed,
            recent_months=everything,
            max_overlap=Fraction(0),
            replay_start=replay_start,
        )
        assert not path.overlap_met
        assert path.overlap_months >= 36
        assert len(path.months) == 60
        fewer = generate(
            months,
            BASE_DATE,
            60,
            seed,
            recent_months=everything,
            max_overlap=Fraction(0),
            replay_start=replay_start,
            params=BootstrapParams(attempts=path.attempt + 1),
        )
        assert fewer == path
        if path.attempt > 0:
            earlier = generate(
                months,
                BASE_DATE,
                60,
                seed,
                recent_months=everything,
                max_overlap=Fraction(0),
                replay_start=replay_start,
                params=BootstrapParams(attempts=path.attempt),
            )
            assert earlier.overlap_months > path.overlap_months


def test_whole_history_recent_and_zero_threshold_returns_attempt_zero():
    months, replay_start = history("ext348")
    recent = months
    path = generate(
        months,
        BASE_DATE,
        60,
        4,
        recent_months=recent,
        max_overlap=Fraction(0),
        replay_start=replay_start,
    )
    assert path.overlap_months == 60
    assert not path.overlap_met
    assert path.attempt == 0


def test_no_recent_months_equals_attempt_zero_and_outside_recent_is_the_same():
    months, replay_start = history("ext348")
    outside = month_range(date(1980, 1, 1), date(1990, 12, 1))
    for seed in range(50):
        plain = generate(months, BASE_DATE, 60, seed, replay_start=replay_start)
        assert plain.attempt == 0
        assert plain.overlap_months == 0
        assert plain.overlap_met
        other = generate(
            months,
            BASE_DATE,
            60,
            seed,
            recent_months=outside,
            max_overlap=Fraction(1, 4),
            replay_start=replay_start,
        )
        assert other == plain


def test_max_overlap_one_stops_at_the_first_attempt_even_with_recent_months():
    months, replay_start = history("ext348")
    for seed in range(30):
        path = generate(
            months,
            BASE_DATE,
            60,
            seed,
            recent_months=half_recent(),
            max_overlap=Fraction(1),
            replay_start=replay_start,
        )
        assert path.attempt == 0
        assert path.overlap_met


# ---- input errors ----


def test_bad_arguments_raise_typed_errors_without_months_in_the_message():
    months = sample_shape()
    for length in (0, 6, 36, 121, -12):
        with pytest.raises(InvalidArgument):
            generate(months, BASE_DATE, length, 1)
    for length in (True, 12.0, "12"):
        with pytest.raises(TypeError):
            generate(months, BASE_DATE, length, 1)  # type: ignore[arg-type]
    generate(months, BASE_DATE, 12, -(2**63))
    generate(months, BASE_DATE, 12, 2**63 - 1)
    for seed in (-(2**63) - 1, 2**63):
        with pytest.raises(InvalidArgument):
            generate(months, BASE_DATE, 12, seed)
    for seed in (True, 1.0, "1"):
        with pytest.raises(TypeError):
            generate(months, BASE_DATE, 12, seed)  # type: ignore[arg-type]
    for bad in (Fraction(-1), Fraction(2), Fraction(3, 2)):
        with pytest.raises(InvalidArgument):
            generate(months, BASE_DATE, 12, 1, max_overlap=bad)
    with pytest.raises(TypeError):
        generate(months, BASE_DATE, 12, 1, max_overlap=0.5)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        generate(months, datetime(2026, 1, 2), 12, 1)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        generate([*months, datetime(2012, 1, 1)], BASE_DATE, 12, 1)  # type: ignore[list-item]
    with pytest.raises(TypeError):
        generate([*months, "2012-01-01"], BASE_DATE, 12, 1)  # type: ignore[list-item]
    with pytest.raises(InvalidArgument) as day:
        generate([*months, date(2013, 1, 15)], BASE_DATE, 12, 1)
    with pytest.raises(InvalidArgument) as twice:
        generate([*months, date(2007, 1, 1)], BASE_DATE, 12, 1)
    with pytest.raises(InvalidArgument) as recent:
        generate(months, BASE_DATE, 12, 1, recent_months=[date(2013, 1, 15)])
    with pytest.raises(PathTooLong) as short:
        generate(tiny(), BASE_DATE, 24, 1)
    assert "2013" not in str(day.value)
    assert "2007" not in str(twice.value)
    assert "2013" not in str(recent.value)
    assert "2010" not in str(short.value)


def test_params_are_validated():
    for kwargs in (
        {"mean_block_length": 0},
        {"max_block_length": 0},
        {"min_candidates": 0},
        {"attempts": 0},
        {"mean_block_length": 2**31},
    ):
        with pytest.raises(InvalidArgument):
            BootstrapParams(**kwargs)
    with pytest.raises(TypeError):
        BootstrapParams(attempts=True)


# ---- pins ----


def test_version_and_default_parameters_are_pinned():
    assert ENGINE_VERSION == "m2-sb6-v1"
    assert len(ENGINE_VERSION) <= 20
    assert stationary.ALLOWED_LENGTHS == (12, 24, 60, 120)
    assert (
        DEFAULT_PARAMS.mean_block_length,
        DEFAULT_PARAMS.max_block_length,
        DEFAULT_PARAMS.min_candidates,
        DEFAULT_PARAMS.attempts,
    ) == (6, 24, 3, 32)

import json
import os
import re
import subprocess
import sys
from collections import Counter
from datetime import date
from fractions import Fraction

import pytest
from conftest import ENGINE_DIR
from gen_stationary_vectors import (
    CONDITIONAL_FILE,
    CONDITIONAL_HEADER,
    LETTERS,
    conditional_block,
    render_conditional,
)
from synthetic_labels import chain_labels, label_fixture
from synthetic_months import BASE_DATE, month_range, run

from antlab_engine.bootstrap import stationary
from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.bootstrap.stationary import (
    CONDITIONAL_VERSION,
    DEFAULT_PARAMS,
    ENGINE_VERSION,
    Tier,
    generate,
)
from antlab_engine.bootstrap.transitions import POOLS, transition_counts
from antlab_engine.engine_errors import InvalidArgument, RegimeLabelsMissing

SEEDS = [0, 14, -1, 1234567]


def conditional(fixture: str, length: int, seed: int, **kwargs):
    months, replay_start, labels = label_fixture(fixture)
    return generate(
        months, BASE_DATE, length, seed, replay_start=replay_start, labels=labels, **kwargs
    )


def signature(path) -> tuple:
    return (
        path.months,
        tuple(
            (b.start_turn, b.drawn_length, b.length, b.tier, b.target, b.matched)
            for b in path.blocks
        ),
        path.overlap_months,
        path.attempt,
    )


# ---- TA3 determinism ----


def test_ta3_same_inputs_give_the_same_path():
    for fixture in ("persist", "patchy"):
        for seed in SEEDS:
            first = conditional(fixture, 60, seed)
            assert signature(conditional(fixture, 60, seed)) == signature(first)
            assert first.engine_version == CONDITIONAL_VERSION


def test_ta3_a_fresh_interpreter_gives_the_same_months():
    code = (
        "import json, sys\n"
        f"sys.path.insert(0, {str(ENGINE_DIR / 'tests')!r})\n"
        "from synthetic_labels import label_fixture\n"
        "from synthetic_months import BASE_DATE\n"
        "from antlab_engine.bootstrap.stationary import generate\n"
        "out = []\n"
        "for name in ('persist', 'patchy'):\n"
        "    months, start, labels = label_fixture(name)\n"
        "    for seed in range(4):\n"
        "        p = generate(months, BASE_DATE, 60, seed, replay_start=start, labels=labels)\n"
        "        out.append([[m.isoformat() for m in p.months],\n"
        "                    [[b.start_turn, b.length, b.tier, b.target, b.matched]\n"
        "                     for b in p.blocks]])\n"
        "json.dump(out, sys.stdout)\n"
    )
    outputs = []
    for hash_seed in ("1", "2"):
        env = {**os.environ, "PYTHONHASHSEED": hash_seed}
        result = subprocess.run(  # noqa: S603
            [sys.executable, "-I", "-c", code],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        assert result.returncode == 0, result.stderr
        outputs.append(result.stdout)
    assert outputs[0] == outputs[1]
    assert len(json.loads(outputs[0])) == 8
    in_process = []
    for name in ("persist", "patchy"):
        for seed in range(4):
            p = conditional(name, 60, seed)
            in_process.append(
                [
                    [m.isoformat() for m in p.months],
                    [[b.start_turn, b.length, b.tier, b.target, b.matched] for b in p.blocks],
                ]
            )
    assert json.loads(outputs[0]) == json.loads(json.dumps(in_process))


# ---- TA4 regression ----


def test_ta4_without_labels_the_unconditional_path_is_untouched():
    months, replay_start, labels = label_fixture("persist")
    for seed in SEEDS:
        plain = generate(months, BASE_DATE, 60, seed, replay_start=replay_start)
        assert plain.engine_version == ENGINE_VERSION
        assert {b.target for b in plain.blocks} == {None}
        assert not any(b.matched for b in plain.blocks)
        explicit = generate(months, BASE_DATE, 60, seed, replay_start=replay_start, labels=None)
        assert signature(explicit) == signature(plain)
        with_labels = generate(
            months, BASE_DATE, 60, seed, replay_start=replay_start, labels=labels
        )
        assert with_labels.engine_version == CONDITIONAL_VERSION
    with pytest.raises(RegimeLabelsMissing):
        generate(months, BASE_DATE, 60, 0, replay_start=replay_start, labels={})


def test_ta4_unconditioned_blocks_follow_the_unconditional_draws():
    months, replay_start, _ = label_fixture("persist")
    window = {m: "P1" for m in month_range(date(1997, 1, 1), date(1998, 12, 1))}
    same = 0
    for seed in range(60):
        plain = generate(months, BASE_DATE, 60, seed, replay_start=replay_start)
        ends = {m for b in plain.blocks for m in [plain.months[b.start_turn + b.length - 1]]}
        if ends & set(window):
            continue
        same += 1
        got = generate(months, BASE_DATE, 60, seed, replay_start=replay_start, labels=window)
        assert got.months == plain.months
        assert [b.tier for b in got.blocks] == [b.tier for b in plain.blocks]
        assert {b.target for b in got.blocks} == {None}
    assert same >= 20


# ---- TA5 vectors ----


def test_ta5_the_conditional_vector_file_has_the_expected_shape():
    lines = CONDITIONAL_FILE.read_text(encoding="utf-8").splitlines()
    assert lines[0] == CONDITIONAL_HEADER
    rows = [
        dict(zip(CONDITIONAL_HEADER.split(","), line.split(","), strict=True)) for line in lines[1:]
    ]
    assert len(rows) == 48 + 18 + 9
    assert {r["labels"] for r in rows} == {"persist", "patchy", "sample", "tight24"}
    assert {int(r["length"]) for r in rows} == {12, 24, 60, 120}
    assert len({r["seed"] for r in rows if r["labels"] == "persist"}) == 6
    blocks = [b.split(":") for r in rows for b in r["blocks"].split(" ")]
    assert {b[3] for b in blocks} >= {"-", *POOLS}
    assert {b[4] for b in blocks} == {"0", "1"}
    assert {b[2] for b in blocks} == set(LETTERS.values())
    joins = [b for b in blocks if b[2] == "J"]
    assert joins and all(b[3] != "-" and b[4] == "0" for b in joins)
    for tier in ("A", "N", "F"):
        assert {b[4] for b in blocks if b[2] == tier} == {"0", "1"}


def test_ta5_every_conditional_vector_row_matches_character_for_character():
    lines = CONDITIONAL_FILE.read_text(encoding="utf-8").splitlines()[1:]
    for line in lines:
        row = dict(zip(CONDITIONAL_HEADER.split(","), line.split(","), strict=True))
        path = conditional(row["labels"], int(row["length"]), int(row["seed"]))
        assert " ".join(f"{m:%Y-%m}" for m in path.months) == row["months"], row["case"]
        blocks = " ".join(conditional_block(b) for b in path.blocks)
        assert blocks == row["blocks"], row["case"]
        assert str(path.overlap_months) == row["overlap_months"]
        assert str(path.attempt) == row["attempt"]


def test_ta5_the_generator_script_reproduces_the_conditional_file_byte_for_byte():
    assert render_conditional() == CONDITIONAL_FILE.read_text(encoding="utf-8")


# ---- structure ----


def test_conditional_paths_obey_the_unconditional_path_rules():
    for fixture in ("persist", "patchy"):
        for seed in range(40):
            path = conditional(fixture, 60, seed)
            assert len(path.months) == 60
            assert sum(b.length for b in path.blocks) == 60
            assert len(set(path.months)) == 60
            assert path.blocks[0].target is None
            assert not path.blocks[0].matched
            for block in path.blocks:
                assert block.matched <= (block.target is not None)
                assert block.tier is not Tier.JOIN or not block.matched


# ---- TA6 the conditioning works ----


CHAIN_MONTHS = month_range(date(1926, 1, 1), date(2025, 12, 1))
CHAIN_START = date(1926, 1, 1)


def transition_stats(labels, seeds, **kwargs):
    stay = moved = conditioned = matched = 0
    for seed in seeds:
        path = generate(
            CHAIN_MONTHS, BASE_DATE, 120, seed, replay_start=CHAIN_START, labels=labels, **kwargs
        )
        for block in path.blocks[1:]:
            before = path.months[block.start_turn - 1]
            first = path.months[block.start_turn]
            if block.target is not None:
                conditioned += 1
                matched += block.matched
            if labels[first] == labels[before]:
                stay += 1
            else:
                moved += 1
    return stay, moved, conditioned, matched


def test_ta6_boundary_frequencies_follow_the_count_matrix():
    labels = chain_labels(CHAIN_MONTHS)
    counts = transition_counts(CHAIN_MONTHS, labels, BASE_DATE, CHAIN_START)
    assert len(CHAIN_MONTHS) == 1200
    prob_stay = [counts.probability(i, i) for i in range(4)]
    assert all(p is not None and abs(p - Fraction(9, 10)) < Fraction(1, 20) for p in prob_stay)
    seen: Counter[tuple[str, str]] = Counter()
    totals: Counter[str] = Counter()
    matched = conditioned = 0
    for seed in range(120):
        path = generate(CHAIN_MONTHS, BASE_DATE, 120, seed, replay_start=CHAIN_START, labels=labels)
        for block in path.blocks[1:]:
            if block.target is None:
                continue
            conditioned += 1
            matched += block.matched
            if not block.matched:
                continue
            previous = labels[path.months[block.start_turn - 1]]
            first = labels[path.months[block.start_turn]]
            assert first == block.target
            seen[(previous, first)] += 1
            totals[previous] += 1
    assert conditioned > 1000
    assert matched / conditioned > 0.95
    for i, a in enumerate(POOLS):
        assert totals[a] > 200
        for j, b in enumerate(POOLS):
            expected = counts.counts[i][j] / counts.row_total(i)
            assert abs(seen[(a, b)] / totals[a] - expected) < 0.03, (a, b)


def test_ta6_unconditional_paths_stay_in_a_regime_much_less_often():
    labels = chain_labels(CHAIN_MONTHS)
    seeds = range(120)
    c_stay, c_moved, _, _ = transition_stats(labels, seeds)
    u_stay = u_moved = 0
    for seed in seeds:
        path = generate(CHAIN_MONTHS, BASE_DATE, 120, seed, replay_start=CHAIN_START)
        for block in path.blocks[1:]:
            before = labels[path.months[block.start_turn - 1]]
            first = labels[path.months[block.start_turn]]
            if before == first:
                u_stay += 1
            else:
                u_moved += 1
    cond_share = c_stay / (c_stay + c_moved)
    plain_share = u_stay / (u_stay + u_moved)
    assert cond_share > 0.85
    assert plain_share < 0.45
    assert cond_share - plain_share > 0.4


# ---- TA7 level order ----


def pool_of(months: list[date]):
    return stationary._pool(sorted(m.year * 12 + m.month - 1 for m in months), set())


def choose(months: list[date], labels: dict[date, str], target: str, ell: int, turn_month: int):
    pool = pool_of(months)
    index = {m.year * 12 + m.month - 1: POOLS.index(p) for m, p in labels.items()}
    cond = stationary._Cond(index, transition_counts(months, labels, BASE_DATE, date(1900, 1, 1)))
    counts: list[int] = []
    used = bytearray(len(pool.run) - 1)
    found = stationary._choose_matched(
        pool, cond, POOLS.index(target), ell, turn_month, used, -1, DEFAULT_PARAMS, counts
    )
    return found, counts


def january_history(matching_januaries: int):
    months = month_range(date(1997, 1, 1), date(2010, 12, 1))
    januaries = [m for m in months if m.month == 1]
    labels = {m: "P3" for m in months if m.month != 1}
    for i, m in enumerate(januaries):
        labels[m] = "P3" if i < matching_januaries else "P1"
    return months, labels, januaries


def test_ta7_calendar_comes_before_the_label():
    months, labels, januaries = january_history(2)
    found, counts = choose(months, labels, "P3", 1, 0)
    assert found is not None
    tier, candidates, matched = found
    assert (tier, matched) == (Tier.ALIGNED, False)
    assert len(candidates) == len(januaries)
    assert counts == [2, len(januaries)]


def test_ta7_the_matched_level_is_taken_at_the_minimum():
    months, labels, januaries = january_history(3)
    found, counts = choose(months, labels, "P3", 1, 0)
    assert found is not None
    tier, candidates, matched = found
    assert (tier, matched, len(candidates)) == (Tier.ALIGNED, True, 3)
    assert counts == [3]


def test_ta7_near_matched_is_tried_before_free_matched():
    months = [*run(2010, 1, 1), *run(2011, 1, 1), *run(2010, 2, 1), *run(2011, 2, 1)]
    months += run(2010, 6, 4)
    labels = {m: "P1" for m in months}
    for m in (date(2010, 2, 1), date(2011, 2, 1), date(2010, 1, 1)):
        labels[m] = "P3"
    found, counts = choose(sorted(months), labels, "P3", 1, 0)
    assert found is not None
    assert (found[0], found[2], len(found[1])) == (Tier.NEAR, True, 3)
    assert counts == [1, 2, 3]


def test_ta7_free_matched_beats_free_when_calendar_tiers_are_short():
    months = [*run(2010, 1, 1), *run(2011, 1, 1), *run(2010, 6, 4)]
    labels = {m: "P1" for m in months}
    for m in run(2010, 6, 3):
        labels[m] = "P3"
    found, counts = choose(sorted(months), labels, "P3", 1, 0)
    assert found is not None
    assert (found[0], found[2], len(found[1])) == (Tier.FREE, True, 3)
    assert counts == [0, 2, 0, 2, 3]
    labels[date(2010, 8, 1)] = "P1"
    found, counts = choose(sorted(months), labels, "P3", 1, 0)
    assert found is not None
    assert (found[0], found[2], len(found[1])) == (Tier.FREE, False, 6)


def test_ta7_the_first_non_empty_level_is_the_fallback():
    months = [*run(2010, 1, 1), *run(2011, 1, 1)]
    labels = {date(2010, 1, 1): "P3", date(2011, 1, 1): "P1"}
    found, counts = choose(months, labels, "P3", 1, 0)
    assert found is not None
    assert (found[0], found[2], len(found[1])) == (Tier.ALIGNED, True, 1)
    found, _ = choose(months, labels, "P4", 1, 0)
    assert found is not None
    assert (found[0], found[2], len(found[1])) == (Tier.ALIGNED, False, 2)


# ---- TA8 look-ahead ----


def test_ta8_labels_outside_the_replay_range_leave_paths_bit_identical():
    months, replay_start, labels = label_fixture("persist")
    outside = {m for m in labels if m < replay_start or m >= date(2026, 1, 1)}
    assert outside
    flipped = {m: ("P4" if m in outside else p) for m, p in labels.items()}
    removed = {m: p for m, p in labels.items() if m not in outside}
    for seed in SEEDS:
        base = conditional("persist", 60, seed)
        for variant in (flipped, removed):
            got = generate(months, BASE_DATE, 60, seed, replay_start=replay_start, labels=variant)
            assert signature(got) == signature(base)


def test_ta8_a_replayable_label_changes_the_counts_and_some_path():
    months, replay_start, labels = label_fixture("persist")
    changed = dict(labels)
    for m in month_range(date(2000, 1, 1), date(2004, 12, 1)):
        changed[m] = POOLS[(POOLS.index(labels[m]) + 2) % 4]
    base = transition_counts(months, labels, BASE_DATE, replay_start)
    assert transition_counts(months, changed, BASE_DATE, replay_start) != base
    differs = 0
    for seed in range(20):
        a = conditional("persist", 60, seed)
        b = generate(months, BASE_DATE, 60, seed, replay_start=replay_start, labels=changed)
        differs += signature(a) != signature(b)
    assert differs > 0


# ---- TA9 minimum labels ----


def test_ta9_the_minimum_is_enforced_by_the_generator():
    months, replay_start, full = label_fixture("persist")
    window = [m for m in months if replay_start <= m < date(2026, 1, 1)]
    for count, ok in ((23, False), (24, True)):
        labels = {m: full[m] for m in window[:count]}
        if ok:
            path = generate(months, BASE_DATE, 12, 0, replay_start=replay_start, labels=labels)
            assert path.engine_version == CONDITIONAL_VERSION
        else:
            with pytest.raises(RegimeLabelsMissing) as caught:
                generate(months, BASE_DATE, 12, 0, replay_start=replay_start, labels=labels)
            assert not re.search(r"\d{4}-\d{2}|P[1-4]", str(caught.value))
    spaced = {m: full[m] for m in window[:48:2]}
    with pytest.raises(RegimeLabelsMissing):
        generate(months, BASE_DATE, 12, 0, replay_start=replay_start, labels=spaced)


# ---- TA10 draws ----


class CountingRng(SplitMix64):
    calls = 0

    def next_int(self, n: int) -> int:
        type(self).calls += 1
        return super().next_int(n)


def test_ta10_a_boundary_without_a_label_consumes_no_target_draw(monkeypatch):
    months, replay_start, full = label_fixture("persist")
    window = [m for m in months if replay_start <= m < date(2026, 1, 1)]
    sparse = {m: full[m] for m in window[:24]}
    one = stationary.BootstrapParams(attempts=1)
    monkeypatch.setattr(stationary, "SplitMix64", CountingRng)
    untargeted = 0
    for seed in range(60):
        CountingRng.calls = 0
        plain = generate(months, BASE_DATE, 12, seed, replay_start=replay_start, params=one)
        plain_calls = CountingRng.calls
        CountingRng.calls = 0
        got = generate(
            months, BASE_DATE, 12, seed, replay_start=replay_start, labels=sparse, params=one
        )
        if all(b.target is None for b in got.blocks):
            untargeted += 1
            assert got.months == plain.months
            assert CountingRng.calls == plain_calls
    assert untargeted >= 10


def test_ta10_blocks_after_an_unlabelled_month_have_no_target():
    months, replay_start, full = label_fixture("persist")
    window = [m for m in months if replay_start <= m < date(2026, 1, 1)]
    sparse = {m: full[m] for m in window[:24]}
    for seed in range(60):
        got = generate(months, BASE_DATE, 60, seed, replay_start=replay_start, labels=sparse)
        for block in got.blocks[1:]:
            before = got.months[block.start_turn - 1]
            assert (block.target is None) == (before not in sparse), seed


# ---- TA11 invalid input ----


def test_ta11_invalid_labels_are_refused_by_generate():
    months, replay_start, _ = label_fixture("persist")
    bad: list[tuple[type[Exception], dict]] = [
        (InvalidArgument, {date(1990, 1, 1): "P1"}),
        (InvalidArgument, {date(2000, 1, 2): "P1"}),
        (InvalidArgument, {date(2000, 1, 1): "P9"}),
        (TypeError, {date(2000, 1, 1): 3}),
    ]
    for error, labels in bad:
        with pytest.raises(error) as caught:
            generate(months, BASE_DATE, 12, 0, replay_start=replay_start, labels=labels)
        assert not re.search(r"\d{4}-\d{2}|P[1-9]", str(caught.value))
    with pytest.raises(TypeError):
        generate(months, BASE_DATE, 12, 0, replay_start=replay_start, labels=[1, 2])  # type: ignore[arg-type]


def test_repr_of_a_block_hides_the_target():
    path = conditional("persist", 60, 0)
    conditioned = [b for b in path.blocks if b.target is not None]
    assert conditioned
    assert not re.search(r"P[1-4]", repr(conditioned[0]))


class FixedRng:
    def __init__(self, value: int) -> None:
        self.value = value
        self.asked: list[int] = []

    def next_int(self, n: int) -> int:
        self.asked.append(n)
        return self.value


def test_the_target_follows_the_cumulative_counts():
    counts = transition_counts(
        month_range(date(2010, 1, 1), date(2010, 2, 1)),
        {date(2010, 1, 1): "P1", date(2010, 2, 1): "P1"},
        BASE_DATE,
        date(2007, 1, 1),
    )
    rows = ((2, 3, 0, 5), (0, 0, 0, 0), (0, 0, 0, 0), (0, 0, 0, 0))
    cond = stationary._Cond({1: 0, 2: 1}, stationary.TransitionCounts(rows, 10, 10))
    assert counts.pairs == 1
    expected = {0: 0, 1: 0, 2: 1, 4: 1, 5: 3, 9: 3}
    for r, target in expected.items():
        rng = FixedRng(r)
        assert stationary._draw_target(cond, 1, rng) == target, r
        assert rng.asked == [10]
    silent = FixedRng(0)
    assert stationary._draw_target(cond, 2, silent) is None
    assert stationary._draw_target(cond, 99, silent) is None
    assert silent.asked == []

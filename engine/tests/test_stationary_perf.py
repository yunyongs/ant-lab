"""Measurements for the M2-05 speed target and the overlap defaults; run with -m slow -s."""

import time
from collections import Counter
from datetime import date
from fractions import Fraction

import pytest
from synthetic_months import BASE_DATE, EXT348_REPLAY_START, ext348, history, month_range

from antlab_engine.bootstrap.stationary import BootstrapParams, generate

pytestmark = pytest.mark.slow


def timed(count: int, length: int, **kwargs) -> float:
    months, replay_start = history("ext348")
    start = time.perf_counter()
    for seed in range(count):
        generate(months, BASE_DATE, length, seed, replay_start=replay_start, **kwargs)
    return time.perf_counter() - start


def recent_of(seasons: int, length: int = 60) -> list:
    months, replay_start = history("ext348")
    result: set = set()
    for seed in range(1000, 1000 + seasons):
        result |= set(generate(months, BASE_DATE, length, seed, replay_start=replay_start).months)
    return sorted(result)


def test_time_per_path_and_projection_batch():
    for length in (60, 120):
        seconds = timed(1000, length)
        print(f"{length} turns, no recent: {seconds / 1000 * 1000:.3f} ms per path")
    batch = timed(2000, 120)
    print(f"2000 x 120 turns, no recent: {batch:.3f} s")
    assert batch < 2


def test_time_with_recent_months_and_a_threshold():
    recent = recent_of(3)
    batch = timed(200, 60, recent_months=recent, max_overlap=Fraction(1, 2))
    print(f"200 x 60 turns, 3 recent seasons ({len(recent)} months): {batch:.3f} s")
    worst = timed(100, 120, recent_months=recent_of(10), max_overlap=Fraction(1, 4))
    print(f"100 x 120 turns, 10 recent seasons, 1/4: {worst:.3f} s")


def test_overlap_distribution_for_the_default_thresholds():
    months, replay_start = history("ext348")
    for label, seasons, threshold in (("human", 3, Fraction(1, 2)), ("agent", 10, Fraction(1, 4))):
        recent = recent_of(seasons)
        counts: Counter[int] = Counter()
        met = attempts = 0
        for seed in range(300):
            path = generate(
                months,
                BASE_DATE,
                60,
                seed,
                recent_months=recent,
                max_overlap=threshold,
                replay_start=replay_start,
            )
            counts[path.overlap_months] += 1
            met += path.overlap_met
            attempts += path.attempt
        spread = sorted(counts.items())
        print(
            f"{label}: recent {len(recent)} months of 348, threshold {threshold}, met {met}/300, "
            f"mean attempt {attempts / 300:.2f}, overlap_months {spread}"
        )


def worst_case_ms(label: str, count: int, months, replay_start, recent, **kwargs) -> float:
    start = time.perf_counter()
    for seed in range(count):
        generate(
            months,
            BASE_DATE,
            120,
            seed,
            recent_months=recent,
            max_overlap=Fraction(0),
            replay_start=replay_start,
            **kwargs,
        )
    per_path = (time.perf_counter() - start) / count * 1000
    print(f"worst case, {label}: {per_path:.1f} ms per path")
    return per_path


def test_worst_inputs_stay_within_the_documented_budget():
    # Every attempt fails the impossible threshold 0, so all attempts run (default 32).
    full = ext348()
    alternate = full[::2]  # no two consecutive months: every block shrinks to 1 month
    exact = month_range(date(2010, 1, 1), date(2019, 12, 1))  # exactly 120 months for 120 turns
    wide = month_range(date(1900, 1, 1), date(1999, 12, 1))  # 1,200 months
    results = [
        worst_case_ms("348 months, all recent", 10, full, EXT348_REPLAY_START, full),
        worst_case_ms(
            "every other month, all recent", 10, alternate, EXT348_REPLAY_START, alternate
        ),
        worst_case_ms("exactly 120 months, all recent", 10, exact, date(2007, 1, 1), exact),
        worst_case_ms("1,200 months, all recent", 5, wide, date(1900, 1, 1), wide),
    ]
    assert max(results) < 1000  # documented budget: under one second per path at 32 attempts


def test_cost_grows_linearly_with_the_attempt_budget():
    full = ext348()
    alternate = full[::2]
    one = worst_case_ms(
        "1 attempt",
        10,
        alternate,
        EXT348_REPLAY_START,
        alternate,
        params=BootstrapParams(attempts=1),
    )
    many = worst_case_ms(
        "256 attempts",
        2,
        alternate,
        EXT348_REPLAY_START,
        alternate,
        params=BootstrapParams(attempts=256),
    )
    print(f"attempt budget 256 vs 1: x{many / one:.0f}")
    assert many < 256 * one * 2

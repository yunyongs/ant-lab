"""Measurement for the conditional path cost (M2-09 TA13); run with -m slow -s."""

import time
from datetime import date

import pytest
from synthetic_labels import chain_labels
from synthetic_months import BASE_DATE, month_range

from antlab_engine.bootstrap.stationary import generate

pytestmark = pytest.mark.slow


def test_worst_conditional_path_stays_under_the_budget():
    months = month_range(date(1926, 1, 1), date(2025, 12, 1))
    labels = chain_labels(months)
    start = time.perf_counter()
    count = 10
    for seed in range(count):
        generate(
            months,
            BASE_DATE,
            120,
            seed,
            replay_start=date(1926, 1, 1),
            labels=labels,
            recent_months=months,
            max_overlap=0,
        )
    per_path = (time.perf_counter() - start) / count
    print(f"1,200 months, 120 turns, 32 attempts, every month recent: {per_path:.3f} s per path")
    assert per_path < 0.3

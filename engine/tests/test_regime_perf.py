"""Timing of 29 years of weekly steps over the full catalog (TB19); run with `-m slow -s`."""

import time
from datetime import date

import pytest
from regime_synthetic import panel

from antlab_engine.regime import evaluate, weekly_schedule

BUDGET_SECONDS = 5.0


@pytest.mark.slow
def test_29_years_weekly_stays_inside_the_budget():
    items = panel(29, 2)
    dates = weekly_schedule(date(1996, 9, 7), date(2025, 8, 9))
    started = time.perf_counter()
    snapshots = evaluate(items, dates)
    seconds = time.perf_counter() - started
    print(f"\nregime evaluate: {len(items)} rows, {len(dates)} steps, {seconds:.2f} s")  # noqa: T201
    assert len(snapshots) == len(dates)
    assert snapshots[-1].p is not None
    assert seconds < BUDGET_SECONDS

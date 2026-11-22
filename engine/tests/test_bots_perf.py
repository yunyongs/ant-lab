"""Throughput of the bot runner. The light test runs in CI; the slow one is measured on request."""

import time

import pytest

from antlab_engine.bots import io
from antlab_engine.bots.runner import run_batch
from antlab_engine.bots.world import synthetic_world


def test_twenty_random_bots_on_a_twelve_turn_world_stay_cheap():
    world = synthetic_world(1, assets=30, length=12)
    started = time.perf_counter()
    records = run_batch(world, [("random", i) for i in range(20)], 1)
    assert len(records) == 20
    assert time.perf_counter() - started < 5.0


@pytest.mark.slow
def test_all_two_hundred_and_three_bots_on_a_sixty_turn_world_with_a_feed():
    world = synthetic_world(7, assets=30, length=60, feed=True)
    specs = [("random", i) for i in range(200)] + [
        ("static", 0),
        ("disciplined", 0),
        ("news", 0),
    ]
    started = time.perf_counter()
    one = run_batch(world, specs, 1)
    single = time.perf_counter() - started
    started = time.perf_counter()
    four = run_batch(world, specs, 1, workers=4)
    parallel = time.perf_counter() - started
    print(f"workers=1 {single:.2f}s workers=4 {parallel:.2f}s")  # noqa: T201
    assert [io.record_line(r) for r in one] == [io.record_line(r) for r in four]
    assert single < 15.0

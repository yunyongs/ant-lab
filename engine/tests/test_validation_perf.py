"""CPU time of one 60-turn world with the full roster and rank counts on (slow, run with -s)."""

import os

import pytest

from antlab_engine.validation.experiment import WorldJob, profile_cells, roster, run_world

CEILING_SECONDS = 40


@pytest.mark.slow
def test_one_sixty_turn_world_with_the_full_roster_stays_under_the_ceiling():
    cell = profile_cells("offline")[0]
    before = os.times()
    index, lines = run_world(WorldJob(cell, 0, 303))
    after = os.times()
    cpu = (after.user - before.user) + (after.system - before.system)
    print(f"60-turn world, {len(lines)} bots, counts on: {cpu:.1f} s CPU")  # noqa: T201
    assert index == 0 and len(lines) == len(roster(cell)) == 656
    assert cpu <= CEILING_SECONDS

"""CPU time of one production-size reference: 200 random bots on a 60-turn, 30-asset world."""

import time

import pytest
from leaderboard_support import SETTINGS_DOC, FakeWorldReader, identity_of, team_record, team_series

from antlab_engine.leaderboard import REFERENCE_SIZE
from antlab_engine.leaderboard.parts import checked_components, season_parts
from antlab_engine.leaderboard.reference import ReferenceService

CPU_CEILING_SECONDS = 10.0


@pytest.mark.slow
def test_a_sixty_turn_reference_of_two_hundred_bots_fits_the_cpu_budget():
    reader = FakeWorldReader(seed=7, assets=30, length=60)
    service = ReferenceService(reader)
    identity = identity_of({**SETTINGS_DOC, "pathLength": 60})
    cpu, wall = time.process_time(), time.perf_counter()
    reference = service.reference(identity)
    cpu, wall = time.process_time() - cpu, time.perf_counter() - wall
    team = team_series(team_record(service, reader, identity))
    started = time.process_time()
    parts = season_parts(team, checked_components(team, 60), reference)
    ranking = time.process_time() - started
    print(  # noqa: T201
        f"reference {REFERENCE_SIZE} bots x 60 turns x 30 assets: cpu {cpu:.2f}s wall {wall:.2f}s;"
        f" parts {ranking * 1000:.1f} ms; per bot {cpu / REFERENCE_SIZE * 1000:.1f} ms"
    )
    assert reference.size == REFERENCE_SIZE
    assert parts.parts.ret is not None
    assert cpu < CPU_CEILING_SECONDS

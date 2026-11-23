"""The pre-reference checks and the benchmark parity on Java-played seasons (Java is the oracle).

`seasons-v1.json` holds 20 seasons Java played and scored. Built from what Java stores (turn 1..T
totals, initial cash, `season_score.sharpe` and `benchSharpe`), every season must pass the returns
and Sharpe checks, and the engine's own world benchmark must give Java's stored bench Sharpe. A
one-sided rounding change in Java or Python fails here instead of excluding every live season.
"""

from datetime import date
from decimal import Decimal

import pytest
from test_sim_parity import INDEXES, SEASONS, inputs_from_wire

from antlab_engine.bots.world import BotWorld
from antlab_engine.leaderboard.parts import TeamSeries, checked_components, season_parts
from antlab_engine.leaderboard.reference import (
    SeasonIdentity,
    build_reference,
    reference_key,
    world_seed,
)
from antlab_engine.sim.score import team_returns_of

BASE_DATE = date(2026, 1, 2)


def _java_series(season: dict, benchmark: tuple[Decimal, ...]) -> TeamSeries:
    java = season["java"]
    initial = Decimal(java["report"]["initialCash"])
    totals = tuple(Decimal(t["total"]) for t in java["turns"])
    return TeamSeries(
        initial,
        totals,
        tuple(team_returns_of(initial, totals)),
        benchmark,
        Decimal(java["score"]["sharpe"]),
        Decimal(java["score"]["benchSharpe"]),
        None if java["score"]["riskScore"] is None else Decimal(java["score"]["riskScore"]),
    )


@pytest.mark.parametrize("index", INDEXES)
def test_a_java_season_passes_the_checks_against_the_engine_world(index):
    season = SEASONS[index]
    inputs = inputs_from_wire(season["inputs"])
    turns = inputs.settings.path_length
    assert [t["turn"] for t in season["java"]["turns"]] == list(range(1, turns + 1))
    months = tuple(date(2000 + i // 12, i % 12 + 1, 1) for i in range(turns))
    identity = SeasonIdentity("ref69-v1", BASE_DATE, months, inputs.settings)
    key = reference_key(identity, size=1)
    reference = build_reference(BotWorld(world_seed(key), BASE_DATE, inputs), key, 1)
    team = _java_series(season, reference.benchmark_returns)
    components = checked_components(team, turns)  # returns from totals, both Sharpe values
    result = season_parts(team, components, reference)
    assert result.percentiles.mdd in (Decimal(0), Decimal(50), Decimal(100))

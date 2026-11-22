"""The static bot holds the random bot's start team and never trades."""

from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import synthetic_world


def test_static_equals_the_random_start_team_and_never_trades():
    world = synthetic_world(8, assets=16, length=24)
    for index in range(15):
        static = run_bot(world, make_bot("static", index, 5, world.seed))
        rand = run_bot(world, make_bot("random", index, 5, world.seed))
        assert [t for t, _ in static.units_by_turn] == [0]
        assert static.units_by_turn[0] == rand.units_by_turn[0]
        assert static.record["verdicts"]["rejected"] == {}


def test_static_teams_differ_by_index_and_have_no_formation_change():
    world = synthetic_world(9, assets=16, length=12)
    teams = {
        run_bot(world, make_bot("static", i, 5, world.seed))
        .decisions["turns"][0]["units"]
        .__repr__()
        for i in range(30)
    }
    assert len(teams) > 25
    run = run_bot(world, make_bot("static", 0, 5, world.seed))
    assert {u["kind"] for t in run.decisions["turns"] for u in t["units"]} == {"ORDER"}

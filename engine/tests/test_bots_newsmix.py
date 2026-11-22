"""The newsmix probe: a disciplined bot that acts as the news bot on a share of its turns."""

import pytest
from bots_support import view

from antlab_engine.bots.disciplined_bot import DisciplinedBot
from antlab_engine.bots.news_bot import NewsBot
from antlab_engine.bots.newsmix_bot import VERSION, NewsMixBot
from antlab_engine.bots.rng import KIND_NEWSMIX_COIN, BotRng, mix
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.decisions import parse_decisions, play
from antlab_engine.sim.report import plain

TURNS = 40


class _Marker:
    def __init__(self, name):
        self.name = name

    def decide(self, v):
        return (self.name, v.turn)


def _marked(percent, seed=7, coin_seed=11):
    bot = NewsMixBot(0, seed, coin_seed, percent)
    bot._disciplined = _Marker("disciplined")
    bot._news = _Marker("news")
    return bot


def _routes(bot):
    return [bot.decide(view(turn=t))[0] for t in range(TURNS)]


def _expected_coins(coin_seed, percent):
    rng = BotRng(coin_seed)
    return ["news" if rng.below(100) < percent else "disciplined" for _ in range(1, TURNS)]


def test_turn_zero_always_goes_to_the_disciplined_team_build_and_draws_no_coin():
    for percent in (0, 1, 50, 100):
        routes = _routes(_marked(percent, coin_seed=3))
        assert routes[0] == "disciplined"
        assert routes[1:] == _expected_coins(3, percent)


def test_the_coin_comes_from_its_own_stream_not_the_shared_bot_stream():
    shared = BotRng(7)
    for _ in range(3):
        shared.below(100)  # whatever the disciplined start team consumed
    routes = _routes(_marked(50, seed=7, coin_seed=11))[1:]
    assert routes == _expected_coins(11, 50)
    assert routes != ["news" if shared.below(100) < 50 else "disciplined" for _ in routes]


def test_a_larger_q_acts_as_news_on_every_turn_a_smaller_q_does():
    by_q = {q: _routes(_marked(q, coin_seed=5)) for q in (0, 25, 50, 75, 100)}
    for low, high in ((0, 25), (25, 50), (50, 75), (75, 100)):
        for a, b in zip(by_q[low], by_q[high], strict=True):
            assert not (a == "news" and b != "news")
    assert "news" not in by_q[0] and set(by_q[100][1:]) == {"news"}
    assert 0 < by_q[50].count("news") < TURNS - 1


def test_the_coin_stream_is_derived_from_the_disciplined_seed_of_the_index():
    world = synthetic_world(9, assets=14, length=12, feed=True)
    bot = make_bot("newsmix-50", 3, 5, world.seed)
    twin = make_bot("disciplined", 3, 5, world.seed)
    assert bot.seed == twin.seed
    assert bot.kind == "newsmix-50" and bot.version == VERSION and bot.params == {"newsPercent": 50}
    assert bot._coin.below(1 << 40) == BotRng(mix(twin.seed, KIND_NEWSMIX_COIN)).below(1 << 40)
    assert isinstance(bot._disciplined, DisciplinedBot) and isinstance(bot._news, NewsBot)


@pytest.mark.parametrize(
    "kind", ["newsmix-", "newsmix-101", "newsmix-05", "newsmix--1", "newsmix-x"]
)
def test_bad_percent_is_refused(kind):
    with pytest.raises(ValueError, match="unknown bot kind"):
        make_bot(kind, 0, 1, 1)
    with pytest.raises(ValueError):
        NewsMixBot(0, 1, 1, 101)


def test_q_zero_is_the_disciplined_bot_and_q_hundred_is_the_news_bot():
    for seed in (31, 32, 33):
        world = synthetic_world(seed, assets=16, length=24, feed=True)
        disciplined = run_bot(world, make_bot("disciplined", 0, 2, world.seed))
        news = run_bot(world, make_bot("news", 0, 2, world.seed))
        zero = run_bot(world, make_bot("newsmix-0", 0, 2, world.seed))
        full = run_bot(world, make_bot("newsmix-100", 0, 2, world.seed))
        assert zero.units_by_turn == disciplined.units_by_turn
        assert zero.record["decisionsSha256"] == disciplined.record["decisionsSha256"]
        assert full.units_by_turn == news.units_by_turn
        assert full.record["decisionsSha256"] == news.record["decisionsSha256"]
        assert disciplined.units_by_turn != news.units_by_turn


def test_a_mixed_season_is_legal_deterministic_and_replays_through_play():
    world = synthetic_world(31, assets=16, length=24, feed=True)
    run = run_bot(world, make_bot("newsmix-50", 0, 2, world.seed))
    assert run.record["verdicts"]["rejected"] == {}
    assert run.record["bot"]["kind"] == "newsmix-50"
    assert run.record == run_bot(world, make_bot("newsmix-50", 0, 2, world.seed)).record
    played = play(world.inputs, parse_decisions(run.decisions))
    assert [plain(t.total) for t in played.season.turns] == run.record["totals"][1:]
    others = {
        run_bot(world, make_bot(kind, 0, 2, world.seed)).record["decisionsSha256"]
        for kind in ("disciplined", "news")
    }
    assert run.record["decisionsSha256"] not in others


def test_golden_decisions_hash_needs_a_version_bump_to_change():
    # A change here needs `newsmix_bot.VERSION` + 1.
    world = synthetic_world(1, assets=12, length=60, feed=True)
    run = run_bot(world, make_bot("newsmix-50", 0, 1, world.seed))
    assert len(run.units_by_turn) > 10
    assert (
        run.record["decisionsSha256"]
        == "c2d30927ce5ec04562c91ad3bb3b92a4c56d681928770db6552e2dbe786da5f3"
    )

"""Rules of the news-following bot, on hand-built views and on whole seasons."""

from bots_support import KR, KR2, US, view

from antlab_engine.bots.news_bot import NewsBot
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.view import FeedItem, ViewAsset
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.decisions import OrderUnit, parse_decisions, play
from antlab_engine.sim.model import Position, Side, SourceKind
from antlab_engine.sim.report import plain

A, B, C, D, E = (ViewAsset(f"KR:N{i}", "KR", Position.MIDFIELDER) for i in "ABCDE")
POOL = (A, B, C, D, E)
TURN = 4


def _units(feed=(), gainers=(), losers=(), attention=(), holdings=None, cash=10_000_000, **kw):
    v = view(
        turn=TURN,
        pool=POOL,
        holdings=holdings or {},
        cash=cash,
        feed=tuple(feed),
        gainers=gainers,
        losers=losers,
        attention=attention,
        **kw,
    )
    return NewsBot(0, 1).decide(v)


def _row(seq, kind, asset, direction=None):
    return FeedItem(seq, kind, asset, direction)


def _sells(units):
    return {u.order.asset: u.order for u in units if u.order.side is Side.SELL}


def _buys(units):
    return [u.order for u in units if u.order.side is Side.BUY]


def test_it_sells_all_of_a_held_asset_a_fall_or_echo_down_row_names_with_the_row_as_source():
    units = _units(
        feed=[_row(2, "FALL", A.asset, "DOWN"), _row(5, "ECHO", B.asset, "DOWN")],
        holdings={A.asset: 120, B.asset: 7, C.asset: 50},
    )
    sells = _sells(units)
    assert set(sells) == {"KR:NA", "KR:NB"}
    assert sells["KR:NA"].quantity == 120 and sells["KR:NB"].quantity == 7
    assert sells["KR:NA"].source.kind is SourceKind.FEED
    assert (sells["KR:NA"].source.feed_turn, sells["KR:NA"].source.feed_seq) == (TURN, 2)
    assert sells["KR:NB"].source.feed_seq == 5


def test_a_loser_without_a_row_is_sold_with_the_ranking_source_and_up_rows_never_sell():
    units = _units(
        feed=[_row(1, "RISE", A.asset, "UP"), _row(3, "ECHO", B.asset, "UP")],
        losers=(A.asset, C.asset),
        holdings={A.asset: 10, B.asset: 10, C.asset: 10},
    )
    sells = _sells(units)
    assert set(sells) == {"KR:NA", "KR:NC"}  # a loser is sold even when a rise row names it
    assert all(o.source.kind is SourceKind.RANKING for o in sells.values())


def test_a_fall_row_wins_over_the_losers_board_for_the_source_and_the_first_row_counts():
    units = _units(
        feed=[_row(7, "FALL", A.asset, "DOWN"), _row(3, "ECHO", A.asset, "DOWN")],
        losers=(A.asset,),
        holdings={A.asset: 10},
    )
    assert _sells(units)["KR:NA"].source.feed_seq == 3


def test_an_asset_not_held_is_never_sold_and_other_row_kinds_do_not_count():
    units = _units(
        feed=[
            _row(1, "FALL", A.asset, "DOWN"),
            _row(2, "FADE", B.asset, "DOWN"),
            _row(3, "EVENT", None),
        ],
        holdings={B.asset: 10},
    )
    assert _sells(units) == {}


def test_buys_follow_rise_rows_by_seq_then_gainers_then_attention_at_most_three():
    units = _units(
        feed=[
            _row(9, "RISE", D.asset, "UP"),
            _row(2, "ECHO", E.asset, "UP"),
            _row(5, "FALL", B.asset, "DOWN"),
        ],
        gainers=(A.asset, D.asset, B.asset),
        attention=(C.asset,),
        cash=9_000_000,
    )
    buys = _buys(units)
    assert [o.asset for o in buys] == ["KR:NE", "KR:ND", "KR:NA"]
    assert [o.source.kind for o in buys] == [SourceKind.FEED, SourceKind.FEED, SourceKind.RANKING]
    assert buys[0].source.feed_seq == 2 and buys[1].source.feed_seq == 9
    assert all(o.position is Position.MIDFIELDER for o in buys)


def test_fewer_than_three_candidates_buy_fewer_and_attention_fills_the_rest():
    units = _units(feed=[_row(4, "RISE", A.asset, "UP")], attention=(B.asset, A.asset, C.asset))
    assert [o.asset for o in _buys(units)] == ["KR:NA", "KR:NB", "KR:NC"]
    assert [o.source.kind for o in _buys(units)] == [
        SourceKind.FEED,
        SourceKind.RANKING,
        SourceKind.RANKING,
    ]
    assert _buys(_units()) == []


def test_assets_sold_this_turn_are_not_bought_back_and_the_budget_is_cash_over_remaining_buys():
    units = _units(
        feed=[
            _row(1, "FALL", A.asset, "DOWN"),
            _row(2, "RISE", A.asset, "UP"),
            _row(3, "RISE", B.asset, "UP"),
        ],
        gainers=(C.asset,),
        holdings={A.asset: 100},
        cash=600_000,
    )
    assert set(_sells(units)) == {"KR:NA"}
    buys = _buys(units)
    assert [o.asset for o in buys] == ["KR:NB", "KR:NC"]
    # cash 600,000 + 1,000,000 of sale proceeds (less fee), shared: first buy gets half of it
    proceeds = 100 * 10_000 - 150
    first = (600_000 + proceeds) // 2
    assert buys[0].quantity == int(first / (10_000 * 1.00015))
    assert sum(o.quantity for o in buys) * 10_000 <= 600_000 + proceeds


def test_the_bot_never_spends_more_cash_than_it_has():
    units = _units(
        feed=[
            _row(1, "RISE", A.asset, "UP"),
            _row(2, "RISE", B.asset, "UP"),
            _row(3, "RISE", C.asset, "UP"),
        ],
        cash=25_000,
    )
    spent = sum(o.quantity * 10_000 for o in _buys(units))
    assert spent <= 25_000 and spent > 0
    assert _buys(_units(feed=[_row(1, "RISE", A.asset, "UP")], cash=9_999)) == []


def test_sells_are_sent_before_buys():
    units = _units(
        feed=[_row(1, "FALL", A.asset, "DOWN"), _row(2, "RISE", B.asset, "UP")],
        holdings={A.asset: 100},
        cash=0,
    )
    sides = [u.order.side for u in units]
    assert sides == [Side.SELL, Side.BUY]


def test_it_has_no_signal_without_a_feed_and_holds():
    v = view(turn=TURN, pool=(KR, KR2, US), holdings={KR.asset: 10})
    assert NewsBot(0, 1).decide(v) == ()


def test_the_start_team_is_the_disciplined_team_of_the_same_seed():
    world = synthetic_world(9, assets=14, length=12, feed=True)
    news = run_bot(world, make_bot("news", 0, 5, world.seed))
    disciplined = run_bot(world, make_bot("disciplined", 0, 5, world.seed))
    assert news.units_by_turn[0] == disciplined.units_by_turn[0]
    assert news.record["bot"]["seed"] == disciplined.record["bot"]["seed"]


def test_seasons_are_legal_deterministic_and_replay_through_play():
    world = synthetic_world(31, assets=16, length=24, feed=True)
    run = run_bot(world, make_bot("news", 0, 2, world.seed))
    assert run.record["verdicts"]["rejected"] == {}
    assert run.record == run_bot(world, make_bot("news", 0, 2, world.seed)).record
    played = play(world.inputs, parse_decisions(run.decisions))
    assert [plain(t.total) for t in played.season.turns] == run.record["totals"][1:]
    orders = [u for _, units in run.units_by_turn for u in units if isinstance(u, OrderUnit)]
    kinds = {u.order.source.kind for u in orders if u.order.side is Side.BUY}
    assert SourceKind.RANKING in kinds or SourceKind.FEED in kinds


def test_the_news_bot_is_scored_against_the_feed_it_followed():
    world = synthetic_world(31, assets=16, length=24, feed=True)
    record = run_bot(world, make_bot("news", 0, 2, world.seed)).record
    assert record["noiseShare"] is not None
    assert record["discipline"]["chase"] is not None


def test_golden_decisions_hash_needs_a_version_bump_to_change():
    # A change here needs `news_bot.VERSION` + 1.
    world = synthetic_world(1, assets=12, length=60, feed=True)
    run = run_bot(world, make_bot("news", 0, 1, world.seed))
    assert len(run.units_by_turn) > 10
    assert run.record["decisionsSha256"] == (
        "7c8b333e5605e5aafdb8a8f0c3bd62a513fb4b7b6adeaf17d7e6a4926ed7457c"
    )

"""Runner: determinism, legality, replay, workers, and the plug-in point of the feed."""

import json
import re
from dataclasses import replace
from decimal import Decimal

import pytest

from antlab_engine.bots import io
from antlab_engine.bots.random_bot import buy_unit, sell_unit
from antlab_engine.bots.runner import BotRejection, make_bot, run_batch, run_bot
from antlab_engine.bots.view import FeedItem, TurnFeed
from antlab_engine.bots.world import BotWorld, synthetic_world, world_from_doc
from antlab_engine.sim.behaviour import TurnSignals
from antlab_engine.sim.decisions import Decisions, OrderUnit, parse_decisions, play
from antlab_engine.sim.model import Order, Side, SimInputError
from antlab_engine.sim.report import report_of
from antlab_engine.sim.score import score_season


def _line(run) -> str:
    return io.record_line(run.record)


def test_same_world_bot_and_seed_give_identical_bytes():
    world = synthetic_world(1, assets=12, length=12)
    a = run_bot(world, make_bot("random", 3, 9, world.seed))
    b = run_bot(world, make_bot("random", 3, 9, world.seed))
    assert _line(a) == _line(b)


def test_other_index_or_master_seed_changes_the_decisions():
    world = synthetic_world(1, assets=12, length=24)
    shas = {
        run_bot(world, make_bot("random", i, 9, world.seed)).record["decisionsSha256"]
        for i in range(25)
    }
    assert len(shas) == 25
    other = run_bot(world, make_bot("random", 0, 10, world.seed)).record["decisionsSha256"]
    assert other not in shas


def test_every_unit_is_accepted_over_many_worlds():
    for seed in range(12):
        world = synthetic_world(seed, assets=10 + seed % 5, length=(12, 24, 60)[seed % 3])
        for kind in ("random", "static"):
            run = run_bot(world, make_bot(kind, seed, 1, world.seed))
            assert run.record["verdicts"]["rejected"] == {}
            assert run.record["verdicts"]["accepted"] == sum(len(u) for _, u in run.units_by_turn)
            assert run.record["status"] == "FINISHED"


class _Illegal:
    kind = "illegal"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def decide(self, view):
        if view.turn == 0:
            return (OrderUnit(Order("KR:NOPE", Side.BUY, 1)),)
        return ()


def test_a_refused_unit_raises_unless_the_caller_asks_to_count_it():
    world = synthetic_world(2, assets=8, length=12)
    with pytest.raises(BotRejection):
        run_bot(world, _Illegal())
    run = run_bot(world, _Illegal(), require_accepted=False)
    assert run.record["verdicts"]["rejected"] == {"ASSET_NOT_IN_POOL": 1}


def test_recorded_decisions_replay_to_the_same_ledger_report_and_score():
    world = synthetic_world(4, assets=14, length=24)
    for index in range(6):
        run = run_bot(world, make_bot("random", index, 2, world.seed))
        played = play(world.inputs, parse_decisions(run.decisions), strict=True)
        assert report_of(played.season).to_wire() == run.record["report"]
        scored = score_season(
            played.season, None, season_seed=world.seed, base_date=world.base_date
        )
        assert scored.score.to_wire() == run.record["score"]
        assert [r.balance_after for r in played.season.ledger][-1] == played.season.cash


def test_totals_and_returns_cover_every_turn():
    world = synthetic_world(6, assets=12, length=12)
    record = run_bot(world, make_bot("random", 0, 1, world.seed)).record
    assert len(record["totals"]) == 13
    assert len(record["teamReturns"]) == 12
    assert len(record["benchmarkReturns"]) == 12
    assert Decimal(record["totals"][0]) <= Decimal(100_000_000)


def test_records_hold_no_dates_or_month_ids():
    world = synthetic_world(6, assets=12, length=12)
    text = _line(run_bot(world, make_bot("random", 0, 1, world.seed)))
    assert not re.search(r"\d{4}-\d{2}-\d{2}", text)
    assert "baseDate" not in text
    assert json.loads(text)["schema"] == "bot-run/1"


def test_workers_one_and_four_write_the_same_bytes(tmp_path):
    world = synthetic_world(10, assets=12, length=12)
    specs = [("random", i) for i in range(12)] + [("static", 0), ("static", 1)]
    one = run_batch(world, specs, 5, workers=1, keep_decisions=True)
    four = run_batch(world, specs, 5, workers=4, keep_decisions=True)
    assert [io.record_line(r) for r in one] == [io.record_line(r) for r in four]
    runs, manifest = io.write_batch(tmp_path / "bots", one, {"masterSeed": 5})
    assert [json.loads(line) for line in runs.read_text().splitlines()] == one
    assert manifest.read_text().strip() == '{"masterSeed":5}'


def test_world_documents_round_trip_and_check_their_id():
    world = synthetic_world(12, assets=9, length=12)
    doc = json.loads(json.dumps(world.to_doc()))
    assert world_from_doc(doc).world_id == world.world_id
    doc["seed"] = "13"
    with pytest.raises(Exception, match="worldId"):
        world_from_doc(doc)
    with_feed = BotWorld(world.seed, world.base_date, world.inputs, {"assets": []})
    assert with_feed.world_id != world.world_id


class _Recorder:
    kind = "recorder"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self):
        self.views = []

    def decide(self, view):
        self.views.append(view)
        return ()


class _FakeFeed:
    def __init__(self):
        self.calls = []

    def at(self, turn, prices):
        self.calls.append((turn, len(prices)))
        self.last_prices = prices
        return TurnFeed(
            (FeedItem(1, "RISE", "KR:069500", "UP"),),
            TurnSignals(turn, frozenset({"KR:069500"}), frozenset()),
        )


def test_a_feed_source_gets_the_turn_and_the_prices_so_far_only():
    world = synthetic_world(13, assets=8, length=12)
    bot, feed = _Recorder(), _FakeFeed()
    run = run_bot(world, bot, feed_source=feed)
    assert feed.calls == [(t, t + 1) for t in range(1, 12)]
    assert bot.views[0].feed == () and bot.views[0].signals is None
    assert bot.views[5].feed[0].asset == "KR:069500"
    assert bot.views[5].signals.turn == 5
    assert run.record["noiseShare"] is None  # no orders, nothing to judge


def test_no_feed_source_means_no_noise_item():
    world = synthetic_world(13, assets=8, length=12)
    record = run_bot(world, make_bot("random", 0, 1, world.seed)).record
    assert record["noiseShare"] is None


def test_decisions_document_is_a_valid_empty_season_when_nothing_is_traded():
    doc = io.decisions_doc([(0, ()), (3, ())])
    assert doc["turns"] == []
    assert parse_decisions(doc) == Decisions(())


# decisionsSha256 of the first bots on one world: a change here means the draw order, a
# parameter or `mix` changed, and the bot's VERSION must go up with it.
GOLDEN_SHA = {
    ("random", 0): "0faea55f1d98e2fc779a099eead085789edff896c4bd857eccee3464b70c87a3",
    ("random", 1): "b32daeaf98c674694c61b24ac5b9dd57110f8f7c5b968673c1f259980a23749b",
    ("random", 2): "b8c28fbdb638966b982524d16f180158fd7a1a62d8d08705e7fa175a8bf9a5dc",
    ("static", 0): "a1d9429d5556705f7b1567a7ac879e4ee16935d254f16e92fd003ed78b4fdf90",
}


def test_decisions_are_pinned_for_the_first_bots():
    world = synthetic_world(1, assets=12, length=12)
    got = {
        (kind, i): run_bot(world, make_bot(kind, i, 1, world.seed)).record["decisionsSha256"]
        for kind, i in GOLDEN_SHA
    }
    assert got == GOLDEN_SHA


class _SwapBot:
    kind = "swap"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self, buys_first: bool):
        self.buys_first = buys_first

    def decide(self, view):
        first, second = view.pool[0], view.pool[2]
        if view.turn == 0:
            built = buy_unit(view, first, int(view.cash))
            return (built[0],)
        if view.turn != 1:
            return ()
        sell, proceeds = sell_unit(view, first.asset, view.holdings[first.asset])
        buy = buy_unit(view, second, int(view.cash + proceeds))
        return (buy[0], sell) if self.buys_first else (sell, buy[0])


def test_a_buy_that_only_fits_after_the_sell_needs_the_sell_first():
    world = synthetic_world(14, assets=8, length=12)
    run_bot(world, _SwapBot(buys_first=False))
    with pytest.raises(BotRejection):
        run_bot(world, _SwapBot(buys_first=True))


def test_views_and_feed_prices_cannot_be_written_to():
    world = synthetic_world(13, assets=8, length=12)
    bot, feed = _Recorder(), _FakeFeed()
    run_bot(world, bot, feed_source=feed)
    asset = world.inputs.pool[0].asset
    for target in (bot.views[3].prices, bot.views[3].prev_prices, bot.views[3].rates):
        with pytest.raises(TypeError):
            target[asset] = 1
    with pytest.raises(TypeError):
        feed.last_prices[0][asset] = 1


def test_a_single_asset_limit_is_refused_up_front():
    world = synthetic_world(13, assets=8, length=12)
    settings = replace(world.inputs.settings, single_asset_limit=Decimal("0.5"))
    limited = replace(world, inputs=replace(world.inputs, settings=settings))
    with pytest.raises(SimInputError, match="single-asset"):
        run_bot(limited, make_bot("random", 0, 1, world.seed))


class _Scripted:
    kind = "scripted"
    version = 1
    index = 0
    seed = 0
    params: dict[str, object] = {}

    def __init__(self, buys_first: bool):
        self.buys_first = buys_first

    def decide(self, view):
        pool = {a.asset: a for a in view.pool}
        x, y = pool["KR:069500"], pool["US:SPY"]
        if view.turn == 0:
            built = buy_unit(view, x, int(view.cash))
            return (built[0],)
        if view.turn != 1 or not view.holdings.get(x.asset):
            return ()
        sell, proceeds = sell_unit(view, x.asset, view.holdings[x.asset])
        buy = buy_unit(view, y, int(view.cash + proceeds))
        assert buy is not None
        return (buy[0], sell) if self.buys_first else (sell, buy[0])


def test_sells_are_applied_before_buys_in_the_order_a_bot_sends_them():
    world = synthetic_world(2, assets=6, length=12)
    run = run_bot(world, _Scripted(buys_first=False))
    assert run.record["verdicts"]["rejected"] == {}
    sides = [u.order.side.value for u in run.units_by_turn[1][1]]
    assert sides == ["SELL", "BUY"]
    with pytest.raises(BotRejection, match="INSUFFICIENT_CASH"):
        run_bot(world, _Scripted(buys_first=True))


def _panel_world():
    from antlab_engine.bots.world_panel import panel_world

    return panel_world(1, 0, length=12, assets=12, generator="simple")


def test_a_counts_source_fills_counts_request_and_scores():
    from antlab_engine.validation.counts_cache import CachedEngineCounts

    world, panel = _panel_world()
    record = run_bot(
        world,
        make_bot("random", 0, 1, world.seed),
        counts_source=CachedEngineCounts(panel),
        path_count=100,
    ).record
    counts = record["counts"]
    assert set(counts) == {"sharpeDiff", "endMultiple"}
    for part in counts.values():
        assert set(part) == {"below", "equal", "n"}
        assert part["n"] == 100 and part["below"] + part["equal"] <= 100
    assert record["percentileRequest"]["n"] == 100
    assert record["score"]["resultScore"] is not None
    assert record["score"]["score"] is not None
    assert record["exclusion"] is None


def test_without_a_counts_source_the_counts_and_request_stay_null():
    world, _ = _panel_world()
    record = run_bot(world, make_bot("random", 0, 1, world.seed)).record
    assert record["counts"] is None
    assert record["percentileRequest"] is None
    assert record["score"]["resultScore"] is None and record["score"]["score"] is None


def test_counts_do_not_change_the_decisions_or_the_pinned_hashes():
    from antlab_engine.validation.counts_cache import CachedEngineCounts

    world, panel = _panel_world()
    bot = make_bot("random", 2, 1, world.seed)
    plain = run_bot(world, bot).record
    counted = run_bot(
        world, make_bot("random", 2, 1, world.seed), counts_source=CachedEngineCounts(panel)
    ).record
    assert counted["decisionsSha256"] == plain["decisionsSha256"]
    assert counted["totals"] == plain["totals"]


def test_a_history_that_cannot_carry_the_horizon_keeps_counts_null_and_the_request():
    from antlab_engine.sim.counts import FixedCounts

    world, _ = _panel_world()
    record = run_bot(
        world, make_bot("random", 0, 1, world.seed), counts_source=FixedCounts(None)
    ).record
    assert record["counts"] is None
    assert record["percentileRequest"] is not None
    assert record["exclusion"] == "NO_SCORE"


def test_batch_with_counts_writes_the_same_bytes_for_one_and_two_workers():
    from antlab_engine.validation.counts_cache import CachedEngineCounts

    world, panel = _panel_world()
    specs = [("random", i) for i in range(4)] + [("static", 0), ("news", 0)]
    one = run_batch(world, specs, 3, counts_source=CachedEngineCounts(panel), path_count=100)
    two = run_batch(
        world, specs, 3, workers=2, counts_source=CachedEngineCounts(panel), path_count=100
    )
    assert [io.record_line(r) for r in one] == [io.record_line(r) for r in two]
    assert all(r["counts"] is not None for r in one)

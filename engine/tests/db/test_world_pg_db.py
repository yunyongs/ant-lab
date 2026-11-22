"""Database worlds on the parity history and events, read as the engine role."""

import json
from contextlib import contextmanager
from datetime import date
from decimal import Decimal
from pathlib import Path

import psycopg
import pytest
from db_support import engine_settings

from antlab_engine.bootstrap.simple import generate_months
from antlab_engine.bots import world_pg
from antlab_engine.bots.world import BotWorld
from antlab_engine.bots.world_feed import WorldFeedSource
from antlab_engine.bots.world_pg import _read_rows, read_world
from antlab_engine.engine_errors import InvalidArgument, UnsafeDatabaseRole
from antlab_engine.history.reader import read_snapshot
from antlab_engine.sim.feed.rules import RULE_VERSION, Failure, FeedGenerationError
from antlab_engine.sim.model import Position
from antlab_engine.sim.season import Season

pytestmark = pytest.mark.db

BASE = date(2026, 1, 2)
START_2007 = date(2007, 1, 1)
START_2012 = date(2012, 1, 1)
ORACLE = json.loads(
    (
        Path(__file__).resolve().parents[3] / "src/test/resources/sim/parity/events-v1.json"
    ).read_text(encoding="utf-8")
)
WALKS = {w["name"]: w for w in ORACLE["seasons"]}


def months_of(name: str) -> list[date]:
    return [date.fromisoformat(m) for m in WALKS[name]["months"]]


def world(db, name="mixed", start=START_2007, seed=7) -> BotWorld:
    return read_world(engine_settings(db), BASE, seed, months=months_of(name), replay_start=start)


def events_of(w: BotWorld) -> dict[int, list[dict]]:
    out: dict[int, list[dict]] = {}
    for e in w.feed["events"]:
        out.setdefault(e["turn"], []).append(
            {k: e[k] for k in ("key", "category", "hidden", "reaction", "strength")}
        )
    return out


def test_the_oracle_pins_the_rule_version():
    assert ORACLE["header"]["rule"] == RULE_VERSION
    assert ORACLE["header"]["baseDate"] == BASE.isoformat()


def test_a_world_equals_a_hand_built_expectation_on_two_assets(world_db):
    w = world(world_db)
    by_name = {a.asset: a for a in w.inputs.pool}
    assert len(by_name) == 14 and "KR:999999" not in by_name
    spy, kr = by_name["US:SPY"], by_name["KR:069500"]
    assert (spy.currency, spy.base_price, spy.base_fx_rate, spy.system_position) == (
        "USD",
        Decimal("480.0000"),
        Decimal("1350.0000"),
        Position.DEFENDER,
    )
    assert (kr.currency, kr.base_price, kr.base_fx_rate, kr.system_position) == (
        "KRW",
        Decimal("35000.5000"),
        Decimal("1.0000"),
        Position.MIDFIELDER,
    )
    assert [a.asset for a in w.inputs.pool] == sorted(by_name, key=lambda k: tuple(k.split(":", 1)))
    names = {a["asset"]: a for a in w.feed["assets"]}
    assert names["US:SPY"] == {
        "asset": "US:SPY",
        "name": "가짜 미국 지수 ETF",
        "groupCode": "FAKE_INDEX",
        "groupName": "가짜 지수",
    }
    assert names["KR:069500"]["name"] == "가짜 국내 지수 ETF"
    assert names["KR:069500"]["groupCode"] == "FAKE_INDEX"
    with world_db.admin() as admin:
        for turn, month in enumerate(months_of("mixed"), start=1):
            expected = dict(
                admin.execute(
                    "SELECT a.market || ':' || a.ticker, r.local_return"
                    " FROM asset_month_return r JOIN asset a ON a.id = r.asset_id"
                    " JOIN month_block m ON m.id = r.month_block_id"
                    " WHERE m.month_start = %s"
                    " AND a.market || ':' || a.ticker IN ('US:SPY', 'KR:069500')",
                    (month,),
                ).fetchall()
            )
            step = w.inputs.path[turn - 1]
            assert step.turn == turn
            assert {k: step.local_returns[k] for k in ("US:SPY", "KR:069500")} == expected
            (fx,) = admin.execute(
                "SELECT f.fx_change FROM fx_month f JOIN month_block m ON m.id = f.month_block_id"
                " WHERE m.month_start = %s AND f.currency = 'USD'",
                (month,),
            ).fetchone()
            assert step.fx_change == {"USD": fx}


def test_attention_is_filtered_to_the_pool(world_db):
    w = world(world_db)
    with world_db.admin() as admin:
        outside = admin.execute(
            "SELECT count(*) FROM asset_month_attention m JOIN asset a ON a.id = m.asset_id"
            " WHERE a.ticker = '999999'"
        ).fetchone()[0]
        total = admin.execute("SELECT count(*) FROM asset_month_attention").fetchone()[0]
    assert outside == 228 and total > outside
    pool = {a.asset for a in w.inputs.pool}
    turns = w.feed["attention"]
    assert [t["turn"] for t in turns] == list(range(13)) and turns[0]["values"] == {}
    assert all(set(t["values"]) <= pool for t in turns)
    assert all(len(t["values"]) == 14 for t in turns[1:])
    WorldFeedSource(w.feed, w.seed, pool=pool)  # the feed port refuses outside-pool attention


def test_the_events_of_a_month_come_in_byte_order_with_the_hidden_flag(world_db):
    w = world(world_db)
    first = events_of(w)[1]
    assert [e["key"] for e in first] == ["Fakez9-2008-09", "fakea1-2008-09"]
    assert [e["hidden"] for e in first] == [False, False]
    assert first[1]["category"] == "FINANCIAL" and first[1]["reaction"] == "-0.12000000"
    hidden = {e["key"]: e for es in events_of(w).values() for e in es if e["hidden"]}
    assert set(hidden) == {"fakeb1-2009-04", "fakee1-2018-05", "faked1-2020-03"}
    assert all(e["category"] is None for e in hidden.values())


def test_the_replay_start_moves_the_hidden_flag(world_db):
    early = events_of(world(world_db, "late", START_2007))[1]
    late = events_of(world(world_db, "late", START_2012))[1]
    assert early[0]["key"] == late[0]["key"] == "fakea3-2016-06"
    assert (early[0]["hidden"], early[0]["category"]) == (False, "POLITICS")
    assert (late[0]["hidden"], late[0]["category"]) == (True, None)


@pytest.mark.parametrize(
    ("name", "start"),
    [(w["name"], run["replayStart"]) for w in ORACLE["seasons"] for run in w["runs"]],
)
def test_events_hidden_flags_and_refusals_equal_the_java_oracle(world_db, name, start):
    run = next(r for r in WALKS[name]["runs"] if r["replayStart"] == start)
    refused = [t["turn"] for t in run["turns"] if "refusal" in t]
    if refused:
        with pytest.raises(FeedGenerationError) as caught:
            world(world_db, name, date.fromisoformat(start))
        assert {t["refusal"] for t in run["turns"] if "refusal" in t} == {
            caught.value.failure.value
        }
        return
    w = world(world_db, name, date.fromisoformat(start))
    expected = {t["turn"]: t["events"] for t in run["turns"] if t["events"]}
    assert events_of(w) == expected
    assert w.feed["reviewed"] == [t["turn"] for t in run["turns"]]


def test_an_unreviewed_month_refuses_and_names_no_month(world_db):
    with pytest.raises(FeedGenerationError) as caught:
        world(world_db, "unreviewed")
    assert caught.value.failure is Failure.FEED_EVENTS_UNREVIEWED
    assert "2009" not in str(caught.value)


def test_a_pool_asset_without_a_return_in_a_path_month_refuses(world_db):
    month = months_of("mixed")[2]
    select = (
        "SELECT r.asset_id, r.month_block_id, r.local_return, r.return_kind"
        " FROM asset_month_return r"
        " WHERE r.month_block_id = (SELECT id FROM month_block WHERE month_start = %s)"
        " AND r.asset_id = (SELECT id FROM asset WHERE market = 'KR' AND ticker = '069500')"
    )
    with world_db.admin() as admin:
        rows = admin.execute(select, (month,)).fetchall()
        assert len(rows) == 1
        admin.execute(
            "DELETE FROM asset_month_return WHERE asset_id = %s AND month_block_id = %s",
            rows[0][:2],
        )
        try:
            with pytest.raises(FeedGenerationError) as caught:
                world(world_db)
            assert caught.value.failure is Failure.FEED_INPUT
            assert "069500" not in str(caught.value)
        finally:
            admin.execute(
                "INSERT INTO asset_month_return"
                " (asset_id, month_block_id, local_return, return_kind)"
                " VALUES (%s, %s, %s, %s)",
                rows[0],
            )
    assert world(world_db).inputs.path[2].local_returns["KR:069500"] == rows[0][2]


def test_a_missing_usd_fx_change_refuses(world_db):
    month = months_of("mixed")[0]
    with world_db.admin() as admin:
        (block,) = admin.execute(
            "SELECT id FROM month_block WHERE month_start = %s", (month,)
        ).fetchone()
        row = admin.execute(
            "SELECT month_block_id, currency, fx_change FROM fx_month WHERE month_block_id = %s",
            (block,),
        ).fetchone()
        admin.execute("DELETE FROM fx_month WHERE month_block_id = %s", (block,))
        try:
            with pytest.raises(FeedGenerationError) as caught:
                world(world_db)
            assert caught.value.failure is Failure.FEED_INPUT
        finally:
            admin.execute(
                "INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES (%s, %s, %s)",
                row,
            )


def test_months_outside_the_replay_range_or_not_stored_refuse(world_db):
    settings = engine_settings(world_db)
    outside = [date(2006, 12, 1)] + months_of("mixed")[1:]
    with pytest.raises(InvalidArgument):
        read_world(settings, BASE, 1, months=outside, replay_start=START_2007)
    after = [date(2026, 1, 1)] + months_of("mixed")[1:]
    with pytest.raises(InvalidArgument):
        read_world(settings, BASE, 1, months=after, replay_start=START_2007)
    unknown = [date(2030, 1, 1)] + months_of("mixed")[1:]
    with pytest.raises(InvalidArgument):
        read_world(settings, BASE, 1, months=unknown, replay_start=START_2007)


def test_a_drawn_path_is_the_engine_path_of_that_seed(world_db):
    with world_db.admin() as admin:
        stored = dict(
            admin.execute(
                "SELECT month_start, id FROM month_block WHERE month_start < %s",
                (date(2026, 1, 1),),
            ).fetchall()
        )
    seen = set()
    for start in (START_2007, START_2012):
        for path_seed in range(1, 8):
            expected = generate_months(sorted(stored), BASE, 12, path_seed, start)
            with read_snapshot(engine_settings(world_db)) as conn:
                rows = _read_rows(conn, BASE, start, length=12, path_seed=path_seed)
            assert rows.path_blocks == tuple(stored[m] for m in expected)
            seen.add(rows.path_blocks)
    assert len(seen) > 8  # different seeds and replay starts really draw different paths
    path_seed = next(
        s
        for s in range(1, 40)
        if date(2009, 6, 1) not in generate_months(sorted(stored), BASE, 12, s, START_2007)
    )
    w = read_world(
        engine_settings(world_db), BASE, 3, length=12, path_seed=path_seed, replay_start=START_2007
    )
    expected = generate_months(sorted(stored), BASE, 12, path_seed, START_2007)
    with world_db.admin() as admin:
        for step, month in zip(w.inputs.path, expected, strict=True):
            (value,) = admin.execute(
                "SELECT r.local_return FROM asset_month_return r JOIN asset a ON a.id = r.asset_id"
                " JOIN month_block m ON m.id = r.month_block_id"
                " WHERE m.month_start = %s AND a.ticker = '069500'",
                (month,),
            ).fetchone()
            assert step.local_returns["KR:069500"] == value


def test_months_together_with_a_length_refuse(world_db):
    with pytest.raises(InvalidArgument):
        read_world(
            engine_settings(world_db),
            BASE,
            1,
            months=months_of("mixed"),
            length=12,
            path_seed=1,
            replay_start=START_2007,
        )


def test_the_world_feeds_the_feed_source_for_every_turn(world_db):
    w = world(world_db, "mixed")
    season = Season(w.inputs)
    for _ in range(12):
        season.advance()
    prices = season.turn_prices
    source = WorldFeedSource(w.feed, w.seed, pool=[a.asset for a in w.inputs.pool])
    for turn in range(1, 12):
        feed = source.at(turn, prices[: turn + 1])
        assert feed.signals is not None and feed.signals.turn == turn
    assert w.world_id == world(world_db, "mixed").world_id


def test_the_whole_world_is_read_in_one_snapshot(world_db, monkeypatch):
    opened = []
    real = read_snapshot

    @contextmanager
    def counting(settings):
        with real(settings) as conn:
            opened.append(conn)
            yield conn

    monkeypatch.setattr(world_pg, "read_snapshot", counting)
    world(world_db)
    assert len(opened) == 1


def test_a_connection_that_is_not_a_repeatable_read_snapshot_is_refused(world_db):
    settings = engine_settings(world_db)
    with psycopg.connect(
        host=settings.host,
        port=settings.port,
        dbname=settings.name,
        user=settings.user,
        passfile="/dev/null",
    ) as conn:
        conn.read_only = True
        with pytest.raises(UnsafeDatabaseRole):
            _read_rows(conn, BASE, START_2007, months=months_of("mixed"))


def test_the_cli_writes_a_world_file_the_offline_batch_can_run(
    world_db, monkeypatch, tmp_path, capsys
):
    from antlab_engine.bots.__main__ import main as batch
    from antlab_engine.bots.world import world_from_doc

    monkeypatch.setenv("ANTLAB_ENGINE_DB_HOST", world_db.admin_params["host"])
    monkeypatch.setenv("ANTLAB_ENGINE_DB_PORT", world_db.admin_params["port"])
    monkeypatch.setenv("ANTLAB_ENGINE_DB_NAME", world_db.name)
    monkeypatch.setenv("ANTLAB_ENGINE_DB_USER", "antlab_engine")
    monkeypatch.delenv("ANTLAB_HISTORY_REPLAY_START", raising=False)
    codes = []
    for path_seed in range(1, 40):
        out = tmp_path / f"w{path_seed}.json"
        code = world_pg.main(
            [
                "--base-date",
                "2026-01-02",
                "--seed",
                "7",
                "--path-seed",
                str(path_seed),
                "--out",
                str(out),
            ]
        )
        codes.append(code)
        if code == 0:
            break
    assert codes[-1] == 0, "every drawn path hit the unreviewed month"
    assert all(c == 1 for c in codes[:-1])
    if len(codes) > 1:
        assert "FEED_EVENTS_UNREVIEWED" in capsys.readouterr().err
    world = world_from_doc(json.loads(out.read_text(encoding="utf-8")))
    assert world.feed is not None and len(world.inputs.path) == 12
    rc = batch(
        ["--world", str(out), "--random", "2", "--static", "1", "--out", str(tmp_path / "batch")]
    )
    assert rc == 0
    assert (tmp_path / "batch" / "runs.jsonl").read_text(encoding="utf-8").count("\n") == 5


def test_the_cli_passes_the_replay_start_setting_to_the_reader(world_db, monkeypatch, tmp_path):
    captured = {}
    real = world_pg.read_world

    def spy(*args, **kwargs):
        captured.update(kwargs)
        return real(*args, **kwargs)

    monkeypatch.setattr(world_pg, "read_world", spy)
    monkeypatch.setenv("ANTLAB_ENGINE_DB_HOST", world_db.admin_params["host"])
    monkeypatch.setenv("ANTLAB_ENGINE_DB_PORT", world_db.admin_params["port"])
    monkeypatch.setenv("ANTLAB_ENGINE_DB_NAME", world_db.name)
    monkeypatch.setenv("ANTLAB_ENGINE_DB_USER", "antlab_engine")
    monkeypatch.setenv("ANTLAB_HISTORY_REPLAY_START", "2012-01-01")
    world_pg.main(
        [
            "--base-date",
            "2026-01-02",
            "--seed",
            "7",
            "--path-seed",
            "1",
            "--out",
            str(tmp_path / "w.json"),
        ]
    )
    assert captured["replay_start"] == START_2012

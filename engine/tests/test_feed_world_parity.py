"""The Java-played parity seasons run through the Python feed port.

`worlds-v1.json` is written next to `seasons-v1.json` by `SimulatorParityFixtureTest` (Java is the
oracle): for each of the 20 seasons the pool names and group codes, the attention figures of every
block, the events, and everything Java stored for the feed (the non-alert `turn_feed` rows and the
ranking board of every turn). The test rebuilds each turn's facts from those inputs and the stored
turn prices, generates the feed and the board offline and compares them exactly, then compares the
turn signals with the ones in `seasons-v1.json`. Ids are not carried by the fixture: the test gives
assets, groups and events ids that differ in order from Java's, so an order that leaks from an id
shows up as a difference. Regenerate with `ANTLAB_PARITY_WRITE=1`.
"""

import json
from collections import Counter
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from antlab_engine.bots.world_feed import WorldFeedSource
from antlab_engine.sim.feed import facts as facts_mod
from antlab_engine.sim.feed.catalog import FILES, Catalog
from antlab_engine.sim.feed.facts import EventFacts, FeedInput, PoolAsset, PreviousRow
from antlab_engine.sim.feed.feed import FeedGenerator
from antlab_engine.sim.feed.ranking import RankingInput, board
from antlab_engine.sim.feed.rules import RULE_VERSION
from antlab_engine.sim.feed.signals import FeedRow, signals_of

PARITY = Path(__file__).resolve().parents[2] / "src/test/resources/sim/parity"
WORLDS_DOC = json.loads((PARITY / "worlds-v1.json").read_text(encoding="utf-8"))
SEASONS_DOC = json.loads((PARITY / "seasons-v1.json").read_text(encoding="utf-8"))
WORLDS = {w["index"]: w for w in WORLDS_DOC["seasons"]}
SEASONS = {s["index"]: s for s in SEASONS_DOC["seasons"]}
INDEXES = list(range(1, 21))
CATALOG = Catalog.load()
GENERATOR = FeedGenerator(CATALOG)

_OUTSIDE_ASSET_ID = 9000


def _dec(value: str) -> Decimal:
    return Decimal(value)


class Ids:
    """Ids for one world, deliberately not in Java's order."""

    def __init__(self, world: dict[str, Any]) -> None:
        keys = [a["asset"] for a in world["pool"]]
        self.asset = {k: len(keys) - i + 100 for i, k in enumerate(keys)}
        outside = sorted(
            {k for entry in world["attention"] for k in entry["values"]} - set(self.asset)
        )
        for i, k in enumerate(outside):
            self.asset[k] = _OUTSIDE_ASSET_ID + i
        codes = sorted({a["groupCode"] for a in world["pool"]}, reverse=True)
        self.group = {c: i + 1 for i, c in enumerate(codes)}
        keys_of_events = sorted({e["key"] for e in world["events"]}, reverse=True)
        self.event = {k: i + 1 for i, k in enumerate(keys_of_events)}
        self.asset_key = {v: k for k, v in self.asset.items()}
        self.group_code = {v: k for k, v in self.group.items()}
        self.event_key = {v: k for k, v in self.event.items()}


def _pool(world: dict[str, Any], ids: Ids) -> list[PoolAsset]:
    out = []
    for a in world["pool"]:
        market, ticker = a["asset"].split(":", 1)
        out.append(
            PoolAsset(
                ids.asset[a["asset"]],
                market,
                ticker,
                a["name"],
                ids.group[a["groupCode"]],
                a["groupCode"],
                a["groupName"],
            )
        )
    return out


def _stored_rows_by_turn(world: dict[str, Any]) -> dict[int, list[dict[str, Any]]]:
    by_turn: dict[int, list[dict[str, Any]]] = {}
    for r in world["rows"]:
        by_turn.setdefault(r["turn"], []).append(r)
    return by_turn


def _row_json(r: Any, ids: Ids, turn: int) -> dict[str, Any]:
    return {
        "turn": turn,
        "seq": r.seq,
        "kind": r.kind,
        "asset": None if r.asset_id is None else ids.asset_key[r.asset_id],
        "group": None if r.asset_group_id is None else ids.group_code[r.asset_group_id],
        "event": None if r.month_event_id is None else ids.event_key[r.month_event_id],
        "hidden": r.category_hidden,
        "direction": r.direction,
        "strength": r.strength,
        "repeat": r.repeat_count,
        "template": r.template_id,
        "vars": r.template_vars_json,
    }


def _board_json(b: Any, ids: Ids, turn: int) -> dict[str, Any]:
    def rate(value: Decimal) -> str:
        return format(value, "f")

    def asset(r: Any) -> dict[str, Any]:
        return {"rank": r.rank, "asset": ids.asset_key[r.asset_id]}

    return {
        "turn": turn,
        "gainers": [{**asset(r), "rate": rate(r.change_rate)} for r in b.gainers],
        "losers": [{**asset(r), "rate": rate(r.change_rate)} for r in b.losers],
        "attention": [{**asset(r), "grade": r.grade} for r in b.attention],
        "risingStreaks": [
            {**asset(r), "streak": r.streak, "rate": rate(r.change_rate)} for r in b.rising_streaks
        ],
        "fallingStreaks": [
            {**asset(r), "streak": r.streak, "rate": rate(r.change_rate)} for r in b.falling_streaks
        ],
        "groupGainers": [
            {"rank": r.rank, "group": ids.group_code[r.group_id], "rate": rate(r.change_rate)}
            for r in b.group_gainers
        ],
        "groupLosers": [
            {"rank": r.rank, "group": ids.group_code[r.group_id], "rate": rate(r.change_rate)}
            for r in b.group_losers
        ],
    }


def _first_difference(actual: Any, expected: Any, path: str) -> str | None:
    """The path of the first difference, showing only that pair of values."""
    if isinstance(actual, dict) and isinstance(expected, dict):
        for key in sorted(set(actual) | set(expected)):
            if key not in actual or key not in expected:
                return f"{path}.{key} (missing on one side)"
            inner = _first_difference(actual[key], expected[key], f"{path}.{key}")
            if inner:
                return inner
        return None
    if isinstance(actual, list) and isinstance(expected, list):
        if len(actual) != len(expected):
            return f"{path} (length {len(actual)} vs {len(expected)})"
        for i, (a, e) in enumerate(zip(actual, expected, strict=True)):
            inner = _first_difference(a, e, f"{path}[{i}]")
            if inner:
                return inner
        return None
    if actual == expected and type(actual) is type(expected):
        return None
    return f"{path}: python {actual!r}, java {expected!r}"


def _replay(index: int) -> dict[str, Any]:
    """Generate every turn of one season offline; returns what the Python side produced."""
    world = WORLDS[index]
    season = SEASONS[index]
    seed = int(season["seed"])
    prices = season["java"]["turnPrices"]
    ids = Ids(world)
    pool = _pool(world, ids)
    attention = {
        e["turn"]: {ids.asset[k]: _dec(v) for k, v in e["values"].items()}
        for e in world["attention"]
    }
    events_by_turn: dict[int, list[EventFacts]] = {}
    for e in world["events"]:
        events_by_turn.setdefault(e["turn"], []).append(
            EventFacts(
                e["turn"],
                ids.event[e["key"]],
                e["key"],
                e["category"],
                _dec(e["reaction"]),
                e["strength"],
                e["hidden"],
            )
        )
    stored = _stored_rows_by_turn(world)
    refs = [a["asset"] for a in world["pool"]]
    rows: list[dict[str, Any]] = []
    boards: list[dict[str, Any]] = []
    signals: list[dict[str, Any]] = []
    for turn in range(1, len(season["inputs"]["path"]) + 1):
        history = {ids.asset[ref]: [_dec(prices[t][ref]) for t in range(turn)] for ref in refs}
        current = {ids.asset[ref]: _dec(prices[turn][ref]) for ref in refs}
        previous = [
            PreviousRow(ids.asset[r["asset"]], r["kind"], r["direction"], r["repeat"])
            for r in stored.get(turn - 1, [])
            if r["asset"] is not None
        ]
        built = facts_mod.build_asset_facts(
            turn,
            pool,
            history,
            current,
            attention[turn],
            attention[turn - 1],
            facts_mod.previous_stories(previous, turn),
        )
        generated = GENERATOR.generate(FeedInput(seed, turn, built, events_by_turn.get(turn, [])))
        rows.extend(_row_json(r, ids, turn) for r in generated)
        ranking = RankingInput(turn, tuple(built))
        boards.append(_board_json(board(ranking), ids, turn))
        if turn < len(season["inputs"]["path"]):
            s = signals_of(
                turn,
                [FeedRow(turn, r.kind, r.asset_id, r.direction) for r in generated],
                ranking,
            )
            signals.append(
                {
                    "turn": turn,
                    "up": sorted(ids.asset_key[a] for a in s.up),
                    "down": sorted(ids.asset_key[a] for a in s.down),
                    "topAttention": sorted(ids.asset_key[a] for a in s.top_attention),
                }
            )
    return {"rows": rows, "boards": boards, "signals": signals}


_CACHE: dict[int, dict[str, Any]] = {}


def _result(index: int) -> dict[str, Any]:
    if index not in _CACHE:
        _CACHE[index] = _replay(index)
    return _CACHE[index]


def test_header_pins_the_rule_version_and_the_catalog():
    header = WORLDS_DOC["header"]
    assert header["rule"] == RULE_VERSION
    assert header["catalogSha256"] == CATALOG.sha256
    assert sorted(header["catalogSha256"]) == sorted(FILES)


def test_the_fixture_holds_every_season_of_the_simulator_fixture():
    assert sorted(WORLDS) == INDEXES
    assert sorted(SEASONS) == INDEXES
    assert WORLDS_DOC["header"]["masterSeed"] == SEASONS_DOC["header"]["masterSeed"]


@pytest.mark.parametrize("index", INDEXES)
def test_the_world_reviews_every_turn(index):
    world = WORLDS[index]
    assert world["reviewed"] == list(range(1, len(SEASONS[index]["inputs"]["path"]) + 1))


@pytest.mark.parametrize("index", INDEXES)
def test_stored_feed_rows_equal_java(index):
    got = _result(index)["rows"]
    where = _first_difference(got, WORLDS[index]["rows"], "rows")
    assert where is None, f"season {index}: {where}"


@pytest.mark.parametrize("index", INDEXES)
def test_ranking_boards_equal_java(index):
    got = _result(index)["boards"]
    where = _first_difference(got, WORLDS[index]["boards"], "boards")
    assert where is None, f"season {index}: {where}"


@pytest.mark.parametrize("index", INDEXES)
def test_turn_signals_equal_java(index):
    got = _result(index)["signals"]
    where = _first_difference(got, SEASONS[index]["signals"], "signals")
    assert where is None, f"season {index}: {where}"


def test_the_seasons_cover_the_feed():
    kinds: Counter[str] = Counter()
    echo_repeats: set[int] = set()
    rows = 0
    for index in INDEXES:
        for r in WORLDS[index]["rows"]:
            kinds[r["kind"]] += 1
            rows += 1
            if r["kind"] == "ECHO":
                echo_repeats.add(r["repeat"])
    assert {"RISE", "FALL", "ECHO", "SPREAD", "FADE"} <= set(kinds), kinds
    assert max(echo_repeats, default=0) >= 2, echo_repeats
    assert rows >= 300
    empty_boards = sum(
        1 for i in INDEXES for b in WORLDS[i]["boards"] if not b["gainers"] and not b["losers"]
    )
    assert empty_boards == 0


def test_the_attention_of_assets_outside_the_pool_is_in_the_fixture():
    for index in INDEXES:
        pool = {a["asset"] for a in WORLDS[index]["pool"]}
        outside = {k for e in WORLDS[index]["attention"] for k in e["values"]} - pool
        assert outside == {"KR:999999"}, index


@pytest.mark.parametrize("index", INDEXES)
def test_the_bot_feed_source_equals_java(index):
    """The source the bots use (self-chained stories, pool-filtered attention) on the Java world."""
    world = WORLDS[index]
    season = SEASONS[index]
    source = WorldFeedSource(
        {
            "assets": world["pool"],
            "attention": world["attention"],
            "events": world["events"],
            "reviewed": world["reviewed"],
        },
        int(season["seed"]),
    )
    prices = [{k: _dec(v) for k, v in step.items()} for step in season["java"]["turnPrices"]]
    stored = _stored_rows_by_turn(world)
    signals = {s["turn"]: s for s in season["signals"]}
    for turn in range(1, len(season["inputs"]["path"]) + 1):
        got = source.at(turn, prices[: turn + 1])
        items = [
            {"seq": i.seq, "kind": i.kind, "asset": i.asset, "direction": i.direction}
            for i in got.items
        ]
        want = [
            {"seq": r["seq"], "kind": r["kind"], "asset": r["asset"], "direction": r["direction"]}
            for r in stored.get(turn, [])
        ]
        where = _first_difference(items, want, f"turn {turn} items")
        assert where is None, f"season {index}: {where}"
        b = world["boards"][turn - 1]
        for name, mine, theirs in (
            ("gainers", got.gainers, b["gainers"]),
            ("losers", got.losers, b["losers"]),
            ("attention", got.attention, b["attention"]),
        ):
            where = _first_difference(
                list(mine), [r["asset"] for r in theirs], f"turn {turn} {name}"
            )
            assert where is None, f"season {index}: {where}"
        if turn in signals:
            s = signals[turn]
            assert got.signals is not None
            where = _first_difference(
                {
                    "up": sorted(got.signals.up),
                    "down": sorted(got.signals.down),
                    "topAttention": sorted(got.signals.top_attention),
                },
                {k: s[k] for k in ("up", "down", "topAttention")},
                f"turn {turn} signals",
            )
            assert where is None, f"season {index}: {where}"

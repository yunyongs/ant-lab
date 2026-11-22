"""The Java-written feed vectors replayed against the Python feed port.

`feed/vectors-v1.jsonl` is written by `FeedVectorsTest` (Java is the oracle) and read in place.
The header pins the rule version and the sha256 of the five `feed/*.csv` files; a different
catalog or rule version fails before any case runs.
"""

import dataclasses
import json
from decimal import Decimal
from pathlib import Path

import pytest

from antlab_engine.sim import money
from antlab_engine.sim.feed import facts as facts_mod
from antlab_engine.sim.feed.catalog import FILES, Catalog
from antlab_engine.sim.feed.facts import AssetFacts, EventFacts, FeedInput
from antlab_engine.sim.feed.feed import FeedGenerator
from antlab_engine.sim.feed.feedhash import byte_key, hash_text, pick
from antlab_engine.sim.feed.ranking import RankingInput, board
from antlab_engine.sim.feed.rules import RULE_VERSION, FeedGenerationError
from antlab_engine.sim.feed.signals import FeedRow, signals_of

VECTORS = Path(__file__).resolve().parents[2] / "src/test/resources/feed/vectors-v1.jsonl"


def _load() -> list[dict]:
    return [json.loads(line) for line in VECTORS.read_text(encoding="utf-8").splitlines()]


LINES = _load()
HEADER = LINES[0]
CASES = LINES[1:]
CATALOG = Catalog.load()
GENERATOR = FeedGenerator(CATALOG)


def dec(value: str | None) -> Decimal | None:
    return None if value is None else Decimal(value)


def asset_of(a: dict) -> AssetFacts:
    return AssetFacts(
        as_of_turn=a["asOfTurn"],
        asset_id=a["id"],
        market=a["market"],
        ticker=a["ticker"],
        name=a["name"],
        group_id=a["groupId"],
        group_code=a["groupCode"],
        group_name=a["groupName"],
        ret=dec(a["ret"]),
        prev_ret=dec(a["prevRet"]),
        streak=a["streak"],
        attention=dec(a["attention"]),
        attention_rank=a["attentionRank"],
        attention_n=a["attentionN"],
        prev_attention=dec(a["prevAttention"]),
        prev_attention_rank=a["prevAttentionRank"],
        prev_story_direction=a["prevStory"],
        prev_echo_repeat=a["prevEchoRepeat"],
        focus_name=a["focusName"],
    )


def event_of(e: dict) -> EventFacts:
    return EventFacts(
        as_of_turn=e["asOfTurn"],
        month_event_id=e["id"],
        event_key=e["key"],
        category=e["category"],
        reaction=dec(e["reaction"]),
        strength=e["strength"],
        hidden=e["hidden"],
    )


def row_json(r) -> dict:
    return {
        "seq": r.seq,
        "kind": r.kind,
        "asset": None if r.asset_id is None else str(r.asset_id),
        "group": None if r.asset_group_id is None else str(r.asset_group_id),
        "event": None if r.month_event_id is None else str(r.month_event_id),
        "hidden": r.category_hidden,
        "direction": r.direction,
        "strength": r.strength,
        "repeat": r.repeat_count,
        "template": r.template_id,
        "vars": r.template_vars_json,
    }


def board_json(b) -> dict:
    def assets(rows):
        return [
            {"rank": r.rank, "asset": r.asset_id, "rate": format(r.change_rate, "f")} for r in rows
        ]

    def streaks(rows):
        return [
            {
                "rank": r.rank,
                "asset": r.asset_id,
                "streak": r.streak,
                "rate": format(r.change_rate, "f"),
            }
            for r in rows
        ]

    def groups(rows):
        return [
            {"rank": r.rank, "group": r.group_id, "rate": format(r.change_rate, "f")} for r in rows
        ]

    return {
        "gainers": assets(b.gainers),
        "losers": assets(b.losers),
        "attention": [{"rank": r.rank, "asset": r.asset_id, "grade": r.grade} for r in b.attention],
        "risingStreaks": streaks(b.rising_streaks),
        "fallingStreaks": streaks(b.falling_streaks),
        "groupGainers": groups(b.group_gainers),
        "groupLosers": groups(b.group_losers),
    }


def assemble(raw: dict, turn: int) -> list[AssetFacts] | str:
    """The facts, or the refusal class as Java names it."""
    pool = [
        facts_mod.PoolAsset(
            p["id"],
            p["market"],
            p["ticker"],
            p["name"],
            p["groupId"],
            p["groupCode"],
            p["groupName"],
        )
        for p in raw["pool"]
    ]
    history = {int(k): [Decimal(x) for x in v] for k, v in raw["history"].items()}
    current = {int(k): Decimal(v) for k, v in raw["current"].items()}
    attention = {int(k): Decimal(v) for k, v in raw["attention"].items()}
    before = {int(k): Decimal(v) for k, v in raw["previousAttention"].items()}
    previous = [
        facts_mod.PreviousRow(r["assetId"], r["kind"], r["direction"], r["repeat"])
        for r in raw["previousFeed"]
    ]
    stories = facts_mod.previous_stories(previous, turn)
    try:
        return facts_mod.build_asset_facts(turn, pool, history, current, attention, before, stories)
    except FeedGenerationError as e:
        return e.failure.value
    except money.MoneyError:
        return "MONEY_INVALID"


def facts_text(a: AssetFacts) -> dict:
    """Every field as text, so a different decimal scale is a difference."""
    return {
        k: format(v, "f") if isinstance(v, Decimal) else v for k, v in dataclasses.asdict(a).items()
    }


def evaluate(case: dict) -> dict:
    """The Python counterpart of `FeedVectorsTest.evaluate`, in the same order of refusals."""
    inp = case["input"]
    turn = inp["turn"]
    out: dict = {}
    assets: list[AssetFacts] = []
    asset_error = None
    try:
        assets = [asset_of(a) for a in inp["assets"]]
    except FeedGenerationError as e:
        asset_error = e.failure.value
    events: list[EventFacts] = []
    event_error = None
    try:
        events = [event_of(e) for e in inp["events"]]
    except FeedGenerationError as e:
        event_error = e.failure.value
    if asset_error or event_error:
        out["feed"] = {"error": asset_error or event_error}
    else:
        try:
            rows = GENERATOR.generate(FeedInput(int(inp["seed"]), turn, assets, events))
            out["feed"] = {"rows": [row_json(r) for r in rows]}
            try:
                ranking = RankingInput(turn, tuple(assets))
                feed_rows = [FeedRow(turn, r.kind, r.asset_id, r.direction) for r in rows]
                s = signals_of(turn, feed_rows, ranking)
                out["signals"] = {
                    "up": sorted(s.up),
                    "down": sorted(s.down),
                    "topAttention": sorted(s.top_attention),
                }
            except FeedGenerationError as e:
                out["signals"] = {"error": e.failure.value}
        except FeedGenerationError as e:
            out["feed"] = {"error": e.failure.value}
    if asset_error:
        out["board"] = {"error": asset_error}
    else:
        try:
            out["board"] = board_json(board(RankingInput(turn, tuple(assets))))
        except FeedGenerationError as e:
            out["board"] = {"error": e.failure.value}
    return out


def test_header_pins_the_rule_version_and_the_catalog():
    assert HEADER["rule"] == RULE_VERSION
    assert HEADER["catalogSha256"] == CATALOG.sha256
    assert sorted(HEADER["catalogSha256"]) == sorted(FILES)


def test_hash_vectors():
    assert HEADER["hash"]
    for h in HEADER["hash"]:
        assert hash_text(h["text"]) == int(h["value"]), h["text"]
    for p in HEADER["pick"]:
        assert pick(int(p["seed"]), p["turn"], p["usage"], p["key"]) == int(p["value"])


def test_byte_order_is_utf8_not_utf16():
    assert sorted(HEADER["byteOrder"], key=byte_key) == HEADER["byteOrder"]


def test_there_are_at_least_300_cases():
    assert len(CASES) >= 300


@pytest.mark.parametrize("case", [c for c in CASES if "input" in c], ids=lambda c: f"case{c['id']}")
def test_case_equals_java(case):
    got = evaluate(case)
    assert got.get("feed") == case["feed"]
    assert got.get("signals") == case.get("signals")
    assert got["board"] == case["board"]


@pytest.mark.parametrize("case", [c for c in CASES if "raw" in c], ids=lambda c: f"case{c['id']}")
def test_assembly_from_raw_equals_java(case):
    built = assemble(case["raw"], case.get("turn") or case["input"]["turn"])
    expected = case["assembly"]
    if "error" in expected:
        assert built == expected["error"]
    else:
        assert isinstance(built, list)
        assert [facts_text(a) for a in built] == [
            facts_text(asset_of(a)) for a in expected["facts"]
        ]
        assert [facts_text(a) for a in built] == [
            facts_text(asset_of(a)) for a in case["input"]["assets"]
        ]


def test_the_raw_cases_include_every_refusal_and_outside_pool_attention():
    errors = {c["assembly"]["error"] for c in CASES if "error" in c.get("assembly", {})}
    assert errors == {"FEED_INPUT", "MONEY_INVALID"}
    outside = 0
    for c in CASES:
        if "raw" in c:
            pool_ids = {p["id"] for p in c["raw"]["pool"]}
            outside += any(int(k) not in pool_ids for k in c["raw"]["attention"])
    assert outside >= 20

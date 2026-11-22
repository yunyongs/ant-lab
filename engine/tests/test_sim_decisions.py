"""`sim-decisions/1` parsing, the `turn-decision/1` adapter and `play`."""

import copy
import json
import re
from decimal import Decimal

import pytest
from conftest import ENGINE_DIR
from sim_support import KR_A, KR_B, US_X, inputs

from antlab_engine.sim.decisions import (
    Decisions,
    FormationUnit,
    LineupUnit,
    OrderUnit,
    RejectedUnit,
    TurnPlan,
    from_turn_decision,
    parse_decisions,
    play,
)
from antlab_engine.sim.model import (
    Order,
    OrderSource,
    Position,
    SeasonStatus,
    Side,
    SimShapeError,
    SourceKind,
)

D = Decimal
SCHEMA_FILE = ENGINE_DIR.parent / "docs" / "api" / "sim-decisions-1.schema.json"


def doc(*turns) -> dict:
    return {"schema": "sim-decisions/1", "turns": list(turns)}


def order(asset=KR_A, side="BUY", quantity=100, **more) -> dict:
    unit = {"kind": "ORDER", "asset": asset, "side": side, "quantity": quantity}
    if side == "BUY" and "position" not in more:
        unit["position"] = "DEFENDER"
    unit = {**unit, **more}
    return {k: v for k, v in unit.items() if v is not None}


def run(*turns, length=12, strict=True, **settings):
    return play(inputs(length=length, **settings), parse_decisions(doc(*turns)), strict=strict)


# --- a tiny validator for the subset of JSON Schema the file uses -----------------------------


def schema() -> dict:
    return json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))


def valid(instance, node, root) -> bool:
    if "$ref" in node:
        target = root
        for part in node["$ref"].removeprefix("#/").split("/"):
            target = target[part]
        return valid(instance, target, root)
    if "const" in node:
        return instance == node["const"] and type(instance) is type(node["const"])
    if "enum" in node:
        return instance in node["enum"] and isinstance(instance, str)
    if "oneOf" in node:
        return sum(valid(instance, n, root) for n in node["oneOf"]) == 1
    kind = node.get("type")
    if kind == "object":
        if not isinstance(instance, dict):
            return False
        props = node.get("properties", {})
        if set(node.get("required", ())) - set(instance):
            return False
        if node.get("additionalProperties") is False and set(instance) - set(props):
            return False
        return all(valid(v, props[k], root) for k, v in instance.items() if k in props)
    if kind == "array":
        return isinstance(instance, list) and all(valid(v, node["items"], root) for v in instance)
    if kind == "integer":
        ok = isinstance(instance, int) and not isinstance(instance, bool)
        return ok and node.get("minimum", instance) <= instance <= node.get("maximum", instance)
    if kind == "boolean":
        return isinstance(instance, bool)
    if kind == "string":
        if not isinstance(instance, str) or len(instance) > node.get("maxLength", len(instance)):
            return False
        return "pattern" not in node or re.search(node["pattern"], instance) is not None
    return True


GOOD = doc(
    {"turn": 0, "units": [order(), order(US_X, position="FORWARD_TARGET", quantity=2)]},
    {
        "turn": 3,
        "units": [
            {
                "kind": "FORMATION",
                "goalkeeper": 20,
                "defender": 30,
                "midfielder": 30,
                "forward": 20,
            },
            order(KR_A, "SELL", 5, source={"kind": "FEED", "feedTurn": 3, "feedSeq": 2}),
            {"kind": "LINEUP", "asset": KR_A, "position": "MIDFIELDER", "expect": "ACCEPTED"},
        ],
    },
    {"turn": 7, "abandon": True},
)

# (name, document, expressible in the JSON Schema file)
BAD = [
    ("wrong schema", {**GOOD, "schema": "sim-decisions/2"}, True),
    ("extra top key", {**GOOD, "note": 1}, True),
    ("turns not a list", {"schema": "sim-decisions/1", "turns": {}}, True),
    ("negative turn", doc({"turn": -1}), True),
    ("bool turn", doc({"turn": True}), True),
    ("float turn", doc({"turn": 3.0}), True),
    ("turns repeat", doc({"turn": 2}, {"turn": 2}), False),
    ("turns go back", doc({"turn": 5}, {"turn": 2}), False),
    ("abandon twice", doc({"turn": 2, "abandon": True}, {"turn": 4, "abandon": True}), False),
    ("turn after abandon", doc({"turn": 2, "abandon": True}, {"turn": 3}), False),
    ("abandon not a bool", doc({"turn": 2, "abandon": 1}), True),
    ("units not a list", doc({"turn": 1, "units": {}}), True),
    ("turn extra key", doc({"turn": 1, "advance": False}), True),
    ("unknown unit kind", doc({"turn": 1, "units": [{"kind": "PAUSE"}]}), True),
    ("fractional quantity", doc({"turn": 0, "units": [order(quantity=1.5)]}), True),
    ("whole float quantity", doc({"turn": 0, "units": [order(quantity=3.0)]}), True),
    ("text quantity", doc({"turn": 0, "units": [order(quantity="5")]}), True),
    ("bool quantity", doc({"turn": 0, "units": [order(quantity=True)]}), True),
    ("quantity over int", doc({"turn": 0, "units": [order(quantity=2**31)]}), True),
    ("quantity under int", doc({"turn": 0, "units": [order(quantity=-(2**31) - 1)]}), True),
    ("lowercase asset", doc({"turn": 0, "units": [order("KR:aaa")]}), True),
    ("newline asset", doc({"turn": 0, "units": [order("KR:AAA\n")]}), True),
    ("other market", doc({"turn": 0, "units": [order("JP:AAA")]}), True),
    ("unknown side", doc({"turn": 0, "units": [order(side="HOLD")]}), True),
    ("unknown position", doc({"turn": 0, "units": [order(position="WINGER")]}), True),
    ("price key", doc({"turn": 0, "units": [order(price=100)]}), True),
    (
        "missing quantity",
        doc({"turn": 0, "units": [{"kind": "ORDER", "asset": KR_A, "side": "BUY"}]}),
        True,
    ),
    (
        "sell with position",
        doc({"turn": 1, "units": [order(side="SELL", position="DEFENDER")]}),
        False,
    ),
    ("feed without refs", doc({"turn": 1, "units": [order(source={"kind": "FEED"})]}), False),
    (
        "feed missing seq",
        doc({"turn": 1, "units": [order(source={"kind": "ALERT", "feedTurn": 1})]}),
        False,
    ),
    (
        "feed turn zero",
        doc({"turn": 1, "units": [order(source={"kind": "FEED", "feedTurn": 0, "feedSeq": 1})]}),
        True,
    ),
    (
        "direct with ref",
        doc({"turn": 1, "units": [order(source={"kind": "DIRECT", "feedSeq": 1})]}),
        False,
    ),
    ("unknown source kind", doc({"turn": 1, "units": [order(source={"kind": "TIP"})]}), True),
    ("lowercase expect", doc({"turn": 0, "units": [order(expect="accepted")]}), True),
    (
        "float share",
        doc(
            {
                "turn": 1,
                "units": [
                    {
                        "kind": "FORMATION",
                        "goalkeeper": 20.0,
                        "defender": 30,
                        "midfielder": 30,
                        "forward": 20,
                    }
                ],
            }
        ),
        True,
    ),
    (
        "formation missing share",
        doc(
            {
                "turn": 1,
                "units": [
                    {"kind": "FORMATION", "goalkeeper": 20, "defender": 30, "midfielder": 30}
                ],
            }
        ),
        True,
    ),
    (
        "long formation reason",
        doc(
            {
                "turn": 1,
                "units": [
                    {
                        "kind": "FORMATION",
                        "goalkeeper": 20,
                        "defender": 30,
                        "midfielder": 30,
                        "forward": 20,
                        "reason": "x" * 501,
                    }
                ],
            }
        ),
        True,
    ),
    (
        "lineup without position",
        doc({"turn": 1, "units": [{"kind": "LINEUP", "asset": KR_A}]}),
        True,
    ),
]


def test_good_document_parses_to_units_in_order():
    parsed = parse_decisions(GOOD)
    assert [p.turn for p in parsed.turns] == [0, 3, 7]
    assert parsed.turns[2] == TurnPlan(7, (), True)
    first, second = parsed.turns[0].units
    assert first == OrderUnit(Order(KR_A, Side.BUY, 100, Position.DEFENDER, OrderSource()))
    assert second.order.position is Position.FORWARD_TARGET
    formation, feed_sell, lineup = parsed.turns[1].units
    assert formation == FormationUnit(20, 30, 30, 20)
    assert feed_sell.order.source == OrderSource(SourceKind.FEED, 3, 2)
    assert lineup == LineupUnit(KR_A, Position.MIDFIELDER)
    assert lineup.expect == "ACCEPTED"


@pytest.mark.parametrize(("name", "bad", "_"), BAD, ids=[b[0] for b in BAD])
def test_shape_errors_raise_before_anything_runs(name, bad, _):
    with pytest.raises(SimShapeError):
        parse_decisions(bad)
    with pytest.raises(SimShapeError):
        play(inputs(), parse_decisions(bad))


def test_schema_file_and_parser_agree_on_the_shapes_the_file_can_express():
    root = schema()
    assert valid(GOOD, root, root)
    for name, bad, expressible in BAD:
        if expressible:
            assert not valid(bad, root, root), name


def test_schema_file_names_the_same_enums_keys_and_ranges_as_the_model():
    root = schema()
    defs = root["$defs"]
    assert defs["position"]["enum"] == [p.value for p in Position]
    assert defs["source"]["properties"]["kind"]["enum"] == [
        k.value
        for k in (
            SourceKind.DIRECT,
            SourceKind.RANKING,
            SourceKind.FEED,
            SourceKind.ALERT,
            SourceKind.BENCH,
        )
    ]
    assert defs["order"]["properties"]["side"]["enum"] == [s.value for s in Side]
    assert (defs["int32"]["minimum"], defs["int32"]["maximum"]) == (-(2**31), 2**31 - 1)
    assert set(defs["order"]["properties"]) == {
        "kind",
        "asset",
        "side",
        "quantity",
        "position",
        "source",
        "expect",
    }
    assert set(defs["formation"]["required"]) == {
        "kind",
        "goalkeeper",
        "defender",
        "midfielder",
        "forward",
    }
    assert set(defs["lineup"]["required"]) == {"kind", "asset", "position"}
    assert all(
        defs[k]["additionalProperties"] is False
        for k in ("order", "formation", "lineup", "source", "turn")
    )


# --- play ------------------------------------------------------------------------------------


def test_units_run_in_list_order_and_unlisted_turns_advance():
    played = run(
        {"turn": 0, "units": [order(KR_A, quantity=100)]},
        {"turn": 2, "units": [order(KR_A, "SELL", 60), order(KR_A, quantity=10, position=None)]},
    )
    season = played.season
    assert season.status is SeasonStatus.FINISHED and len(season.turns) == 12
    assert [(r.turn, r.index, r.verdict.code) for r in played.results] == [
        (0, 0, "ACCEPTED"),
        (2, 0, "ACCEPTED"),
        (2, 1, "ACCEPTED"),
    ]
    assert season.holdings[KR_A][0] == 50
    # the sell ran first: buying 10 first would have left a different average price at the sell
    assert [e.side for e in season.executions] == [Side.BUY, Side.SELL, Side.BUY]


def test_a_buy_listed_before_the_sell_runs_first():
    played = run(
        {"turn": 0, "units": [order(KR_A, quantity=100)]},
        {"turn": 1, "units": [order(KR_A, quantity=10, position=None), order(KR_A, "SELL", 105)]},
    )
    assert [r.verdict.code for r in played.results] == ["ACCEPTED", "ACCEPTED", "ACCEPTED"]
    assert played.season.holdings[KR_A][0] == 5


def test_formation_lineup_and_refusals_come_back_as_verdicts():
    played = run(
        {
            "turn": 0,
            "units": [
                order(KR_A, quantity=100),
                {
                    "kind": "FORMATION",
                    "goalkeeper": 20,
                    "defender": 30,
                    "midfielder": 30,
                    "forward": 20,
                },
                {"kind": "LINEUP", "asset": KR_A, "position": "MIDFIELDER"},
                order("KR:NOPE"),
            ],
        },
        {
            "turn": 1,
            "units": [
                {
                    "kind": "FORMATION",
                    "goalkeeper": 20,
                    "defender": 30,
                    "midfielder": 30,
                    "forward": 20,
                },
                {"kind": "LINEUP", "asset": KR_A, "position": "MIDFIELDER"},
                {
                    "kind": "FORMATION",
                    "goalkeeper": 10,
                    "defender": 30,
                    "midfielder": 30,
                    "forward": 20,
                },
            ],
        },
    )
    assert [r.verdict.code for r in played.results] == [
        "ACCEPTED",
        "TURN_ZERO_FORMATION",
        "TURN_MISMATCH",
        "ASSET_NOT_IN_POOL",
        "ACCEPTED",
        "ACCEPTED",
        "FORMATION_INVALID",
    ]


def test_abandon_runs_the_auto_turns_after_the_listed_units():
    played = run(
        {"turn": 0, "units": [order(KR_A, quantity=100)]},
        {"turn": 4, "units": [order(KR_A, "SELL", 40)], "abandon": True},
    )
    season = played.season
    assert (season.status, season.abandoned_turn, season.current_turn) == (
        SeasonStatus.FINISHED,
        4,
        12,
    )
    assert len(season.turns) == 12 and season.holdings[KR_A][0] == 60
    assert season.not_started is False


def test_abandon_before_turn_three_is_not_started_for_a_human_only():
    human = run({"turn": 2, "abandon": True}).season
    agent = run({"turn": 2, "abandon": True}, member_kind="AGENT").season
    assert (human.not_started, agent.not_started) == (True, False)


def test_a_turn_at_or_after_the_path_length_is_a_shape_error_and_the_last_turn_is_not():
    with pytest.raises(SimShapeError):
        run({"turn": 12, "units": [order()]})
    assert (
        run({"turn": 11, "units": [order(KR_A, quantity=1)]}).season.status is SeasonStatus.FINISHED
    )


def test_a_sixty_turn_list_plays_to_the_end():
    played = run({"turn": 0, "units": [order()]}, length=60)
    assert len(played.season.turns) == 60


def test_expect_is_carried_but_never_changes_the_run():
    right = run(
        {
            "turn": 0,
            "units": [order(expect="ACCEPTED"), order("KR:NOPE", expect="ASSET_NOT_IN_POOL")],
        }
    )
    wrong = run(
        {
            "turn": 0,
            "units": [order(expect="INSUFFICIENT_CASH"), order("KR:NOPE", expect="ACCEPTED")],
        }
    )
    assert [r.verdict for r in right.results] == [r.verdict for r in wrong.results]
    assert right.season.ledger == wrong.season.ledger
    assert [r.unit.expect for r in wrong.results] == ["INSUFFICIENT_CASH", "ACCEPTED"]


def test_the_same_decisions_give_the_same_run():
    turns = (
        {
            "turn": 0,
            "units": [order(KR_A, quantity=300), order(US_X, quantity=3, position="MIDFIELDER")],
        },
        {"turn": 5, "units": [order(US_X, "SELL", 1)]},
    )
    first, second = run(*turns), run(*turns)
    assert first.season.ledger == second.season.ledger and first.season.turns == second.season.turns


# --- turn-decision/1 adapter -----------------------------------------------------------------


def decision(orders=(), formation=None, theses=(), reason="r") -> dict:
    return {
        "schema": "turn-decision/1",
        "formation_change": formation,
        "orders": list(orders),
        "theses": list(theses),
        "reason": reason,
    }


def proposal(asset=KR_A, side="BUY", quantity=10, source=None, **more) -> dict:
    out = {
        "asset": asset,
        "side": side,
        "quantity": quantity,
        "source": source or {"kind": "DIRECT"},
    }
    if side == "BUY" and "position" not in more:
        out["position"] = "DEFENDER"
    return {**out, **more}


FORMATION = {"goalkeeper": 20, "defender": 30, "midfielder": 30, "forward": 20, "reason": "tilt"}


def test_adapter_orders_formation_then_sells_then_buys_each_in_reply_order():
    units = from_turn_decision(
        decision(
            [
                proposal(KR_A, "BUY", 1),
                proposal(KR_B, "SELL", 2),
                proposal(US_X, "BUY", 3, position="FORWARD_TARGET"),
                proposal(KR_A, "SELL", 4),
            ],
            FORMATION,
        ),
        3,
    )
    assert isinstance(units[0], FormationUnit) and units[0].reason == "tilt"
    assert [(u.order.side, u.order.asset, u.order.quantity) for u in units[1:]] == [
        (Side.SELL, KR_B, 2),
        (Side.SELL, KR_A, 4),
        (Side.BUY, KR_A, 1),
        (Side.BUY, US_X, 3),
    ]


def test_adapter_feed_reference_is_this_turn_and_the_reply_seq():
    units = from_turn_decision(
        decision(
            [
                proposal(source={"kind": "FEED", "feed_seq": 2}),
                proposal(source={"kind": "ALERT", "feed_seq": 5}),
                proposal(source={"kind": "RANKING", "ranking_rank": 1}),
                proposal(source={"kind": "DIRECT", "feed_seq": 9}),
            ]
        ),
        3,
    )
    assert [u.order.source for u in units] == [
        OrderSource(SourceKind.FEED, 3, 2),
        OrderSource(SourceKind.ALERT, 3, 5),
        OrderSource(SourceKind.RANKING),
        OrderSource(SourceKind.DIRECT),
    ]


@pytest.mark.parametrize(
    ("source", "turn"),
    [
        ({"kind": "FEED"}, 3),
        ({"kind": "ALERT", "feed_seq": 0}, 3),
        ({"kind": "FEED", "feed_seq": -4}, 3),
        ({"kind": "FEED", "feed_seq": 1}, 0),
    ],
)
def test_adapter_feed_reference_that_cannot_exist_is_refused_as_not_visible(source, turn):
    (unit,) = from_turn_decision(decision([proposal(source=source)]), turn)
    assert unit == RejectedUnit("SOURCE_NOT_VISIBLE")


def test_adapter_theses_become_a_rejected_unit_after_the_orders():
    units = from_turn_decision(decision([proposal()], theses=[{"any": 1}]), 1)
    assert isinstance(units[0], OrderUnit) and units[-1] == RejectedUnit("THESES_NOT_SUPPORTED")


def agent(turn: int, reply: dict, **settings):
    plan = TurnPlan(turn, from_turn_decision(reply, turn))
    return play(inputs(**settings), Decisions((plan,)), strict=True)


def test_agent_sell_with_a_position_gets_the_agent_verdict_at_its_place_in_the_order():
    base = {"turn": 0, "units": [order(KR_A, quantity=100)]}
    played = play(
        inputs(),
        Decisions(
            (
                *parse_decisions(doc(base)).turns,
                TurnPlan(
                    1,
                    from_turn_decision(
                        decision(
                            [
                                proposal(KR_A, "SELL", 10, position="DEFENDER"),
                                proposal("KR:NOPE", "SELL", 10, position="DEFENDER"),
                                proposal(KR_A, "SELL", 0, position="DEFENDER"),
                                proposal(
                                    KR_A, "SELL", 10, source={"kind": "BENCH"}, position="DEFENDER"
                                ),
                                proposal(KR_A, "SELL", 10),
                            ]
                        ),
                        1,
                    ),
                ),
            )
        ),
        strict=True,
    )
    assert [r.verdict.code for r in played.results[1:]] == [
        "POSITION_ON_SELL",
        "ASSET_NOT_IN_POOL",
        "QUANTITY_INVALID",
        "SOURCE_NOT_ALLOWED",
        "ACCEPTED",
    ]
    assert played.season.holdings[KR_A][0] == 90  # only the clean sell filled


def test_agent_formation_failure_keeps_the_formation_and_the_orders_still_run():
    played = agent(0, decision([proposal(KR_A, "BUY", 10)], FORMATION))
    assert [r.verdict.code for r in played.results] == ["TURN_ZERO_FORMATION", "ACCEPTED"]
    assert played.season.formation == inputs().settings.start_formation
    assert played.season.executions[0].formation_change is None


@pytest.mark.parametrize(
    ("name", "mutate"),
    [
        ("wrong schema", lambda d: d.update(schema="turn-decision/2")),
        ("missing reason", lambda d: d.pop("reason")),
        ("extra key", lambda d: d.update(price=1)),
        ("long reason", lambda d: d.update(reason="x" * 501)),
        ("orders not a list", lambda d: d.update(orders={})),
        ("too many orders", lambda d: d.update(orders=[proposal() for _ in range(41)])),
        ("theses not objects", lambda d: d.update(theses=[1])),
        ("21 theses", lambda d: d.update(theses=[{} for _ in range(21)])),
        (
            "text ranking rank",
            lambda d: d["orders"][0]["source"].update(kind="RANKING", ranking_rank="1"),
        ),
        (
            "huge ranking rank",
            lambda d: d["orders"][0]["source"].update(kind="RANKING", ranking_rank=2**31),
        ),
        ("fractional quantity", lambda d: d["orders"][0].update(quantity=1.5)),
        ("order price", lambda d: d["orders"][0].update(price=5)),
        ("bad asset", lambda d: d["orders"][0].update(asset="KR:aaa")),
        ("missing source", lambda d: d["orders"][0].pop("source")),
        ("bad source key", lambda d: d["orders"][0]["source"].update(feed_turn=1)),
        ("formation extra key", lambda d: d.update(formation_change={**FORMATION, "x": 1})),
        (
            "formation missing reason",
            lambda d: d.update(
                formation_change={k: v for k, v in FORMATION.items() if k != "reason"}
            ),
        ),
        (
            "formation long reason",
            lambda d: d.update(formation_change={**FORMATION, "reason": "x" * 201}),
        ),
    ],
)
def test_adapter_shape_errors_refuse_the_whole_reply(name, mutate):
    reply = decision([proposal()])
    mutate(reply)
    with pytest.raises(SimShapeError):
        from_turn_decision(copy.deepcopy(reply), 1)


def test_adapter_keeps_the_reply_index_across_the_reordering():
    units = from_turn_decision(
        decision(
            [
                proposal(KR_A, "BUY", 1),
                proposal(KR_B, "SELL", 2),
                proposal(US_X, "BUY", 3, source={"kind": "FEED"}, position="FORWARD_TARGET"),
                proposal(KR_A, "SELL", 4),
            ]
        ),
        3,
    )
    assert [(type(u).__name__, u.reply_index) for u in units] == [
        ("OrderUnit", 1),
        ("OrderUnit", 3),
        ("OrderUnit", 0),
        ("RejectedUnit", 2),
    ]


CORPUS = ENGINE_DIR.parent / "src" / "test" / "resources" / "agent" / "turn-decision"


def test_adapter_agrees_with_the_java_turn_decision_corpus():
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    assert len(manifest["files"]) >= 50
    for entry in manifest["files"]:
        reply = json.loads((CORPUS / entry["file"]).read_text(encoding="utf-8"))
        if entry["valid"]:
            from_turn_decision(reply, 3)
        else:
            with pytest.raises(SimShapeError):
                from_turn_decision(reply, 3)

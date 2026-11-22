"""Decision lists for the simulator: `sim-decisions/1` (spec `docs/api/sim-decisions-1.schema.json`)
and the adapter from `turn-decision/1`.

Parsing is strict and happens before any unit runs: a shape error raises `SimShapeError` and no
season is touched. Value rules (a quantity of zero, a pool miss, shares that do not add up) are
verdicts that `Season` answers one unit at a time. `expect` is carried on the units for the parity
test and is never read here.
"""

import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

from .model import (
    Order,
    OrderSource,
    Position,
    Side,
    SimInputs,
    SimShapeError,
    SourceKind,
    Verdict,
)
from .rules import INT_MAX, INT_MIN
from .season import Season

SCHEMA = "sim-decisions/1"
TURN_DECISION_SCHEMA = "turn-decision/1"
ASSET_KEY = re.compile(r"(KR|US):[A-Z0-9.]{1,12}\Z")
EXPECT = re.compile(r"[A-Z_]{1,40}\Z")


@dataclass(frozen=True)
class OrderUnit:
    order: Order
    position_on_sell: bool = False
    expect: str | None = field(default=None, compare=False)
    reply_index: int | None = field(default=None, compare=False)


@dataclass(frozen=True)
class FormationUnit:
    goalkeeper: int
    defender: int
    midfielder: int
    forward: int
    reason: str | None = None
    expect: str | None = field(default=None, compare=False)


@dataclass(frozen=True)
class LineupUnit:
    asset: str
    position: Position
    expect: str | None = field(default=None, compare=False)


@dataclass(frozen=True)
class RejectedUnit:
    """A unit the agent path refuses before the season is consulted (no state can change)."""

    code: str
    expect: str | None = field(default=None, compare=False)
    reply_index: int | None = field(default=None, compare=False)


Unit = OrderUnit | FormationUnit | LineupUnit | RejectedUnit


@dataclass(frozen=True)
class TurnPlan:
    turn: int
    units: tuple[Unit, ...] = ()
    abandon: bool = False


@dataclass(frozen=True)
class Decisions:
    turns: tuple[TurnPlan, ...]


@dataclass(frozen=True)
class UnitResult:
    turn: int
    index: int
    unit: Unit
    verdict: Verdict


@dataclass(frozen=True)
class Played:
    season: Season
    results: tuple[UnitResult, ...]


def _fail(where: str, why: str) -> SimShapeError:
    return SimShapeError(f"{where}: {why}")


def _mapping(value: Any, where: str, required: set[str], optional: set[str]) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise _fail(where, "must be an object")
    keys = set(value)
    if not all(isinstance(k, str) for k in keys) or keys - required - optional:
        raise _fail(where, "has a key the schema does not allow")
    if required - keys:
        raise _fail(where, "misses a required key")
    return value


def _int(value: Any, where: str, low: int = INT_MIN, high: int = INT_MAX) -> int:
    # bool is an int subclass and 3.0 is not a whole number to Java: both refused.
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise _fail(where, "must be a whole number in range")
    return value


def _text(value: Any, where: str, limit: int) -> str:
    if not isinstance(value, str) or len(value) > limit:
        raise _fail(where, "must be text within its length limit")
    return value


def _enum[E: (Position, Side, SourceKind)](cls: type[E], value: Any, where: str) -> E:
    if not isinstance(value, str) or value not in {m.value for m in cls}:
        raise _fail(where, "is not an allowed value")
    return cls(value)


def _asset(value: Any, where: str) -> str:
    if not isinstance(value, str) or not ASSET_KEY.match(value):
        raise _fail(where, "must look like MARKET:TICKER")
    return value


def _expect(raw: Mapping[str, Any], where: str) -> str | None:
    value = raw.get("expect")
    if value is None:
        return None
    if not isinstance(value, str) or not EXPECT.match(value):
        raise _fail(where, "expect must be a code name")
    return value


def _source(raw: Any, where: str) -> OrderSource:
    body = _mapping(raw, where, {"kind"}, {"feedTurn", "feedSeq"})
    kind = _enum(SourceKind, body["kind"], f"{where}/kind")
    turn = _int(body["feedTurn"], f"{where}/feedTurn") if "feedTurn" in body else None
    seq = _int(body["feedSeq"], f"{where}/feedSeq") if "feedSeq" in body else None
    return OrderSource(kind, turn, seq)


def _unit(raw: Any, where: str) -> Unit:
    if not isinstance(raw, Mapping) or raw.get("kind") not in ("ORDER", "FORMATION", "LINEUP"):
        raise _fail(where, "kind must be ORDER, FORMATION or LINEUP")
    kind = raw["kind"]
    if kind == "ORDER":
        body = _mapping(
            raw,
            where,
            {"kind", "asset", "side", "quantity"},
            {"position", "source", "expect"},
        )
        position = (
            _enum(Position, body["position"], f"{where}/position") if "position" in body else None
        )
        order = Order(
            _asset(body["asset"], f"{where}/asset"),
            _enum(Side, body["side"], f"{where}/side"),
            _int(body["quantity"], f"{where}/quantity"),
            position,
            _source(body["source"], f"{where}/source") if "source" in body else OrderSource(),
        )
        return OrderUnit(order, expect=_expect(body, where))
    if kind == "FORMATION":
        body = _mapping(
            raw,
            where,
            {"kind", "goalkeeper", "defender", "midfielder", "forward"},
            {"reason", "expect"},
        )
        return FormationUnit(
            _int(body["goalkeeper"], f"{where}/goalkeeper"),
            _int(body["defender"], f"{where}/defender"),
            _int(body["midfielder"], f"{where}/midfielder"),
            _int(body["forward"], f"{where}/forward"),
            _text(body["reason"], f"{where}/reason", 500) if "reason" in body else None,
            _expect(body, where),
        )
    body = _mapping(raw, where, {"kind", "asset", "position"}, {"expect"})
    return LineupUnit(
        _asset(body["asset"], f"{where}/asset"),
        _enum(Position, body["position"], f"{where}/position"),
        _expect(body, where),
    )


def parse_decisions(doc: Any) -> Decisions:
    """`sim-decisions/1` to units. Turns strictly increase; nothing may follow an abandon."""
    body = _mapping(doc, "decisions", {"schema", "turns"}, set())
    if body["schema"] != SCHEMA:
        raise _fail("decisions/schema", f"must be {SCHEMA}")
    if not isinstance(body["turns"], list):
        raise _fail("decisions/turns", "must be a list")
    plans: list[TurnPlan] = []
    for i, raw in enumerate(body["turns"]):
        where = f"decisions/turns/{i}"
        turn_body = _mapping(raw, where, {"turn"}, {"units", "abandon"})
        turn = _int(turn_body["turn"], f"{where}/turn", 0)
        if plans and turn <= plans[-1].turn:
            raise _fail(where, "turns must strictly increase")
        if plans and plans[-1].abandon:
            raise _fail(where, "nothing can follow an abandon")
        abandon = turn_body.get("abandon", False)
        if not isinstance(abandon, bool):
            raise _fail(f"{where}/abandon", "must be true or false")
        units = turn_body.get("units", [])
        if not isinstance(units, list):
            raise _fail(f"{where}/units", "must be a list")
        plans.append(
            TurnPlan(
                turn, tuple(_unit(u, f"{where}/units/{k}") for k, u in enumerate(units)), abandon
            )
        )
    return Decisions(tuple(plans))


_DECISION_ORDER_MAX = 40
_DECISION_THESES_MAX = 20


def from_turn_decision(doc: Any, turn: int) -> tuple[Unit, ...]:
    """`turn-decision/1` to units for decision turn `turn`: formation, sells, then buys.

    The decision's shape is checked as a whole (Java refuses an invalid reply as one piece).
    Agent-path refusals that never touch the season become `RejectedUnit`: a sell that declares
    a position, a FEED or ALERT order without a usable `feed_seq`, and any theses. An agent sees
    only this turn's feed, so a feed reference is `(turn, feed_seq)`. Order units keep
    `reply_index`, the position in the reply, because they are reordered (Java returns results
    by that index).

    A `SimShapeError` is Java's INVALID outcome: no unit runs and the turn still advances, so a bot
    or backtest loop should catch it and play an empty turn. RANKING board membership is not
    checked (the simulator has no ranking board).
    """
    body = _mapping(
        doc, "decision", {"schema", "formation_change", "orders", "theses", "reason"}, set()
    )
    if body["schema"] != TURN_DECISION_SCHEMA:
        raise _fail("decision/schema", f"must be {TURN_DECISION_SCHEMA}")
    _text(body["reason"], "decision/reason", 500)
    raw_orders, theses = body["orders"], body["theses"]
    if not isinstance(raw_orders, list) or len(raw_orders) > _DECISION_ORDER_MAX:
        raise _fail("decision/orders", "must be a list of at most 40")
    if not isinstance(theses, list) or len(theses) > _DECISION_THESES_MAX:
        raise _fail("decision/theses", "must be a list of at most 20")
    if not all(isinstance(t, Mapping) for t in theses):
        raise _fail("decision/theses", "items must be objects")

    units: list[Unit] = []
    change = body["formation_change"]
    if change is not None:
        f = _mapping(
            change,
            "decision/formation_change",
            {"goalkeeper", "defender", "midfielder", "forward", "reason"},
            set(),
        )
        units.append(
            FormationUnit(
                _int(f["goalkeeper"], "decision/formation_change/goalkeeper"),
                _int(f["defender"], "decision/formation_change/defender"),
                _int(f["midfielder"], "decision/formation_change/midfielder"),
                _int(f["forward"], "decision/formation_change/forward"),
                _text(f["reason"], "decision/formation_change/reason", 200),
            )
        )
    proposals = [_proposal(o, f"decision/orders/{i}", turn, i) for i, o in enumerate(raw_orders)]
    for side in (Side.SELL, Side.BUY):
        units.extend(unit for s, unit in proposals if s is side)
    if theses:
        units.append(RejectedUnit("THESES_NOT_SUPPORTED"))
    return tuple(units)


def _proposal(raw: Any, where: str, turn: int, index: int) -> tuple[Side, Unit]:
    body = _mapping(raw, where, {"asset", "side", "quantity", "source"}, {"position"})
    asset = _asset(body["asset"], f"{where}/asset")
    side = _enum(Side, body["side"], f"{where}/side")
    quantity = _int(body["quantity"], f"{where}/quantity")
    position = (
        _enum(Position, body["position"], f"{where}/position") if "position" in body else None
    )
    src = _mapping(body["source"], f"{where}/source", {"kind"}, {"feed_seq", "ranking_rank"})
    kind = _enum(SourceKind, src["kind"], f"{where}/source/kind")
    seq = _int(src["feed_seq"], f"{where}/source/feed_seq") if "feed_seq" in src else None
    if "ranking_rank" in src:
        _int(src["ranking_rank"], f"{where}/source/ranking_rank")
    if kind in (SourceKind.FEED, SourceKind.ALERT):
        if seq is None or seq < 1 or turn < 1:
            return side, RejectedUnit("SOURCE_NOT_VISIBLE", reply_index=index)
        source = OrderSource(kind, turn, seq)
    else:
        source = OrderSource(kind)
    on_sell = side is Side.SELL and position is not None
    order = Order(asset, side, quantity, None if side is Side.SELL else position, source)
    return side, OrderUnit(order, position_on_sell=on_sell, reply_index=index)


def play(inputs: SimInputs, decisions: Decisions, *, strict: bool = False) -> Played:
    """Run a whole season: each listed turn's units in order, then an advance (or the abandon).

    Turns that are not listed advance with no units. A turn at or after the path length would act
    on a finished season and is a shape error, raised before the season is built.
    """
    length = inputs.settings.path_length
    for plan in decisions.turns:
        if plan.turn >= length:
            raise SimShapeError(f"turn {plan.turn} comes after the last turn to decide")
    by_turn = {plan.turn: plan for plan in decisions.turns}
    season = Season(inputs, strict=strict)
    results: list[UnitResult] = []
    for turn in range(length):
        plan = by_turn.get(turn)
        if plan is not None:
            for index, unit in enumerate(plan.units):
                results.append(UnitResult(turn, index, unit, apply_unit(season, unit)))
            if plan.abandon:
                season.abandon()
                season.run_abandoned()
                break
        season.advance()
    return Played(season, tuple(results))


def apply_unit(season: Season, unit: Unit) -> Verdict:
    if isinstance(unit, OrderUnit):
        return season.place_order(unit.order, position_on_sell=unit.position_on_sell)
    if isinstance(unit, FormationUnit):
        return season.change_formation(
            unit.goalkeeper, unit.defender, unit.midfielder, unit.forward, unit.reason
        )
    if isinstance(unit, LineupUnit):
        return season.change_lineup(unit.asset, unit.position)
    return Verdict(False, unit.code)

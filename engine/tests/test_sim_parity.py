"""The Java-played parity seasons replayed by the Python simulator.

`seasons-v1.json` is written by `SimulatorParityFixtureTest` (Java is the oracle): 20 seasons
played over the REST API on a synthetic history, with the inputs, the decisions the script sent
(each unit carries the verdict it was meant to get) and everything Java stored or answered. This
test builds each season again offline and compares it field by field, exactly: every verdict,
execution, ledger row, holdings and lineup spans after each turn, turn price and snapshot, the 38
report values, the stored score, and the percentile request body (weight order included). A
failure names the season, the part and the first differing field; values are shown only for that
one pair. Regenerate the file with `ANTLAB_PARITY_WRITE=1`.
"""

import copy
import json
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from antlab_engine.sim import rules
from antlab_engine.sim.behaviour import TurnSignals
from antlab_engine.sim.counts import FixedCounts, PercentileCounts, RankCounts
from antlab_engine.sim.decisions import (
    FormationUnit,
    LineupUnit,
    OrderUnit,
    Played,
    Unit,
    from_turn_decision,
    parse_decisions,
    play,
)
from antlab_engine.sim.model import (
    Formation,
    PathStep,
    PoolAsset,
    Position,
    Settings,
    SimInputs,
    SimShapeError,
    Verdict,
)
from antlab_engine.sim.score import score_season
from antlab_engine.sim.season import Season

FIXTURE = Path(__file__).resolve().parents[2] / "src/test/resources/sim/parity/seasons-v1.json"
INDEXES = list(range(1, 21))


def _load() -> dict[str, Any]:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


DOC = _load()
SEASONS = {s["index"]: s for s in DOC["seasons"]}


# ---------------------------------------------------------------------------- sim-inputs/1 reader


def _need(body: Mapping[str, Any], where: str, keys: set[str]) -> None:
    if not isinstance(body, Mapping) or set(body) != keys:
        raise SimShapeError(f"{where}: keys must be exactly {sorted(keys)}")


def inputs_from_wire(doc: Mapping[str, Any]) -> SimInputs:
    """`sim-inputs/1` to `SimInputs`; every key is required, `systemPosition` may be null."""
    _need(doc, "inputs", {"settings", "pool", "path"})
    s = doc["settings"]
    _need(
        s,
        "settings",
        {
            "initialCash",
            "feeRateKr",
            "feeRateUs",
            "fxSpreadRate",
            "riskLimit",
            "startFormation",
            "pathLength",
            "memberKind",
            "singleAssetLimit",
        },
    )
    cash = Decimal(s["initialCash"])
    if cash % 2:
        raise SimShapeError("settings: an odd initial cash would be refused by the baseline")
    f = s["startFormation"]
    _need(f, "startFormation", {"goalkeeper", "defender", "midfielder", "forward"})
    settings = Settings(
        initial_cash=cash,
        fee_rate_kr=Decimal(s["feeRateKr"]),
        fee_rate_us=Decimal(s["feeRateUs"]),
        fx_spread_rate=Decimal(s["fxSpreadRate"]),
        risk_limit=Decimal(s["riskLimit"]),
        start_formation=Formation(f["goalkeeper"], f["defender"], f["midfielder"], f["forward"]),
        path_length=s["pathLength"],
        member_kind=s["memberKind"],
        single_asset_limit=None
        if s["singleAssetLimit"] is None
        else Decimal(s["singleAssetLimit"]),
    )
    pool = []
    for i, a in enumerate(doc["pool"]):
        _need(a, f"pool/{i}", {"asset", "currency", "basePrice", "baseFxRate", "systemPosition"})
        position = None if a["systemPosition"] is None else Position(a["systemPosition"])
        pool.append(
            PoolAsset(
                a["asset"],
                a["currency"],
                Decimal(a["basePrice"]),
                Decimal(a["baseFxRate"]),
                position,
            )
        )
    path = []
    for i, step in enumerate(doc["path"]):
        _need(step, f"path/{i}", {"turn", "localReturns", "fxChange"})
        path.append(
            PathStep(
                step["turn"],
                {k: Decimal(v) for k, v in step["localReturns"].items()},
                {k: Decimal(v) for k, v in step["fxChange"].items()},
            )
        )
    return SimInputs(settings, tuple(pool), tuple(path))


# ---------------------------------------------------------------------------- comparison


def _differ(actual: Any, expected: Any, path: str) -> str | None:
    """The path of the first difference (and that pair only); decimals compare by value."""
    if isinstance(actual, Mapping) and isinstance(expected, Mapping):
        for key in sorted(set(actual) | set(expected)):
            if key not in actual or key not in expected:
                return f"{path}.{key} (missing on one side)"
            inner = _differ(actual[key], expected[key], f"{path}.{key}")
            if inner:
                return inner
        return None
    if isinstance(actual, list | tuple) and isinstance(expected, list | tuple):
        if len(actual) != len(expected):
            return f"{path} (length {len(actual)} vs {len(expected)})"
        for i, (a, e) in enumerate(zip(actual, expected, strict=True)):
            inner = _differ(a, e, f"{path}[{i}]")
            if inner:
                return inner
        return None
    if actual == expected and type(actual) is type(expected):
        return None
    return f"{path}: python {actual!r}, java {expected!r}"


def _same(index: int, part: str, actual: Any, expected: Any) -> None:
    where = _differ(actual, expected, part)
    assert where is None, f"season {index}: {where}"


def _dec(value: str | None) -> Decimal | None:
    return None if value is None else Decimal(value)


# ---------------------------------------------------------------------------- the replay


@dataclass
class Replay:
    played: Played
    loop: Season
    holdings: list[dict[str, Any]]


def _apply(season: Season, unit: Unit) -> Verdict:
    if isinstance(unit, OrderUnit):
        return season.place_order(unit.order, position_on_sell=unit.position_on_sell)
    if isinstance(unit, FormationUnit):
        return season.change_formation(
            unit.goalkeeper, unit.defender, unit.midfielder, unit.forward, unit.reason
        )
    assert isinstance(unit, LineupUnit)
    return season.change_lineup(unit.asset, unit.position)


def _holdings_at(season: Season, turn: int) -> dict[str, Any]:
    held = {a: [q, avg] for a, (q, avg) in season.holdings.items() if q > 0}
    return {"turn": turn, "holdings": held}


def replay(index: int) -> Replay:
    entry = SEASONS[index]
    inputs = inputs_from_wire(entry["inputs"])
    decisions = parse_decisions(entry["decisions"])
    played = play(inputs, decisions)

    # The same loop with the holdings written down after each turn's units (what Java recorded).
    season = Season(inputs, strict=True)
    by_turn = {p.turn: p for p in decisions.turns}
    holdings = []
    for turn in range(inputs.settings.path_length):
        plan = by_turn.get(turn)
        if plan is not None:
            for unit in plan.units:
                _apply(season, unit)
        holdings.append(_holdings_at(season, turn))
        if plan is not None and plan.abandon:
            season.abandon()
            season.run_abandoned()
            break
        season.advance()
    holdings.append(_holdings_at(season, inputs.settings.path_length))
    return Replay(played, season, holdings)


def _java_holdings(java: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "turn": h["turn"],
            "holdings": {a: [v[0], Decimal(v[1])] for a, v in h["holdings"].items()},
        }
        for h in java
    ]


def _expected_codes(index: int) -> list[str]:
    return [u["expect"] for t in SEASONS[index]["decisions"]["turns"] for u in t.get("units", [])]


@pytest.mark.parametrize("index", INDEXES)
def test_the_season_replays_exactly(index: int) -> None:
    entry = SEASONS[index]
    java = entry["java"]
    r = replay(index)
    # Everything is compared from what the public `play` returns; the loop only adds holdings.
    season = r.played.season

    loop = r.loop
    for part in ("ledger", "executions", "turns", "spans", "formation_changes", "turn_prices"):
        _same(index, f"play vs loop {part}", getattr(season, part), getattr(loop, part))
    _same(index, "play vs loop status", season.status, loop.status)
    _same(index, "play vs loop abandoned turn", season.abandoned_turn, loop.abandoned_turn)

    # Verdicts: what Java was meant to answer, unit by unit.
    codes = [res.verdict.code for res in r.played.results]
    expected = _expected_codes(index)
    _same(index, "verdicts", codes, expected)
    for res in r.played.results:
        assert res.verdict.accepted == (res.verdict.code == "ACCEPTED"), (
            f"season {index} turn {res.turn}"
        )

    _same(index, "status", season.status.value, java["status"])
    _same(index, "abandonedTurn", season.abandoned_turn, java["abandonedTurn"])

    _same(
        index,
        "executions",
        [
            {
                "turn": e.turn,
                "asset": e.asset,
                "side": e.side.value,
                "quantity": e.quantity,
                "price": e.price,
                "fee": e.fee,
                "realizedPnl": e.realized_pnl,
                "source": e.source.kind.value,
                "feedTurn": e.source.feed_turn,
                "feedSeq": e.source.feed_seq,
                "formationChange": e.formation_change,
            }
            for e in season.executions
        ],
        [
            {
                **{
                    k: x[k]
                    for k in ("turn", "asset", "side", "quantity", "source", "feedTurn", "feedSeq")
                },
                "price": Decimal(x["price"]),
                "fee": Decimal(x["fee"]),
                "realizedPnl": _dec(x["realizedPnl"]),
                "formationChange": x["formationChange"],
            }
            for x in java["executions"]
        ],
    )
    _same(
        index,
        "ledger",
        [
            {
                "type": row.type.value,
                "amount": row.amount,
                "balanceAfter": row.balance_after,
                "turn": row.turn,
                "execution": row.execution,
            }
            for row in season.ledger
        ],
        [
            {
                "type": x["type"],
                "amount": Decimal(x["amount"]),
                "balanceAfter": Decimal(x["balanceAfter"]),
                "turn": x["turn"],
                "execution": x["execution"],
            }
            for x in java["ledger"]
        ],
    )
    _same(index, "holdingsByTurn", r.holdings, _java_holdings(java["holdingsByTurn"]))
    _same(
        index,
        "spans",
        [
            {
                "asset": s.asset,
                "position": s.position.value,
                "startTurn": s.start_turn,
                "endTurn": s.end_turn,
            }
            for s in season.spans
        ],
        java["spans"],
    )
    _same(
        index,
        "formationChanges",
        [
            {
                "turn": c.turn,
                "before": [
                    c.before.goalkeeper,
                    c.before.defender,
                    c.before.midfielder,
                    c.before.forward,
                ],
                "after": [
                    c.after.goalkeeper,
                    c.after.defender,
                    c.after.midfielder,
                    c.after.forward,
                ],
                "reason": c.reason,
            }
            for c in season.formation_changes
        ],
        java["formationChanges"],
    )
    _same(
        index,
        "turnPrices",
        season.turn_prices,
        [{a: Decimal(p) for a, p in step.items()} for step in java["turnPrices"]],
    )
    _same(
        index,
        "turns",
        [
            {
                "turn": t.turn,
                "cash": t.cash,
                "holdingsValue": t.holdings_value,
                "total": t.total,
                "goalkeeper": t.goalkeeper,
                "defender": t.defender,
                "midfielder": t.midfielder,
                "forward": t.forward,
                "drawdown": t.drawdown,
            }
            for t in season.turns
        ],
        [{k: (v if k == "turn" else Decimal(v)) for k, v in x.items()} for x in java["turns"]],
    )

    counts = entry["counts"]
    fixed = FixedCounts(
        PercentileCounts(
            RankCounts(counts["sharpeDiff"]["below"], counts["sharpeDiff"]["equal"], counts["n"]),
            RankCounts(counts["endMultiple"]["below"], counts["endMultiple"]["equal"], counts["n"]),
        )
    )
    signals = [
        TurnSignals(
            s["turn"], frozenset(s["up"]), frozenset(s["down"]), frozenset(s["topAttention"])
        )
        for s in entry["signals"]
    ]
    scored = score_season(
        season,
        fixed,
        season_seed=int(entry["seed"]),
        base_date=date.fromisoformat(DOC["header"]["baseDate"]),
        path_count=DOC["header"]["pathCount"],
        signals=signals,
    )
    assert scored.percentile_request is not None
    _same(
        index, "percentileRequest", scored.percentile_request.to_wire(), java["percentileRequest"]
    )
    _same(index, "report", scored.report.to_wire(), java["report"])
    score = {**scored.score.to_wire(), "ratingExclusion": None}
    if scored.exclusion is not None:
        score["ratingExclusion"] = scored.exclusion.value
    _same(index, "score", score, java["score"])


# ---------------------------------------------------------------------------- the file itself


def test_the_header_constants_equal_the_python_rules() -> None:
    h = DOC["header"]
    assert DOC["schema"] == h["schema"] == "sim-parity/1"
    assert h["scoreVersion"] == rules.SCORE_VERSION
    assert (h["seasonMinMonths"], h["seasonMinUp"], h["seasonMinDown"], h["annualTurns"]) == (
        rules.SEASON_MIN_MONTHS,
        rules.SEASON_MIN_UP,
        rules.SEASON_MIN_DOWN,
        rules.ANNUAL_TURNS,
    )
    decimals = {
        "band": rules.BAND,
        "topRatio": rules.TOP_RATIO,
        "benchKrWeight": rules.BENCH_KR_WEIGHT,
        "benchUsWeight": rules.BENCH_US_WEIGHT,
        "goalkeeperDownMax": rules.GOALKEEPER_DOWN_MAX,
        "defenderDownBelow": rules.DEFENDER_DOWN_BELOW,
        "breakoutUpAbove": rules.BREAKOUT_UP_ABOVE,
        "breakoutMddFactor": rules.BREAKOUT_MDD_FACTOR,
        "defensiveUpMin": rules.DEFENSIVE_UP_MIN,
        "defensiveDownBelow": rules.DEFENSIVE_DOWN_BELOW,
        "targetUpAbove": rules.TARGET_UP_ABOVE,
        "targetDownMax": rules.TARGET_DOWN_MAX,
    }
    for name, value in decimals.items():
        assert Decimal(h[name]) == value, name
    assert (h["benchKr"], h["benchUs"]) == (rules.BENCH_KR, rules.BENCH_US)
    assert h["nudgeSources"] == sorted(rules.NUDGE_SOURCES)
    assert h["platformSources"] == sorted(rules.PLATFORM_SOURCES)
    assert (h["dropPercent"], h["risePercent"]) == (rules.DROP_PERCENT, rules.RISE_PERCENT)
    assert h["notStartedBelowTurn"] == rules.NOT_STARTED_BELOW_TURN


def test_the_fixture_holds_the_planned_twenty_seasons() -> None:
    assert sorted(SEASONS) == INDEXES
    lengths = [SEASONS[i]["inputs"]["settings"]["pathLength"] for i in INDEXES]
    assert sorted(lengths) == [12] * 10 + [24] * 8 + [60] * 2
    cash = [Decimal(SEASONS[i]["inputs"]["settings"]["initialCash"]) for i in INDEXES]
    assert sorted(cash) == [Decimal(30_000_000)] * 17 + [Decimal(100_000_000)] * 3
    abandoned = {
        i: SEASONS[i]["java"]["abandonedTurn"]
        for i in INDEXES
        if SEASONS[i]["java"]["abandonedTurn"] is not None
    }
    assert len(abandoned) == 2 and min(abandoned.values()) < rules.NOT_STARTED_BELOW_TURN <= max(
        abandoned.values()
    )
    exclusions = {SEASONS[i]["java"]["score"]["ratingExclusion"] for i in INDEXES}
    assert "NOT_STARTED" in exclusions and None in exclusions


def test_the_pool_is_listed_in_asset_id_order_with_a_us_asset_before_a_kr_one() -> None:
    pool = [a["asset"] for a in SEASONS[1]["inputs"]["pool"]]
    assert pool != sorted(pool)
    assert pool.index("US:SPY") < pool.index("KR:069500")
    for i in INDEXES:
        assert [a["asset"] for a in SEASONS[i]["inputs"]["pool"]] == pool
        weights = [w["asset"] for w in SEASONS[i]["java"]["percentileRequest"]["team"]["weights"]]
        assert weights == [a for a in pool if a in weights]


def test_every_kind_of_unit_and_refusal_is_in_the_fixture() -> None:
    expects = {
        u["expect"]
        for i in INDEXES
        for t in SEASONS[i]["decisions"]["turns"]
        for u in t.get("units", [])
    }
    assert expects >= {
        "ACCEPTED",
        "INSUFFICIENT_CASH",
        "INSUFFICIENT_HOLDING",
        "POSITION_REQUIRED",
        "POSITION_CONFLICT",
        "QUANTITY_INVALID",
        "ASSET_NOT_IN_POOL",
        "SOURCE_NOT_ALLOWED",
        "FORMATION_INVALID",
        "FORMATION_UNCHANGED",
        "TURN_ZERO_FORMATION",
        "TURN_MISMATCH",
    }
    kinds = {x["source"] for i in INDEXES for x in SEASONS[i]["java"]["executions"]}
    assert kinds == {"DIRECT", "FEED", "ALERT", "RANKING"}
    assert any(SEASONS[i]["java"]["formationChanges"] for i in INDEXES)
    assert any(
        x["formationChange"] is not None for i in INDEXES for x in SEASONS[i]["java"]["executions"]
    )
    unit_kinds = {
        u["kind"]
        for i in INDEXES
        for t in SEASONS[i]["decisions"]["turns"]
        for u in t.get("units", [])
    }
    assert unit_kinds == {"ORDER", "FORMATION", "LINEUP"}


def test_an_asset_that_never_falls_is_held_so_the_system_position_fallback_is_compared() -> None:
    covered = []
    for i in INDEXES:
        entry = SEASONS[i]
        bought = {e["asset"] for e in entry["java"]["executions"]}
        for asset in bought:
            downs = sum(1 for s in entry["inputs"]["path"] if Decimal(s["localReturns"][asset]) < 0)
            if downs < rules.SEASON_MIN_DOWN:
                covered.append(i)
    assert covered


def test_the_recorded_counts_are_replayed_as_fixed_counts() -> None:
    for i in INDEXES:
        c = SEASONS[i]["counts"]
        assert c["n"] == DOC["header"]["pathCount"] == SEASONS[i]["java"]["percentileRequest"]["n"]


def test_adapter_order_units_pair_with_the_reply_by_reply_index() -> None:
    """A turn of fixture orders becomes a `turn-decision/1` reply; each unit finds its entry."""
    paired = 0
    for i in INDEXES:
        for plan in SEASONS[i]["decisions"]["turns"]:
            orders = [
                u
                for u in plan.get("units", [])
                if u["kind"] == "ORDER" and "source" not in u and u["expect"] == "ACCEPTED"
            ]
            if {o["side"] for o in orders} != {"BUY", "SELL"}:
                continue
            reply = {
                "schema": "turn-decision/1",
                "formation_change": None,
                "orders": [
                    {
                        "asset": o["asset"],
                        "side": o["side"],
                        "quantity": o["quantity"],
                        "source": {"kind": "DIRECT"},
                        **({"position": o["position"]} if "position" in o else {}),
                    }
                    for o in orders
                ],
                "theses": [],
                "reason": "parity pairing",
            }
            units = [u for u in from_turn_decision(reply, plan["turn"]) if isinstance(u, OrderUnit)]
            assert sorted(u.reply_index for u in units) == list(range(len(orders)))
            for unit in units:
                entry = orders[unit.reply_index]
                assert (unit.order.asset, unit.order.side.value, unit.order.quantity) == (
                    entry["asset"],
                    entry["side"],
                    entry["quantity"],
                )
            sides = [u.order.side.value for u in units]
            assert sides == sorted(sides, key=lambda side: side != "SELL")
            paired += 1
    assert paired


def test_the_inputs_reader_refuses_a_missing_key_and_accepts_a_null_system_position() -> None:
    doc = copy.deepcopy(SEASONS[1]["inputs"])
    del doc["pool"][0]["systemPosition"]
    with pytest.raises(SimShapeError):
        inputs_from_wire(doc)
    doc = copy.deepcopy(SEASONS[1]["inputs"])
    doc["pool"][0]["systemPosition"] = None
    assert inputs_from_wire(doc).pool[0].system_position is None
    doc = copy.deepcopy(SEASONS[1]["inputs"])
    doc["extra"] = 1
    with pytest.raises(SimShapeError):
        inputs_from_wire(doc)
    doc = copy.deepcopy(SEASONS[1]["inputs"])
    doc["settings"]["initialCash"] = "30000001.0000"
    with pytest.raises(SimShapeError):
        inputs_from_wire(doc)


def test_turn_zero_sells_are_in_the_fixture_and_leave_the_start_team() -> None:
    partial = full = 0
    for i in INDEXES:
        entry = SEASONS[i]
        sells = [e for e in entry["java"]["executions"] if e["turn"] == 0 and e["side"] == "SELL"]
        for sell in sells:
            bought = sum(
                e["quantity"]
                for e in entry["java"]["executions"]
                if e["turn"] == 0 and e["asset"] == sell["asset"] and e["side"] == "BUY"
            )
            sold = sum(e["quantity"] for e in sells if e["asset"] == sell["asset"])
            if sold == bought:
                full += 1
                weights = entry["java"]["percentileRequest"]["team"]["weights"]
                assert sell["asset"] not in [w["asset"] for w in weights]
            else:
                partial += 1
    assert partial and full

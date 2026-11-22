"""`bot-world/1`: the season inputs of the simulator plus the seed and the optional feed facts.

`base_date` is internal (it only seeds the rank-count paths) and never reaches a bot view or a
bot-run record. `feed` is carried as-is for the feed port (PR A); the runner does not read it.
"""

import hashlib
import json
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Any

from antlab_engine.sim.model import (
    Formation,
    PathStep,
    PoolAsset,
    Position,
    Settings,
    SimInputs,
    SimShapeError,
)

SCHEMA = "bot-world/1"
SETTINGS_KEYS = {
    "initialCash",
    "feeRateKr",
    "feeRateUs",
    "fxSpreadRate",
    "riskLimit",
    "startFormation",
    "pathLength",
    "memberKind",
    "singleAssetLimit",
}
_SYSTEM = (
    Position.DEFENDER,
    Position.MIDFIELDER,
    Position.FORWARD_TARGET,
    Position.GOALKEEPER,
)


@dataclass(frozen=True)
class BotWorld:
    seed: int
    base_date: date
    inputs: SimInputs
    feed: Mapping[str, Any] | None = None

    @property
    def world_id(self) -> str:
        return hashlib.sha256(canonical(self._body()).encode()).hexdigest()

    def _body(self) -> dict[str, Any]:
        body: dict[str, Any] = {
            "schema": SCHEMA,
            "seed": str(self.seed),
            "baseDate": self.base_date.isoformat(),
            "inputs": inputs_to_doc(self.inputs),
        }
        if self.feed is not None:
            body["feed"] = self.feed
        return body

    def to_doc(self) -> dict[str, Any]:
        return {**self._body(), "worldId": self.world_id}


def canonical(doc: Any) -> str:
    return json.dumps(doc, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _plain(value: Decimal) -> str:
    return format(value, "f")


def inputs_to_doc(inputs: SimInputs) -> dict[str, Any]:
    s = inputs.settings
    f = s.start_formation
    return {
        "settings": {
            "initialCash": _plain(s.initial_cash),
            "feeRateKr": _plain(s.fee_rate_kr),
            "feeRateUs": _plain(s.fee_rate_us),
            "fxSpreadRate": _plain(s.fx_spread_rate),
            "riskLimit": _plain(s.risk_limit),
            "startFormation": {
                "goalkeeper": f.goalkeeper,
                "defender": f.defender,
                "midfielder": f.midfielder,
                "forward": f.forward,
            },
            "pathLength": s.path_length,
            "memberKind": s.member_kind,
            "singleAssetLimit": None
            if s.single_asset_limit is None
            else _plain(s.single_asset_limit),
        },
        "pool": [
            {
                "asset": a.asset,
                "currency": a.currency,
                "basePrice": _plain(a.base_price),
                "baseFxRate": _plain(a.base_fx_rate),
                "systemPosition": None if a.system_position is None else a.system_position.value,
            }
            for a in inputs.pool
        ],
        "path": [
            {
                "turn": step.turn,
                "localReturns": {k: _plain(v) for k, v in step.local_returns.items()},
                "fxChange": {k: _plain(v) for k, v in step.fx_change.items()},
            }
            for step in inputs.path
        ],
    }


def _need(body: Any, where: str, keys: set[str]) -> None:
    if not isinstance(body, Mapping) or set(body) != keys:
        raise SimShapeError(f"{where}: keys must be exactly {sorted(keys)}")


def inputs_from_doc(doc: Mapping[str, Any]) -> SimInputs:
    _need(doc, "inputs", {"settings", "pool", "path"})
    s = doc["settings"]
    _need(s, "settings", SETTINGS_KEYS)
    f = s["startFormation"]
    _need(f, "startFormation", {"goalkeeper", "defender", "midfielder", "forward"})
    limit = s["singleAssetLimit"]
    settings = Settings(
        initial_cash=Decimal(s["initialCash"]),
        fee_rate_kr=Decimal(s["feeRateKr"]),
        fee_rate_us=Decimal(s["feeRateUs"]),
        fx_spread_rate=Decimal(s["fxSpreadRate"]),
        risk_limit=Decimal(s["riskLimit"]),
        start_formation=Formation(f["goalkeeper"], f["defender"], f["midfielder"], f["forward"]),
        path_length=s["pathLength"],
        member_kind=s["memberKind"],
        single_asset_limit=None if limit is None else Decimal(limit),
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


def world_from_doc(doc: Mapping[str, Any]) -> BotWorld:
    keys = {"schema", "worldId", "seed", "baseDate", "inputs"}
    if isinstance(doc, Mapping) and "feed" in doc:
        keys = keys | {"feed"}
    _need(doc, "world", keys)
    if doc["schema"] != SCHEMA:
        raise SimShapeError(f"world: schema must be {SCHEMA}")
    world = BotWorld(
        int(doc["seed"]),
        date.fromisoformat(doc["baseDate"]),
        inputs_from_doc(doc["inputs"]),
        doc.get("feed"),
    )
    if world.world_id != doc["worldId"]:
        raise SimShapeError("world: worldId does not match the body")
    return world


def synthetic_world(seed: int, assets: int = 12, length: int = 12) -> BotWorld:
    """A reproducible world for tests and throughput runs; includes the two benchmark proxies."""
    from .rng import BotRng, mix

    rng = BotRng(mix(seed, 0x57_0F_1D))
    pool = [
        PoolAsset("KR:069500", "KRW", Decimal("30000"), Decimal(1), Position.MIDFIELDER),
        PoolAsset("US:SPY", "USD", Decimal(50), Decimal(1400), Position.FORWARD_TARGET),
    ]
    for i in range(max(assets - 2, 0)):
        position = _SYSTEM[rng.below(len(_SYSTEM))]
        if i % 2 == 0:
            pool.append(
                PoolAsset(
                    f"KR:S{i:03d}",
                    "KRW",
                    Decimal(5000 + 100 * rng.below(300)),
                    Decimal(1),
                    position,
                )
            )
        else:
            pool.append(
                PoolAsset(
                    f"US:S{i:03d}", "USD", Decimal(10 + rng.below(190)), Decimal(1400), position
                )
            )
    path = []
    for turn in range(1, length + 1):
        returns = {a.asset: Decimal(rng.between(-1000, 1000)) / 10000 for a in pool}
        fx = {"USD": Decimal(rng.between(-100, 100)) / 10000}
        path.append(PathStep(turn, returns, fx))
    inputs = SimInputs(Settings(path_length=length), tuple(pool), tuple(path))
    return BotWorld(seed, date(2010, 1, 1), inputs)

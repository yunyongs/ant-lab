"""Fake season worlds and manager-parts requests built from bot records (no database)."""

from copy import deepcopy
from datetime import date
from decimal import Decimal

from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import BotWorld, synthetic_world
from antlab_engine.leaderboard.parts import TeamSeries
from antlab_engine.leaderboard.reference import (
    ReferenceService,
    SeasonIdentity,
    world_seed,
)
from antlab_engine.sim.model import Formation, Settings, SimInputs

BASE_DATE = "2026-01-02"
TEST_SIZE = 20
SETTINGS_DOC = {
    "pathLength": 12,
    "initialCash": "100000000",
    "feeRateKr": "0.00015000",
    "feeRateUs": "0.00250000",
    "fxSpreadRate": "0.00400000",
    "riskLimit": "-0.20000000",
    "startFormation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
    "memberKind": "HUMAN",
}


def months_of(length: int, start_year: int = 2009) -> list[str]:
    return [f"{start_year + i // 12}-{i % 12 + 1:02d}-01" for i in range(length)]


class FakeWorldReader:
    """The same synthetic pool and path for every identity, cut to the path length."""

    def __init__(self, seed: int = 5, assets: int = 8, length: int = 60) -> None:
        self.base = synthetic_world(seed, assets=assets, length=length)
        self.calls = 0

    def read(self, identity: SeasonIdentity, seed: int) -> BotWorld:
        self.calls += 1
        turns = identity.settings.path_length
        inputs = SimInputs(identity.settings, self.base.inputs.pool, self.base.inputs.path[:turns])
        return BotWorld(seed, self.base.base_date, inputs)


def sim_settings(doc: dict) -> Settings:
    f = doc["startFormation"]
    return Settings(
        initial_cash=Decimal(doc["initialCash"]),
        fee_rate_kr=Decimal(doc["feeRateKr"]),
        fee_rate_us=Decimal(doc["feeRateUs"]),
        fx_spread_rate=Decimal(doc["fxSpreadRate"]),
        risk_limit=Decimal(doc["riskLimit"]),
        start_formation=Formation(f["goalkeeper"], f["defender"], f["midfielder"], f["forward"]),
        path_length=doc["pathLength"],
        member_kind=doc["memberKind"],
    )


def identity_of(settings_doc: dict | None = None, months: list[str] | None = None):
    doc = settings_doc or SETTINGS_DOC
    chosen = months or months_of(doc["pathLength"])
    return SeasonIdentity(
        "ref69-v1",
        date.fromisoformat(BASE_DATE),
        tuple(date.fromisoformat(m) for m in chosen),
        sim_settings(doc),
    )


def team_record(
    service: ReferenceService,
    reader: FakeWorldReader,
    identity: SeasonIdentity,
    kind: str = "disciplined",
    index: int = 0,
) -> dict:
    """A bot that played the reference world itself (same world id, so M3-03 `s_parts` applies)."""
    world = reader.read(identity, world_seed(service.key(identity)))
    return dict(run_bot(world, make_bot(kind, index, 1, world.seed)).record)


def team_doc(record: dict) -> dict:
    score = record["score"]
    return {
        "totals": list(record["totals"][1:]),  # turns 1..T (Java stores no turn 0 row)
        "returns": list(record["teamReturns"]),
        "benchmarkReturns": list(record["benchmarkReturns"]),
        "sharpe": score["sharpe"],
        "benchSharpe": score["benchSharpe"],
        "riskScore": score["riskScore"],
    }


def request_doc(record: dict, settings_doc: dict | None = None, months=None) -> dict:
    doc = deepcopy(settings_doc or SETTINGS_DOC)
    return {
        "referenceVersion": "ref69-v1",
        "baseDate": BASE_DATE,
        "months": months or months_of(doc["pathLength"]),
        "settings": doc,
        "team": team_doc(record),
    }


def team_series(record: dict) -> TeamSeries:
    doc = team_doc(record)
    return TeamSeries(
        Decimal(record["report"]["initialCash"]),
        tuple(Decimal(v) for v in doc["totals"]),
        tuple(Decimal(v) for v in doc["returns"]),
        tuple(Decimal(v) for v in doc["benchmarkReturns"]),
        Decimal(doc["sharpe"]),
        Decimal(doc["benchSharpe"]),
        None if doc["riskScore"] is None else Decimal(doc["riskScore"]),
    )

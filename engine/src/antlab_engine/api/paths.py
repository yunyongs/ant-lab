"""POST /v1/paths: one path of months for a seed, drawn from the history reader's snapshot.

Months and blocks are replay-only data. They are returned on this internal route only and never
logged. Only `snapshot.months[*].month_start` is used; the pool, returns and FX are not read here.
"""

import hashlib
from datetime import date
from decimal import Decimal
from fractions import Fraction
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field, field_validator

from antlab_engine.bootstrap import simple, stationary
from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.errors import Problem
from antlab_engine.history.model import HistoryReader

from . import PROBLEM_RESPONSES
from .deps import history_reader

MONTH_PATTERN = r"^[0-9]{4}-(0[1-9]|1[0-2])$"
SEED_PATTERN = r"^-?[0-9]{1,19}$"
OVERLAP_PATTERN = r"^(0(\.[0-9]{1,4})?|1(\.0{1,4})?)$"
DIGEST_PATTERN = r"^[0-9a-f]{64}$"
MAX_RECENT_MONTHS = 4000

router = APIRouter()


class PathRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    engineVersion: Literal["p0-boot6-v1", "m2-sb6-v1"]
    seed: Annotated[str, Field(pattern=SEED_PATTERN)]
    length: Literal[12, 24, 60, 120]
    baseDate: date
    conditional: bool = False
    recentMonths: list[Annotated[str, Field(pattern=MONTH_PATTERN)]] = Field(
        default_factory=list,
        max_length=MAX_RECENT_MONTHS,
        json_schema_extra={"uniqueItems": True},
    )
    maxOverlap: Annotated[str, Field(pattern=OVERLAP_PATTERN)] = "1"

    @field_validator("recentMonths")
    @classmethod
    def _unique(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("recentMonths must not repeat a month")
        return value


class PathBlock(BaseModel):
    startTurn: int
    drawnLength: int
    length: int
    tier: Literal["ALIGNED", "NEAR", "FREE", "JOIN"]


class PathResult(BaseModel):
    engineVersion: str
    months: list[Annotated[str, Field(pattern=MONTH_PATTERN)]]
    blocks: list[PathBlock]
    overlapMonths: int
    overlapMet: bool
    attempt: int
    historyDigest: Annotated[str, Field(pattern=DIGEST_PATTERN)]


def _month(value: str) -> date:
    return date(int(value[:4]), int(value[5:7]), 1)


def history_digest(engine_version: str, months: list[date], base_date: date) -> str:
    """SHA-256 over the engine version and the sorted replayable months of the snapshot."""
    upper = simple.replay_upper_bound(base_date)
    replayable = sorted({m for m in months if simple.REPLAY_START <= m < upper})
    text = "\n".join([engine_version, *(f"{m:%Y-%m}" for m in replayable)])
    return hashlib.sha256(text.encode("ascii")).hexdigest()


def _check(request: PathRequest) -> tuple[int, Fraction]:
    seed = int(request.seed)
    if not stationary.SEED_MIN <= seed <= stationary.SEED_MAX:
        raise InvalidArgument("seed must fit in a signed 64-bit integer")
    if request.conditional:
        raise InvalidArgument("conditional paths are not available yet")
    threshold = Fraction(Decimal(request.maxOverlap))
    if request.engineVersion == simple.ENGINE_VERSION and (request.recentMonths or threshold != 1):
        raise InvalidArgument(f"{simple.ENGINE_VERSION} does not support recent-season overlap")
    return seed, threshold


def _simple_path(months: list[date], request: PathRequest, seed: int, digest: str) -> PathResult:
    path = simple.generate_months(months, request.baseDate, request.length, seed)
    size = simple.BLOCK_LENGTH
    return PathResult(
        engineVersion=simple.ENGINE_VERSION,
        months=[f"{m:%Y-%m}" for m in path],
        blocks=[
            PathBlock(startTurn=turn, drawnLength=size, length=size, tier="ALIGNED")
            for turn in range(0, request.length, size)
        ],
        overlapMonths=0,
        overlapMet=True,
        attempt=0,
        historyDigest=digest,
    )


def _stationary_path(
    months: list[date], request: PathRequest, seed: int, threshold: Fraction, digest: str
) -> PathResult:
    path = stationary.generate(
        months,
        request.baseDate,
        request.length,
        seed,
        recent_months=[_month(m) for m in request.recentMonths],
        max_overlap=threshold,
    )
    return PathResult(
        engineVersion=path.engine_version,
        months=[f"{m:%Y-%m}" for m in path.months],
        blocks=[
            PathBlock(
                startTurn=b.start_turn,
                drawnLength=b.drawn_length,
                length=b.length,
                tier=b.tier.value,
            )
            for b in path.blocks
        ],
        overlapMonths=path.overlap_months,
        overlapMet=path.overlap_met,
        attempt=path.attempt,
        historyDigest=digest,
    )


@router.post(
    "/v1/paths",
    response_model=PathResult,
    operation_id="createPath",
    responses={
        **PROBLEM_RESPONSES,
        400: {"model": Problem, "description": "Invalid argument"},
        404: {"model": Problem, "description": "No pool for the base date"},
        422: {"model": Problem, "description": "The history cannot build this path"},
        503: {"model": Problem, "description": "History unavailable"},
    },
)
def create_path(
    request: PathRequest, reader: Annotated[HistoryReader, Depends(history_reader)]
) -> PathResult:
    seed, threshold = _check(request)
    snapshot = reader.read(request.baseDate)
    months = [m.month_start for m in snapshot.months]
    digest = history_digest(request.engineVersion, months, request.baseDate)
    if request.engineVersion == simple.ENGINE_VERSION:
        return _simple_path(months, request, seed, digest)
    return _stationary_path(months, request, seed, threshold, digest)

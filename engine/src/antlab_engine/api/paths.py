"""POST /v1/paths: one path of months for a seed, drawn from the history reader's snapshot.

Months and blocks are replay-only data. They are returned on this internal route only and never
logged. Only `snapshot.months` (start and stored regime label) is used; the pool, returns and FX
are not read here. Regime labels feed `m2-sbc6-v1` only and never reach the response.
"""

import hashlib
from collections.abc import Mapping
from datetime import date
from decimal import Decimal
from fractions import Fraction
from typing import Annotated, Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator

from antlab_engine.bootstrap import simple, stationary
from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.errors import Problem

from . import PROBLEM_RESPONSES
from .deps import history_reader, replay_start

MONTH_PATTERN = r"^[0-9]{4}-(0[1-9]|1[0-2])$"
SEED_PATTERN = r"^-?[0-9]{1,19}$"
OVERLAP_PATTERN = r"^(0(\.[0-9]{1,4})?|1(\.0{1,4})?)$"
DIGEST_PATTERN = r"^[0-9a-f]{64}$"
MAX_RECENT_MONTHS = 4000

router = APIRouter()


class PathRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    engineVersion: Literal["p0-boot6-v1", "m2-sb6-v1", "m2-sbc6-v1"]
    seed: Annotated[str, Field(pattern=SEED_PATTERN)]
    length: Literal[12, 24, 60, 120]
    baseDate: Annotated[date, Field(strict=False)]  # an ISO string; see _iso_date
    conditional: bool = False
    recentMonths: list[Annotated[str, Field(pattern=MONTH_PATTERN)]] = Field(
        default_factory=list,
        max_length=MAX_RECENT_MONTHS,
        json_schema_extra={"uniqueItems": True},
    )
    maxOverlap: Annotated[str, Field(pattern=OVERLAP_PATTERN)] = "1"

    @field_validator("baseDate", mode="before")
    @classmethod
    def _iso_date(cls, value: object) -> object:
        if not isinstance(value, str):
            raise ValueError("baseDate must be an ISO date string")
        return value

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
    conditioned: bool
    matched: bool


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


def history_digest(
    engine_version: str,
    months: list[date],
    base_date: date,
    replay_start: date = simple.REPLAY_START,
    labels: Mapping[date, str] | None = None,
) -> str:
    """SHA-256 over the engine version and the sorted replayable months of the snapshot.

    With `labels` (`m2-sbc6-v1`) every line is `YYYY-MM:P3`, or `YYYY-MM:-` for an unlabelled month;
    labels of months outside the replay range are not part of it.
    """
    upper = simple.replay_upper_bound(base_date)
    replayable = sorted({m for m in months if replay_start <= m < upper})
    if labels is None:
        lines = [f"{m:%Y-%m}" for m in replayable]
    else:
        lines = [f"{m:%Y-%m}:{labels.get(m, '-')}" for m in replayable]
    text = "\n".join([engine_version, *lines])
    return hashlib.sha256(text.encode("ascii")).hexdigest()


def _check(request: PathRequest) -> tuple[int, Fraction]:
    seed = int(request.seed)
    if not stationary.SEED_MIN <= seed <= stationary.SEED_MAX:
        raise InvalidArgument("seed must fit in a signed 64-bit integer")
    if request.conditional != (request.engineVersion == stationary.CONDITIONAL_VERSION):
        raise InvalidArgument(
            f"conditional must be true exactly for {stationary.CONDITIONAL_VERSION}"
        )
    threshold = Fraction(Decimal(request.maxOverlap))
    if request.engineVersion == simple.ENGINE_VERSION and (request.recentMonths or threshold != 1):
        raise InvalidArgument(f"{simple.ENGINE_VERSION} does not support recent-season overlap")
    return seed, threshold


def _simple_path(
    months: list[date], request: PathRequest, seed: int, digest: str, replay_start: date
) -> PathResult:
    path = simple.generate_months(
        months, request.baseDate, request.length, seed, replay_start=replay_start
    )
    size = simple.BLOCK_LENGTH
    return PathResult(
        engineVersion=simple.ENGINE_VERSION,
        months=[f"{m:%Y-%m}" for m in path],
        blocks=[
            PathBlock(
                startTurn=turn,
                drawnLength=size,
                length=size,
                tier="ALIGNED",
                conditioned=False,
                matched=False,
            )
            for turn in range(0, request.length, size)
        ],
        overlapMonths=0,
        overlapMet=True,
        attempt=0,
        historyDigest=digest,
    )


def _stationary_path(
    months: list[date],
    request: PathRequest,
    seed: int,
    threshold: Fraction,
    replay_start: date,
    labels: dict[date, str] | None,
) -> PathResult:
    path = stationary.generate(
        months,
        request.baseDate,
        request.length,
        seed,
        recent_months=[_month(m) for m in request.recentMonths],
        max_overlap=threshold,
        replay_start=replay_start,
        labels=labels,
    )
    # the version in the answer is the one that was asked for, so a stored version never lies
    if path.engine_version != request.engineVersion:
        raise RuntimeError("the generator answered another engine version")
    digest = history_digest(request.engineVersion, months, request.baseDate, replay_start, labels)
    return PathResult(
        engineVersion=path.engine_version,
        months=[f"{m:%Y-%m}" for m in path.months],
        blocks=[
            PathBlock(
                startTurn=b.start_turn,
                drawnLength=b.drawn_length,
                length=b.length,
                tier=b.tier.value,
                conditioned=b.target is not None,
                matched=b.matched,
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
def create_path(request: PathRequest, http_request: Request) -> PathResult:
    seed, threshold = _check(request)
    # resolved after the checks: a bad request is 400 even without a configured database
    reader = history_reader(http_request)
    snapshot = reader.read(request.baseDate)
    months = [m.month_start for m in snapshot.months]
    start = replay_start(http_request)
    if request.engineVersion == simple.ENGINE_VERSION:
        digest = history_digest(request.engineVersion, months, request.baseDate, start)
        return _simple_path(months, request, seed, digest, start)
    # a dict for the conditional version even when empty: `labels or None` would drop the rule
    labels = (
        {m.month_start: m.regime_label for m in snapshot.months if m.regime_label is not None}
        if request.conditional
        else None
    )
    return _stationary_path(months, request, seed, threshold, start, labels)

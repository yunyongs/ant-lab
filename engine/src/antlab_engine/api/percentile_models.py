"""Wire models of `POST /v1/percentiles`: decimal strings in, integer counts out."""

from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .projection_models import (
    DIGEST_PATTERN,
    MAX_HOLDINGS,
    SEED_PATTERN,
    Holding,
    Share,
)

END_MULTIPLE_PATTERN = r"^[0-9]{1,12}(\.[0-9]{1,12})?$"
SHARPE_DIFF_PATTERN = r"^-?[0-9]{1,6}(\.[0-9]{1,12})?$"
MAX_PATHS = 5000


class PercentilePortfolio(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    cash: Share
    weights: list[Holding] = Field(max_length=MAX_HOLDINGS)

    @field_validator("weights")
    @classmethod
    def _unique_assets(cls, value: list[Holding]) -> list[Holding]:
        if len({h.asset for h in value}) != len(value):
            raise ValueError("an asset must not appear twice in one portfolio")
        return value


class PercentileObserved(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    endMultiple: Annotated[str, Field(pattern=END_MULTIPLE_PATTERN)]
    sharpeDiff: Annotated[str, Field(pattern=SHARPE_DIFF_PATTERN)]


class PercentileRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    seasonSeed: Annotated[str, Field(pattern=SEED_PATTERN)]
    baseDate: Annotated[date, Field(strict=False)]  # an ISO string; see _iso_date
    horizonTurns: Literal[12, 24, 60, 120]
    n: int = Field(default=2000, ge=100, le=MAX_PATHS)
    team: PercentilePortfolio
    benchmark: PercentilePortfolio
    observed: PercentileObserved

    @field_validator("horizonTurns", mode="before")
    @classmethod
    def _whole_horizon(cls, value: object) -> object:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("horizonTurns must be an integer")
        return value

    @field_validator("baseDate", mode="before")
    @classmethod
    def _iso_date(cls, value: object) -> object:
        if not isinstance(value, str) or len(value) != 10:
            raise ValueError("baseDate must be an ISO date string")
        return value


class RankCounts(BaseModel):
    below: Annotated[int, Field(ge=0, le=MAX_PATHS)]
    equal: Annotated[int, Field(ge=0, le=MAX_PATHS)]


class PercentileResult(BaseModel):
    projectionVersion: str
    engineVersion: str
    n: int
    horizonTurns: int
    historyDigest: Annotated[str, Field(pattern=DIGEST_PATTERN)]
    endMultiple: RankCounts
    sharpeDiff: RankCounts

    @model_validator(mode="after")
    def _counts_fit_in_n(self) -> "PercentileResult":
        for counts in (self.endMultiple, self.sharpeDiff):
            if counts.below + counts.equal > self.n:
                raise ValueError("below + equal must not exceed n")
        return self

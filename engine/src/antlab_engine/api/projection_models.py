"""Wire models of `POST /v1/projections`. Decimals travel as strings, never as floats."""

from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

SEED_PATTERN = r"^-?[0-9]{1,19}$"
DIGEST_PATTERN = r"^[0-9a-f]{64}$"
ASSET_PATTERN = r"^(KR|US):[A-Z0-9.\-]{1,16}$"
KEY_PATTERN = r"^[A-Z][A-Z0-9_]{0,31}$"
SHARE_PATTERN = r"^(0(\.[0-9]{1,12})?|1(\.0{1,12})?)$"
LIMIT_PATTERN = r"^-0\.[0-9]{1,8}$"
MULTIPLE_PATTERN = r"^[0-9]{1,24}\.[0-9]{8}$"
DRAWDOWN_PATTERN = r"^(0\.0{8}|-(0\.[0-9]{8}|1\.0{8}))$"
PROBABILITY_PATTERN = r"^(0\.[0-9]{8}|1\.0{8})$"
MAX_HOLDINGS = 200
MAX_PORTFOLIOS = 6
MAX_SUMMARY_TURNS = 20

Share = Annotated[str, Field(pattern=SHARE_PATTERN)]
Multiple = Annotated[str, Field(pattern=MULTIPLE_PATTERN)]
Drawdown = Annotated[str, Field(pattern=DRAWDOWN_PATTERN)]
Probability = Annotated[str, Field(pattern=PROBABILITY_PATTERN)]


class Holding(BaseModel):
    model_config = ConfigDict(extra="forbid")

    asset: Annotated[str, Field(pattern=ASSET_PATTERN)]
    weight: Share


class ProjectionPortfolio(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key: Annotated[str, Field(pattern=KEY_PATTERN)]
    cash: Share
    weights: list[Holding] = Field(max_length=MAX_HOLDINGS)

    @field_validator("weights")
    @classmethod
    def _unique_assets(cls, value: list[Holding]) -> list[Holding]:
        if len({h.asset for h in value}) != len(value):
            raise ValueError("an asset must not appear twice in one portfolio")
        return value


class ProjectionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    seasonSeed: Annotated[str, Field(pattern=SEED_PATTERN)]
    baseDate: date
    horizonTurns: Literal[60, 120]
    n: int = Field(default=2000, ge=100, le=5000)
    riskLimit: Annotated[str, Field(pattern=LIMIT_PATTERN)]
    summaryTurns: list[Annotated[int, Field(ge=1, le=120)]] = Field(
        default_factory=lambda: [60, 120], min_length=1, max_length=MAX_SUMMARY_TURNS
    )
    portfolios: list[ProjectionPortfolio] = Field(min_length=1, max_length=MAX_PORTFOLIOS)

    @model_validator(mode="after")
    def _unique_keys(self) -> "ProjectionRequest":
        if len({p.key for p in self.portfolios}) != len(self.portfolios):
            raise ValueError("portfolio keys must be unique")
        return self


class ProjectionBand(BaseModel):
    turn: int
    p05: Multiple
    p25: Multiple
    p50: Multiple
    p75: Multiple
    p95: Multiple


class EndQuantiles(BaseModel):
    p05: Multiple
    p25: Multiple
    p50: Multiple
    p75: Multiple
    p95: Multiple


class MddQuantiles(BaseModel):
    p05: Drawdown
    p10: Drawdown
    p25: Drawdown
    p50: Drawdown
    p75: Drawdown
    p95: Drawdown


class ProjectionSummary(BaseModel):
    turn: int
    end: EndQuantiles
    mdd: MddQuantiles
    breachCount: int
    breachProbability: Probability


class ProjectionPortfolioResult(BaseModel):
    key: str
    bands: list[ProjectionBand]
    summaries: list[ProjectionSummary]


class ProjectionResult(BaseModel):
    projectionVersion: str
    engineVersion: str
    n: int
    horizonTurns: int
    historyDigest: Annotated[str, Field(pattern=DIGEST_PATTERN)]
    portfolios: list[ProjectionPortfolioResult]

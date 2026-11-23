"""Wire models of `POST /v1/manager-parts`: decimal strings in and out, no dates in the answer."""

from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from antlab_engine.leaderboard import REFERENCE_SIZE, REFERENCE_VERSION

from .projection_models import DIGEST_PATTERN, LIMIT_PATTERN

MONTH_START_PATTERN = r"^[0-9]{4}-(0[1-9]|1[0-2])-01$"
WON_PATTERN = r"^[0-9]{1,15}(\.0{1,4})?$"
RATE_PATTERN = r"^0(\.[0-9]{1,8})?$"
RETURN_PATTERN = r"^-?[0-9]{1,6}(\.[0-9]{1,30})?$"
SHARPE_PATTERN = r"^-?[0-9]{1,6}\.[0-9]{8}$"
RISK_SCORE_PATTERN = r"^(100\.00|[0-9]{1,2}\.[0-9]{2})$"
COMPONENT_PATTERN = r"^-?[0-9]{1,8}\.[0-9]{12}$"
PERCENTILE_PATTERN = r"^(100\.0{8}|[0-9]{1,2}\.[0-9]{8})$"
BOT_VERSION_PATTERN = r"^[a-z]+/[0-9]{1,4}$"
MIN_TURNS, MAX_TURNS = 12, 60

Won = Annotated[str, Field(pattern=WON_PATTERN)]
Rate = Annotated[str, Field(pattern=RATE_PATTERN)]
Return = Annotated[str, Field(pattern=RETURN_PATTERN)]
Sharpe = Annotated[str, Field(pattern=SHARPE_PATTERN)]
Component = Annotated[str, Field(pattern=COMPONENT_PATTERN)]
Percentile = Annotated[str, Field(pattern=PERCENTILE_PATTERN)]
Share = Annotated[int, Field(ge=0, le=100)]


class StartFormation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    goalkeeper: Annotated[int, Field(ge=1, le=100)]
    defender: Share
    midfielder: Share
    forward: Share


class PartsSettings(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    pathLength: Literal[12, 24, 60]
    initialCash: Won
    feeRateKr: Rate
    feeRateUs: Rate
    fxSpreadRate: Rate
    riskLimit: Annotated[str, Field(pattern=LIMIT_PATTERN)]
    startFormation: StartFormation
    memberKind: Literal["HUMAN", "AGENT"]

    @field_validator("pathLength", mode="before")
    @classmethod
    def _whole_length(cls, value: object) -> object:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("pathLength must be an integer")
        return value


class PartsTeam(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    totals: list[Won] = Field(min_length=MIN_TURNS, max_length=MAX_TURNS)
    returns: list[Return] = Field(min_length=MIN_TURNS, max_length=MAX_TURNS)
    benchmarkReturns: list[Return] = Field(min_length=MIN_TURNS, max_length=MAX_TURNS)
    sharpe: Sharpe
    benchSharpe: Sharpe
    riskScore: Annotated[str, Field(pattern=RISK_SCORE_PATTERN)] | None


class ManagerPartsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    referenceVersion: Literal[REFERENCE_VERSION]  # type: ignore[valid-type]
    baseDate: Annotated[date, Field(strict=False)]  # an ISO string; see _iso_date
    months: list[Annotated[str, Field(pattern=MONTH_START_PATTERN)]] = Field(
        min_length=MIN_TURNS, max_length=MAX_TURNS, json_schema_extra={"uniqueItems": True}
    )
    settings: PartsSettings
    team: PartsTeam

    @field_validator("baseDate", mode="before")
    @classmethod
    def _iso_date(cls, value: object) -> object:
        if not isinstance(value, str) or len(value) != 10:
            raise ValueError("baseDate must be an ISO date string")
        return value

    @field_validator("months")
    @classmethod
    def _unique(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("months must not repeat a month")
        return value

    @model_validator(mode="after")
    def _lengths_follow_the_path(self) -> "ManagerPartsRequest":
        turns = self.settings.pathLength
        team = self.team
        if len(self.months) != turns:
            raise ValueError("months must hold pathLength months")
        if len(team.totals) != turns:
            raise ValueError("team.totals must hold pathLength values")
        if len(team.returns) != turns or len(team.benchmarkReturns) != turns:
            raise ValueError("team returns must hold pathLength values")
        return self


class PartsComponents(BaseModel):
    cagr: Component
    benchCagr: Component
    excessCagr: Component
    sharpeDiff: Component
    sortino: Component
    benchSortino: Component
    sortinoDiff: Component
    calmar: Component
    benchCalmar: Component
    calmarDiff: Component
    mdd: Component
    benchMdd: Component


class PartsPercentiles(BaseModel):
    excessCagr: Percentile
    sharpeDiff: Percentile
    sortinoDiff: Percentile
    calmarDiff: Percentile
    riskItem: Percentile | None
    mdd: Percentile


class PartsValues(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    ret: Percentile = Field(alias="return")
    riskAdjusted: Percentile
    risk: Percentile


class ManagerPartsResult(BaseModel):
    referenceVersion: Literal[REFERENCE_VERSION]  # type: ignore[valid-type]
    referenceKey: Annotated[str, Field(pattern=DIGEST_PATTERN)]
    referenceSize: Annotated[int, Field(ge=1, le=REFERENCE_SIZE)]
    botVersion: Annotated[str, Field(pattern=BOT_VERSION_PATTERN)]
    engineVersion: str
    components: PartsComponents
    percentiles: PartsPercentiles
    parts: PartsValues

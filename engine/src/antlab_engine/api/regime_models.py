"""Wire models of `POST /v1/regime/evaluate`. Every number travels as a decimal string."""

from typing import Annotated, Literal, get_args

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from antlab_engine.regime.model import SeriesCode

MONTH_PATTERN = r"^(19|20)[0-9]{2}-(0[1-9]|1[0-2])$"
DIGEST_PATTERN = r"^[0-9a-f]{64}$"
STRENGTH_PATTERN = r"^[0-9]{1,4}\.[0-9]{8}$"
INDICATOR_PATTERN = r"^-?[0-9]{1,4}\.[0-9]{8}$"
PROBABILITY_PATTERN = r"^(0\.[0-9]{4}|1\.0000)$"
MIN_MONTHS, MAX_MONTHS = 1, 480

IndicatorKey = Literal[
    "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "14US", "14KR"
]
INDICATOR_KEYS: tuple[str, ...] = get_args(IndicatorKey)
SeriesKey = Literal[tuple(c.value for c in SeriesCode)]  # type: ignore[valid-type]

Month = Annotated[str, Field(pattern=MONTH_PATTERN)]
Strength = Annotated[str, Field(pattern=STRENGTH_PATTERN)]
IndicatorValue = Annotated[str, Field(pattern=INDICATOR_PATTERN)]
ProbabilityValue = Annotated[str, Field(pattern=PROBABILITY_PATTERN)]


class RegimeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    labelVersion: Literal["m2-rl-v1"]
    months: list[Month] = Field(min_length=MIN_MONTHS, max_length=MAX_MONTHS)

    @field_validator("months")
    @classmethod
    def _strictly_increasing(cls, value: list[str]) -> list[str]:
        if any(a >= b for a, b in zip(value, value[1:], strict=False)):
            raise ValueError("months must be strictly increasing")
        return value


class AxisReading(BaseModel):
    direction: Literal["UP", "DOWN", "NEUTRAL"]
    strength: Strength


class Axes(BaseModel):
    rate: AxisReading | None
    growth: AxisReading | None
    fx: AxisReading | None
    sentiment: AxisReading | None


class Probabilities(BaseModel):
    p1: ProbabilityValue
    p2: ProbabilityValue
    p3: ProbabilityValue
    p4: ProbabilityValue


class Quality(BaseModel):
    ok: bool
    stale: list[str]
    unavailable: list[str]


class RegimeMonth(BaseModel):
    month: Month
    status: Literal["EVALUATED", "CARRIED", "NO_DATA"]
    axes: Axes
    probabilities: Probabilities | None
    label: Literal["P1", "P2", "P3", "P4"] | None
    phase: Literal["WATCH", "ALERT", "TRANSITION", "CONVICTION"] | None
    indicators: dict[IndicatorKey, IndicatorValue | None]
    quality: Quality

    @model_validator(mode="after")
    def _consistent(self) -> "RegimeMonth":
        present = (self.probabilities is not None, self.label is not None, self.phase is not None)
        if any(present) != all(present):
            raise ValueError("probabilities, label and phase come together")
        if present[0] != (self.status == "EVALUATED"):
            raise ValueError("only an evaluated month has probabilities")
        if set(self.indicators) != set(INDICATOR_KEYS):
            raise ValueError("indicators must carry every key")
        return self


class RegimeCoverage(BaseModel):
    evaluated: int = Field(ge=0)
    carried: int = Field(ge=0)
    noData: int = Field(ge=0)
    indicators: dict[IndicatorKey, int]
    ruleDatedRows: dict[SeriesKey, int]  # type: ignore[valid-type]
    dtwexLinked: bool


class RegimeResult(BaseModel):
    labelVersion: Literal["m2-rl-v1"]
    regimeVersion: str
    inputDigest: Annotated[str, Field(pattern=DIGEST_PATTERN)]
    months: list[RegimeMonth]
    coverage: RegimeCoverage

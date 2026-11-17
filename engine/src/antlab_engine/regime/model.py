"""Input and output types of the regime scoring library; no I/O.

Observation and evaluation dates are real calendar dates (replay-only data): they stay out of
every `repr`, error message and log line.
"""

import math
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from operator import itemgetter
from types import MappingProxyType

import numpy as np

from antlab_engine.engine_errors import InvalidArgument


class SeriesCode(StrEnum):
    """Raw input series. Mapping a database series code to one of these is the adapter's job."""

    US_DGS2 = "US_DGS2"  # FRED DGS2, 2-year Treasury yield
    US_CPILFESL = "US_CPILFESL"  # FRED CPILFESL, core CPI index (revised)
    KR_KTB3Y = "KR_KTB3Y"  # ECOS 817Y002, 3-year government bond yield
    KR_BASE_RATE = "KR_BASE_RATE"  # ECOS 722Y001, policy rate (published on change only)
    KR_CPI = "KR_CPI"  # ECOS 901Y009, consumer price index
    US_ICSA = "US_ICSA"  # FRED ICSA, initial jobless claims (weekly, revised)
    US_SAHM = "US_SAHM"  # FRED SAHMREALTIME, Sahm rule indicator
    US_T10Y3M = "US_T10Y3M"  # FRED T10Y3M, 10-year minus 3-month spread
    KR_CLI = "KR_CLI"  # ECOS 901Y067, composite leading index
    KR_EXPORTS = "KR_EXPORTS"  # ECOS 901Y118, exports
    KR_USDKRW = "KR_USDKRW"  # ECOS 731Y001, won per dollar
    US_DTWEXBGS = "US_DTWEXBGS"  # FRED DTWEXBGS, broad dollar index (weekly publication)
    US_VIX = "US_VIX"  # FRED VIXCLS
    US_BAA10Y = "US_BAA10Y"  # FRED BAA10Y, corporate spread
    KR_CORP_AA = "KR_CORP_AA"  # ECOS, AA- corporate bond yield


class Frequency(StrEnum):
    DAILY = "DAILY"
    WEEKLY = "WEEKLY"
    MONTHLY = "MONTHLY"


class Market(StrEnum):
    US = "US"
    KR = "KR"


class Axis(StrEnum):
    RATE = "RATE"
    GROWTH = "GROWTH"
    FX = "FX"
    SENTIMENT = "SENTIMENT"


class Region(StrEnum):
    US = "US"
    KR = "KR"
    GLOBAL = "GLOBAL"


class Group(StrEnum):
    """Where a term's signal goes: a rate/growth sub-axis, the FX axis F or the sentiment axis S."""

    R_US = "R_US"
    R_KR = "R_KR"
    G_US = "G_US"
    G_KR = "G_KR"
    F = "F"
    S = "S"


class TermStatus(StrEnum):
    OK = "OK"
    STALE = "STALE"
    WARMING = "WARMING"
    MISSING = "MISSING"
    DEGENERATE = "DEGENERATE"


class Direction(StrEnum):
    UP = "UP"
    DOWN = "DOWN"
    NEUTRAL = "NEUTRAL"  # display only: inside the dead band


class Pool(StrEnum):
    """Regime pool: P1 rate down / growth down, P2 down / up, P3 up / up, P4 up / down."""

    P1 = "P1"
    P2 = "P2"
    P3 = "P3"
    P4 = "P4"


class Phase(StrEnum):
    WATCH = "WATCH"
    ALERT = "ALERT"
    TRANSITION = "TRANSITION"
    CONVICTION = "CONVICTION"


@dataclass(frozen=True, slots=True)
class SeriesSpec:
    frequency: Frequency
    market: Market  # business-day calendar of the stale rule
    # each vintage is its own observation; the Korean monthly series are revisable too, because
    # the system snapshots every collection (MACRO_ETF §2.8)
    revisable: bool = False
    kr_lag_days: int | None = None  # publication lag after the reference month end
    stale_exempt: bool = False  # published only when it changes
    weekly_publication: bool = False  # daily values published once a week


_SPECS = {
    SeriesCode.US_DGS2: SeriesSpec(Frequency.DAILY, Market.US),
    SeriesCode.US_CPILFESL: SeriesSpec(Frequency.MONTHLY, Market.US, revisable=True),
    SeriesCode.KR_KTB3Y: SeriesSpec(Frequency.DAILY, Market.KR),
    SeriesCode.KR_BASE_RATE: SeriesSpec(Frequency.DAILY, Market.KR, stale_exempt=True),
    SeriesCode.KR_CPI: SeriesSpec(Frequency.MONTHLY, Market.KR, revisable=True, kr_lag_days=7),
    SeriesCode.US_ICSA: SeriesSpec(Frequency.WEEKLY, Market.US, revisable=True),
    SeriesCode.US_SAHM: SeriesSpec(Frequency.MONTHLY, Market.US),
    SeriesCode.US_T10Y3M: SeriesSpec(Frequency.DAILY, Market.US),
    SeriesCode.KR_CLI: SeriesSpec(Frequency.MONTHLY, Market.KR, revisable=True, kr_lag_days=35),
    SeriesCode.KR_EXPORTS: SeriesSpec(Frequency.MONTHLY, Market.KR, revisable=True, kr_lag_days=15),
    SeriesCode.KR_USDKRW: SeriesSpec(Frequency.DAILY, Market.KR),
    SeriesCode.US_DTWEXBGS: SeriesSpec(Frequency.DAILY, Market.US, weekly_publication=True),
    SeriesCode.US_VIX: SeriesSpec(Frequency.DAILY, Market.US),
    SeriesCode.US_BAA10Y: SeriesSpec(Frequency.DAILY, Market.US),
    SeriesCode.KR_CORP_AA: SeriesSpec(Frequency.DAILY, Market.KR),
}
SERIES_SPECS = MappingProxyType(_SPECS)


def is_plain_date(value: object) -> bool:
    return isinstance(value, date) and not isinstance(value, datetime)


@dataclass(frozen=True, slots=True)
class Observation:
    """One published value. `available_on` is the first date it may be used (inclusive).

    Daily: `ref_date` is the trade date. Weekly: the week-ending date. Monthly: the first day of
    the month. A revisable series carries each vintage as its own observation.
    """

    series: SeriesCode
    ref_date: date = field(repr=False)
    value: Decimal = field(repr=False)
    available_on: date = field(repr=False)

    def __post_init__(self) -> None:
        if not isinstance(self.series, SeriesCode):
            raise InvalidArgument("an observation needs a SeriesCode")
        if not (is_plain_date(self.ref_date) and is_plain_date(self.available_on)):
            raise InvalidArgument("observation dates must be dates")
        if not isinstance(self.value, Decimal) or not self.value.is_finite():
            raise InvalidArgument("an observation value must be a finite Decimal")
        if not math.isfinite(float(self.value)):
            raise InvalidArgument("an observation value does not fit a float64")
        if self.available_on < self.ref_date:
            raise InvalidArgument("available_on is before ref_date")
        monthly = SERIES_SPECS[self.series].frequency is Frequency.MONTHLY
        if monthly and self.ref_date.day != 1:
            raise InvalidArgument("a monthly ref_date must be the first of the month")


@dataclass(frozen=True, slots=True, eq=False)
class SeriesView:
    """A series as it was known at one date: sorted by `ref_date`, values read-only float64."""

    series: SeriesCode
    ref_dates: tuple[date, ...] = field(repr=False)
    ordinals: np.ndarray = field(repr=False)
    values: np.ndarray = field(repr=False)
    last_available_on: date | None = field(repr=False)

    def __len__(self) -> int:
        return len(self.ref_dates)


def take_dates(dates: tuple[date, ...], index: np.ndarray) -> tuple[date, ...]:
    """`dates` at the strictly ascending positions in `index`, without a Python-level loop."""
    if len(index) == 0:
        return ()
    if index[-1] == len(index) - 1:  # strictly ascending, so this is a prefix
        return dates[: len(index)]
    picked = itemgetter(*index.tolist())(dates)
    return picked if len(index) > 1 else (picked,)


@dataclass(frozen=True, slots=True, eq=False)
class Transformed:
    """A transformed series aligned to `ref_dates`; NaN marks a point that cannot be computed.

    `ordinals` (the proleptic Gregorian ordinals of `ref_dates`) are filled in when omitted.
    """

    ref_dates: tuple[date, ...] = field(repr=False)
    values: np.ndarray = field(repr=False)
    ordinals: np.ndarray | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        if self.values.ndim != 1 or len(self.values) != len(self.ref_dates):
            raise InvalidArgument("a transformed series needs one value per reference date")
        if self.ordinals is None:
            computed = np.array([d.toordinal() for d in self.ref_dates], dtype=np.int64)
            object.__setattr__(self, "ordinals", computed)
        elif self.ordinals.shape != self.values.shape:
            raise InvalidArgument("a transformed series needs one ordinal per reference date")


@dataclass(frozen=True, slots=True)
class TermSignal:
    term_id: str
    z: float | None  # signed (positive = rate up / growth up / USD strong / risk-on), clipped
    status: TermStatus

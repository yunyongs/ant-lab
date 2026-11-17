"""Value types of the projection library: portfolios and results. Ratios only, never won."""

import re
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import (
    Context,
    Decimal,
    DecimalException,
    DivisionByZero,
    Inexact,
    InvalidOperation,
    Overflow,
)
from types import MappingProxyType

import numpy as np

from antlab_engine.engine_errors import InvalidArgument

PROJECTION_VERSION = "m2-pj-v1"
ASSET_KEY = re.compile(r"^(KR|US):[A-Z0-9.\-]{1,16}$")
PORTFOLIO_KEY = re.compile(r"^[A-Z][A-Z0-9_]{0,31}$")
MAX_ASSETS = 200
MAX_PATHS = 5000
# Exact arithmetic: any rounding (Inexact) is an error, so a sum or product that fits in 200
# significant digits is exact and one that does not is rejected instead of silently rounded.
EXACT = Context(prec=200, traps=[InvalidOperation, DivisionByZero, Overflow, Inexact])


def exact_add(left: Decimal, right: Decimal) -> Decimal:
    try:
        return EXACT.add(left, right)
    except DecimalException:
        raise InvalidArgument("a decimal value has too many digits to be added exactly") from None


def exact_multiply(left: Decimal, right: Decimal) -> Decimal:
    try:
        return EXACT.multiply(left, right)
    except DecimalException:
        raise InvalidArgument("a decimal value has too many digits to be used exactly") from None


def require_decimal(value: object, name: str) -> Decimal:
    if not isinstance(value, Decimal):
        raise InvalidArgument(f"{name} must be a Decimal")
    if not value.is_finite():
        raise InvalidArgument(f"{name} must be finite")
    return value


def check_asset_key(asset: object) -> str:
    if not isinstance(asset, str) or not ASSET_KEY.fullmatch(asset):
        raise InvalidArgument("asset key must look like MARKET:TICKER")
    return asset


@dataclass(frozen=True)
class Portfolio:
    """Fixed-quantity holding expressed as start-of-projection value shares.

    Weights and cash add up to exactly 1; zero weights stay in `weights` (so unknown assets are
    still rejected) but never take part in evaluation. The sum is checked without any rounding, so
    values whose partial sums need more than 200 significant digits are rejected.
    """

    key: str
    weights: Mapping[str, Decimal]
    cash: Decimal

    def __post_init__(self) -> None:
        if not isinstance(self.key, str) or not PORTFOLIO_KEY.fullmatch(self.key):
            raise InvalidArgument("portfolio key must match ^[A-Z][A-Z0-9_]{0,31}$")
        if not isinstance(self.weights, Mapping):
            raise InvalidArgument("weights must be a mapping")
        if len(self.weights) > MAX_ASSETS:
            raise InvalidArgument(f"a portfolio holds at most {MAX_ASSETS} assets")
        total = require_decimal(self.cash, "cash")
        if total < 0:
            raise InvalidArgument("cash must not be negative")
        checked: dict[str, Decimal] = {}
        for asset in sorted(check_asset_key(a) for a in self.weights):
            weight = require_decimal(self.weights[asset], "weight")
            if weight < 0:
                raise InvalidArgument("a weight must not be negative")
            checked[asset] = weight
            total = exact_add(total, weight)
        if total != 1:
            raise InvalidArgument("weights plus cash must add up to exactly 1")
        object.__setattr__(self, "weights", MappingProxyType(checked))

    @property
    def active(self) -> tuple[tuple[str, Decimal], ...]:
        """Non-zero weights in sorted asset order: the canonical evaluation order."""
        return tuple((asset, w) for asset, w in self.weights.items() if w != 0)


def readonly(array: np.ndarray) -> np.ndarray:
    array.flags.writeable = False
    return array


@dataclass(frozen=True, eq=False)
class Summary:
    """Statistics at one turn: end multiple quantiles, MDD quantiles and the breach count."""

    turn: int
    end_quantiles: np.ndarray
    mdd_quantiles: np.ndarray
    breach_count: int


@dataclass(frozen=True, eq=False)
class PortfolioProjection:
    key: str
    bands: np.ndarray
    summaries: tuple[Summary, ...]
    end_values: np.ndarray


@dataclass(frozen=True, eq=False)
class Projection:
    """Internal result; `end_values` stays in the process and is never serialised."""

    projection_version: str
    engine_version: str
    n: int
    horizon: int
    eligible_months: int
    portfolios: tuple[PortfolioProjection, ...]

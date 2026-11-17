"""Return panel: one growth factor per (month, asset), built once from exact decimals."""

from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal

import numpy as np

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection.model import (
    check_asset_key,
    exact_add,
    exact_multiply,
    readonly,
    require_decimal,
)

ONE = Decimal(1)
MAX_PANEL_MONTHS = 1200
MAX_PANEL_ASSETS = 1000


def _is_month(value: object) -> bool:
    return isinstance(value, date) and not isinstance(value, datetime) and value.day == 1


@dataclass(frozen=True)
class ReturnRow:
    """One return cell. Dates and returns are history, so they stay out of `repr`."""

    asset: str
    month: date = field(repr=False)
    local_return: Decimal = field(repr=False)
    fx_change: Decimal = field(repr=False)

    def __post_init__(self) -> None:
        check_asset_key(self.asset)
        if not _is_month(self.month):
            raise InvalidArgument("a return row needs a first-of-month date")
        if require_decimal(self.local_return, "local_return") < -1:
            raise InvalidArgument("local_return must be at least -1")
        if require_decimal(self.fx_change, "fx_change") <= -1:
            raise InvalidArgument("fx_change must be greater than -1")
        self.factor()

    def factor(self) -> float:
        """(1 + local) * (1 + fx), exact in Decimal and converted once (as MoneyMath.priceAt).

        Exact while the operands fit in 200 significant digits; a row that needs more digits, or
        whose factor does not fit in a float64, is rejected.
        """
        product = exact_multiply(exact_add(ONE, self.local_return), exact_add(ONE, self.fx_change))
        value = float(product)
        if not np.isfinite(value):
            raise InvalidArgument("a return is too large for a float64 growth factor")
        return value


def _check_months(months: Sequence[date]) -> None:
    if not all(_is_month(m) for m in months):
        raise InvalidArgument("months must be first-of-month dates")
    if any(a >= b for a, b in zip(months, months[1:], strict=False)):
        raise InvalidArgument("months must be unique and ascending")


@dataclass(frozen=True, eq=False)
class ReturnPanel:
    """Months and assets in ascending order; a missing cell is NaN with `present` False.

    The constructor validates and copies the arrays: shape, dtype, one finite non-negative factor
    per present cell, NaN in the others. Prefer `from_rows`.
    """

    months: tuple[date, ...] = field(repr=False)
    assets: tuple[str, ...]
    factors: np.ndarray = field(repr=False)
    present: np.ndarray = field(repr=False)

    def __post_init__(self) -> None:
        months, assets = tuple(self.months), tuple(self.assets)
        if len(months) > MAX_PANEL_MONTHS or len(assets) > MAX_PANEL_ASSETS:
            raise InvalidArgument("the return panel is too large")
        _check_months(months)
        for asset in assets:
            check_asset_key(asset)
        if any(a >= b for a, b in zip(assets, assets[1:], strict=False)):
            raise InvalidArgument("assets must be unique and ascending")
        shape = (len(months), len(assets))
        factors, present = self.factors, self.present
        if not isinstance(factors, np.ndarray) or not isinstance(present, np.ndarray):
            raise InvalidArgument("factors and present must be arrays")
        if factors.dtype != np.float64 or present.dtype != np.bool_:
            raise InvalidArgument("factors must be float64 and present must be bool")
        if factors.shape != shape or present.shape != shape:
            raise InvalidArgument("factors and present must have shape (months, assets)")
        factors, present = factors.copy(), present.copy()
        seen = factors[present]
        if not (np.isfinite(seen).all() and (seen >= 0).all()):
            raise InvalidArgument("a present cell needs a finite non-negative factor")
        if not np.isnan(factors[~present]).all():
            raise InvalidArgument("a missing cell must be NaN")
        object.__setattr__(self, "months", months)
        object.__setattr__(self, "assets", assets)
        object.__setattr__(self, "factors", readonly(factors))
        object.__setattr__(self, "present", readonly(present))

    @staticmethod
    def from_rows(
        months: Sequence[date], assets: Sequence[str], rows: Iterable[ReturnRow]
    ) -> "ReturnPanel":
        if len(months) > MAX_PANEL_MONTHS or len(assets) > MAX_PANEL_ASSETS:
            raise InvalidArgument("the return panel is too large")
        month_list = sorted(months)
        if not all(_is_month(m) for m in month_list):
            raise InvalidArgument("months must be first-of-month dates")
        if len(set(month_list)) != len(month_list):
            raise InvalidArgument("the same month appears twice")
        asset_list = sorted(check_asset_key(a) for a in assets)
        if len(set(asset_list)) != len(asset_list):
            raise InvalidArgument("the same asset appears twice")
        month_index = {m: i for i, m in enumerate(month_list)}
        asset_index = {a: i for i, a in enumerate(asset_list)}
        factors = np.full((len(month_list), len(asset_list)), np.nan)
        present = np.zeros((len(month_list), len(asset_list)), dtype=bool)
        for row in rows:
            if not isinstance(row, ReturnRow):
                raise InvalidArgument("rows must be ReturnRow values")
            if row.month not in month_index or row.asset not in asset_index:
                raise InvalidArgument("a return row names an unknown month or asset")
            cell = (month_index[row.month], asset_index[row.asset])
            if present[cell]:
                raise InvalidArgument("a return row appears twice")
            factors[cell] = row.factor()
            present[cell] = True
        return ReturnPanel(tuple(month_list), tuple(asset_list), factors, present)

    def rows_with(self, assets: Sequence[str]) -> np.ndarray:
        """Row indexes of the months where every named asset has a return."""
        index = {a: i for i, a in enumerate(self.assets)}
        mask = np.ones(len(self.months), dtype=bool)
        for asset in assets:
            if asset not in index:
                raise InvalidArgument("the panel does not have a requested asset")
            mask &= self.present[:, index[asset]]
        return np.flatnonzero(mask)

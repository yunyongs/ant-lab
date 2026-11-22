"""Pure model of the history the engine reads; stdlib only.

Month starts are internal: they must not appear in logs or in any response before review.
No prices and no won amounts are read (the engine returns ratios; Java converts to won).
"""

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Literal, Protocol


@dataclass(frozen=True, slots=True)
class HistoryMonth:
    month_block_id: int
    month_start: date = field(repr=False)  # internal replay month: never in logs or reprs
    regime_label: str | None = field(default=None, repr=False)  # "P1".."P4"; NULL = not labelled


@dataclass(frozen=True, slots=True)
class PoolAsset:
    asset_id: int
    market: Literal["KR", "US"]
    ticker: str
    currency: Literal["KRW", "USD"]
    asset_type: Literal["STOCK", "ETF"]


@dataclass(frozen=True, slots=True)
class MonthReturn:
    asset_id: int
    month_block_id: int
    local_return: Decimal
    return_kind: Literal["ACTUAL", "PROXY_INDEX"]


@dataclass(frozen=True, slots=True)
class FxChange:
    month_block_id: int
    currency: Literal["USD"]
    fx_change: Decimal


@dataclass(frozen=True, slots=True)
class SessionFacts:
    role: str
    read_only: bool
    isolation: str


@dataclass(frozen=True, slots=True)
class HistorySnapshot:
    base_date: date
    upper_bound: date
    months: tuple[HistoryMonth, ...]
    pool: tuple[PoolAsset, ...]
    returns: tuple[MonthReturn, ...]
    fx: tuple[FxChange, ...]
    session: SessionFacts


class HistoryReader(Protocol):
    def read(self, base_date: date) -> HistorySnapshot: ...

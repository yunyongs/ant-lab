"""Synthetic history snapshots (pool, returns, FX) for the projection route tests. Not real data."""

from collections.abc import Sequence
from datetime import date
from decimal import Decimal

from synthetic_months import month_range

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.history.model import (
    FxChange,
    HistoryMonth,
    HistorySnapshot,
    MonthReturn,
    PoolAsset,
    SessionFacts,
)

# 2006-12 is below the replay start, 2026-01 is the upper bound of the 2026 base year; the
# 228 months in between are replayable.
FULL_MONTHS = month_range(date(2006, 12, 1), date(2026, 1, 1))
BASE_DATE = date(2026, 1, 2)
SESSION = SessionFacts(role="fake", read_only=True, isolation="repeatable read")


def pool_asset(asset_id: int, key: str) -> PoolAsset:
    market, ticker = key.split(":")
    us = market == "US"
    return PoolAsset(
        asset_id,
        "US" if us else "KR",
        ticker,
        "USD" if us else "KRW",
        "ETF",
    )


def snapshot(
    assets: Sequence[str],
    months: Sequence[date] = FULL_MONTHS,
    seed: int = 7,
    skip: frozenset[tuple[str, date]] = frozenset(),
    base_date: date = BASE_DATE,
    changes: dict[tuple[str, date], str] | None = None,
) -> HistorySnapshot:
    """Returns in about [-10 %, +10 %] per asset and month; US assets also get an FX move."""
    rng = SplitMix64(seed)
    blocks = {m: i + 1 for i, m in enumerate(months)}
    pool = tuple(pool_asset(i + 1, key) for i, key in enumerate(assets))
    returns, fx = [], []
    fx_by_month = {m: Decimal(rng.next_int(401) - 200) / Decimal(10000) for m in months}
    for month in months:
        for asset in pool:
            key = f"{asset.market}:{asset.ticker}"
            local = Decimal(rng.next_int(2001) - 1000) / Decimal(10000)
            if changes and (key, month) in changes:
                local = Decimal(changes[(key, month)])
            if (key, month) not in skip:
                returns.append(MonthReturn(asset.asset_id, blocks[month], local, "ACTUAL"))
    fx = [FxChange(blocks[m], "USD", fx_by_month[m]) for m in months]
    return HistorySnapshot(
        base_date=base_date,
        upper_bound=date(base_date.year, 1, 1),
        months=tuple(HistoryMonth(blocks[m], m) for m in months),
        pool=pool,
        returns=tuple(returns),
        fx=tuple(fx),
        session=SESSION,
    )

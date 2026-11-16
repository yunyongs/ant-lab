"""Synthetic return panels for the projection tests. Not real history."""

from collections.abc import Iterable, Mapping, Sequence
from datetime import date
from decimal import Decimal

from synthetic_months import month_range

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.projection import Portfolio, ReturnPanel, ReturnRow

REPLAY_MONTHS = month_range(date(1997, 1, 1), date(2025, 12, 1))


def make_panel(months: Sequence[date], table: Mapping[str, Mapping[date, str]]) -> ReturnPanel:
    """Panel from explicit local returns; a month missing from an asset's table has no cell."""
    rows = [
        ReturnRow(asset, month, Decimal(local), Decimal(0))
        for asset, cells in table.items()
        for month, local in cells.items()
    ]
    return ReturnPanel.from_rows(months, list(table), rows)


def constant_panel(months: Sequence[date], local: Mapping[str, str]) -> ReturnPanel:
    return make_panel(months, {a: dict.fromkeys(months, r) for a, r in local.items()})


def random_rows(
    months: Iterable[date], assets: Sequence[str], seed: int, skip: frozenset = frozenset()
) -> list[ReturnRow]:
    """Local returns in about [-10 %, +10 %]; US assets also get a currency move."""
    rng = SplitMix64(seed)
    rows = []
    for month in months:
        for asset in assets:
            local = Decimal(rng.next_int(2001) - 1000) / Decimal(10000)
            fx = Decimal(rng.next_int(401) - 200) / Decimal(10000) if asset[:2] == "US" else 0
            if (asset, month) not in skip:
                rows.append(ReturnRow(asset, month, local, Decimal(fx)))
    return rows


def random_panel(
    months: Sequence[date], assets: Sequence[str], seed: int, skip: frozenset = frozenset()
) -> ReturnPanel:
    return ReturnPanel.from_rows(months, assets, random_rows(months, assets, seed, skip))


def asset_names(count: int) -> list[str]:
    return [f"{'KR' if i % 2 else 'US'}:T{i:03d}" for i in range(count)]


def split(assets: Sequence[str], cash: str = "0.1") -> dict[str, Decimal]:
    """Equal weights that add up to exactly 1 together with `cash` (assets share the rest)."""
    share = (Decimal(1) - Decimal(cash)) / len(assets)
    weights = {a: share.quantize(Decimal("1e-12")) for a in assets}
    return weights


def portfolio(key: str, assets: Sequence[str]) -> Portfolio:
    weights = split(assets)
    cash = Decimal(1) - sum(weights.values(), Decimal(0))
    return Portfolio(key, weights, cash)

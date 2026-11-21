"""Reference formulas for the DERIVED rows of `db/full/proxy_series.csv` (MVP-03 A).

The build SQL (06-A) must give the same monthly returns as these functions for the same inputs; the golden values in
`tests/test_derived.py` and in `db/full/README.md` come from here. All values are `Decimal` ratios (0.05 = 5 %), rounded
once by the caller at CSV write (8 decimals). Yields arrive in percent in the source files: divide by 100 first.
"""
from __future__ import annotations

from decimal import Decimal, getcontext

getcontext().prec = 40

ONE = Decimal(1)
TWELVE = Decimal(12)
HALF = Decimal("0.5")


def par_bond_return(y_prev: Decimal, y_now: Decimal, years: int) -> Decimal:
    """PAR_BOND_TR: one month of a constant-maturity bond that pays a semiannual coupon equal to last month-end's yield.

    carry = y_prev / 12; price effect = exact repricing of that bond at this month-end's yield (maturity held constant, no roll-down),
    which contains the duration, convexity and higher-order terms without a duration constant.
    """
    if years < 1 or y_prev <= -ONE or y_now <= -ONE:
        raise ValueError("maturity must be a positive number of years and yields above -100 %")
    n = 2 * years
    j = y_now / 2
    c = y_prev / 2
    discount = (ONE + j) ** -n
    annuity = Decimal(n) if j == 0 else (ONE - discount) / j
    price = c * annuity + discount
    return y_prev / TWELVE + (price - ONE)


def cash_accrual(y_prev: Decimal) -> Decimal:
    """CASH_ACCRUAL: a money-market rate earns one twelfth of last month-end's annual rate; no price effect."""
    return y_prev / TWELVE


def mix_50_50(a: Decimal, b: Decimal) -> Decimal:
    """MIX_50_50: equal weights rebalanced every month."""
    return HALF * a + HALF * b


def to_krw(local_return: Decimal, fx_change: Decimal) -> Decimal:
    """Apply once: (1 + r) * (1 + fx_change) - 1, fx_change = month change of KRW per USD (`fx_month`)."""
    return (ONE + local_return) * (ONE + fx_change) - ONE


def level_return(prev: Decimal, now: Decimal) -> Decimal:
    if prev <= 0 or now <= 0:
        raise ValueError("levels must be positive")
    return now / prev - ONE


def etf_proxy_return(index_return: Decimal, annual_fee: Decimal) -> Decimal:
    """Fee deduction for an ETF month filled by an index (PLAN §4.6): subtract fee / 12; never for a stock month."""
    return index_return - annual_fee / TWELVE


def mix_50_50_fx(usd_leg: Decimal, krw_leg: Decimal, fx_change: Decimal) -> Decimal:
    """MIX_50_50_FX: the USD leg is converted to won first, the won leg is not: 0.5 * ((1 + a)(1 + fx) - 1) + 0.5 * b."""
    return HALF * to_krw(usd_leg, fx_change) + HALF * krw_leg


def mix_weighted(a: Decimal, b: Decimal, weight_a_percent: int) -> Decimal:
    """MIX_W: `weight_a_percent` % of `a` and the rest of `b`, rebalanced every month."""
    if not 0 < weight_a_percent < 100:
        raise ValueError("the weight is a whole percent between 1 and 99")
    w = Decimal(weight_a_percent) / 100
    return w * a + (ONE - w) * b

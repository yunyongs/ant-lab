"""Position measurement from monthly return lists (Java `PositionMeter`, SQL `pos_*`).

Inputs are lists of exact `Decimal` returns; a month may be `None` (no stored return). Every
product and sum is exact, every rounding is the one named in the Java method.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum

from . import money
from .model import Position, SimReportError
from .rules import (
    BAND,
    BENCH_KR_WEIGHT,
    BENCH_US_WEIGHT,
    BREAKOUT_MDD_FACTOR,
    BREAKOUT_UP_ABOVE,
    DEFENDER_DOWN_BELOW,
    DEFENSIVE_DOWN_BELOW,
    DEFENSIVE_UP_MIN,
    GOALKEEPER_DOWN_MAX,
    TARGET_DOWN_MAX,
    TARGET_UP_ABOVE,
    TOP_RATIO,
)

_X = money.EXACT
_ONE = Decimal(1)
_ZERO = Decimal(0)


class Reason(StrEnum):
    """Why an asset cannot be judged; the declaration order is the SQL CASE order."""

    MONTHS = "MONTHS"
    N_UP = "N_UP"
    N_DOWN = "N_DOWN"
    ZERO_DEN = "ZERO_DEN"


@dataclass(frozen=True)
class Metrics:
    months_used: int
    n_up: int
    n_down: int
    up: Decimal | None
    down: Decimal | None
    volatility: Decimal | None
    max_drawdown: Decimal | None
    reason: Reason | None

    @property
    def judgeable(self) -> bool:
        return self.reason is None


def market_return(kr_return: Decimal | None, us_krw_return: Decimal | None) -> Decimal:
    if kr_return is None or us_krw_return is None:
        raise SimReportError("a market proxy return is missing")
    return _X.add(
        _X.multiply(BENCH_KR_WEIGHT, kr_return), _X.multiply(BENCH_US_WEIGHT, us_krw_return)
    )


def krw_return(krw_currency: bool, local_return: Decimal, fx_change: Decimal | None) -> Decimal:
    """Local return for KRW assets, else (1 + local)(1 + fx) - 1, exact."""
    if krw_currency:
        return local_return
    if fx_change is None:
        raise SimReportError("a foreign asset has no fx change")
    return _X.subtract(_X.multiply(_X.add(_ONE, local_return), _X.add(_ONE, fx_change)), _ONE)


def max_drawdown(returns: Sequence[Decimal]) -> Decimal | None:
    """Worst peak-to-trough ratio of the compounded path (W0 = 1), 8 decimals, once."""
    if not returns:
        return None
    wealth = _ONE
    peak = _ONE
    low = _ZERO
    for r in returns:
        wealth = _X.multiply(wealth, _X.add(_ONE, r))
        if wealth > peak:
            peak = wealth
        low = min(low, _X.subtract(money.pad40_divide(wealth, peak), _ONE))
    return money.round8(low)


def measure(
    returns: Sequence[Decimal | None],
    market: Sequence[Decimal | None],
    min_months: int,
    min_up: int,
    min_down: int,
) -> Metrics:
    if len(returns) != len(market):
        raise SimReportError("asset and market return lists differ in length")
    if min_months < 2:
        raise SimReportError("the minimum month count must be at least 2")
    pairs = [
        (r, m) for r, m in zip(returns, market, strict=True) if r is not None and m is not None
    ]
    r_used = [r for r, _ in pairs]
    n_up = n_down = 0
    sum_r_up = sum_m_up = sum_r_down = sum_m_down = None
    for r, m in pairs:
        if m >= BAND:
            n_up += 1
            sum_r_up = r if sum_r_up is None else _X.add(sum_r_up, r)
            sum_m_up = m if sum_m_up is None else _X.add(sum_m_up, m)
        if m <= -BAND:
            n_down += 1
            sum_r_down = r if sum_r_down is None else _X.add(sum_r_down, r)
            sum_m_down = m if sum_m_down is None else _X.add(sum_m_down, m)
    months = len(pairs)
    reason: Reason | None = None
    if months < min_months:
        reason = Reason.MONTHS
    elif n_up < min_up:
        reason = Reason.N_UP
    elif n_down < min_down:
        reason = Reason.N_DOWN
    elif not sum_m_up or not sum_m_down:
        reason = Reason.ZERO_DEN
    if reason is not None:
        return Metrics(months, n_up, n_down, None, None, None, None, reason)
    if sum_r_up is None or sum_r_down is None or sum_m_up is None or sum_m_down is None:
        raise SimReportError("unreachable: judged asset without sums")
    return Metrics(
        months,
        n_up,
        n_down,
        money.round8(money.pad40_divide(sum_r_up, sum_m_up)),
        money.round8(money.pad40_divide(sum_r_down, sum_m_down)),
        money.sample_stddev8(r_used),
        max_drawdown(r_used),
        None,
    )


def classify_pool[K](
    metrics: Mapping[K, Metrics], market_max_drawdown: Decimal
) -> dict[K, Position]:
    """Pool-relative judgement: k = ceil(0.20 * N) by volatility rank, ties share a rank."""
    judged = [m for m in metrics.values() if m.judgeable]
    k = money.ceil_count(TOP_RATIO, len(judged))
    result: dict[K, Position] = {}
    for key, mine in metrics.items():
        if not mine.judgeable:
            continue
        rank_desc = rank_asc = 1
        for other in judged:
            if other.volatility > mine.volatility:  # type: ignore[operator]
                rank_desc += 1
            elif other.volatility < mine.volatility:  # type: ignore[operator]
                rank_asc += 1
        result[key] = classify(
            mine.up,  # type: ignore[arg-type]
            mine.down,  # type: ignore[arg-type]
            mine.max_drawdown,  # type: ignore[arg-type]
            market_max_drawdown,
            rank_desc <= k,
            rank_asc <= k,
        )
    return result


def classify(
    up: Decimal,
    down: Decimal,
    max_dd: Decimal,
    market_max_dd: Decimal,
    vol_top: bool,
    vol_bottom: bool,
) -> Position:
    if vol_bottom and down <= GOALKEEPER_DOWN_MAX:
        return Position.GOALKEEPER
    if down < DEFENDER_DOWN_BELOW:
        return Position.DEFENDER
    if (
        vol_top
        and up > BREAKOUT_UP_ABOVE
        and max_dd <= _X.multiply(BREAKOUT_MDD_FACTOR, market_max_dd)
    ):
        return Position.FORWARD_BREAKOUT
    if up >= DEFENSIVE_UP_MIN and down < DEFENSIVE_DOWN_BELOW:
        return Position.FORWARD_DEFENSIVE
    if up > TARGET_UP_ABOVE and down <= TARGET_DOWN_MAX and not vol_top:
        return Position.FORWARD_TARGET
    return Position.MIDFIELDER

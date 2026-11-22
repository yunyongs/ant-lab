"""Python twin of the Java `MoneyMath` functions the season rules use.

Money is `Decimal` only. Every rounding goes through `_rescale` or `_divide`, which work on exact
integers, so a result carries the same value and the same scale as the Java `BigDecimal`. Refusals
raise `MoneyError` (Java `IllegalArgumentException`) or `MoneyArithmeticError` (Java
`ArithmeticException`).
"""

import decimal
from collections.abc import Sequence
from decimal import Context, Decimal
from math import isqrt

from .rules import (
    INT_MAX,
    INT_MIN,
    MAX_MONEY,
    PAD_SCALE,
    RATE_PRECISION,
    RATE_SCALE,
    SCORE_ROOT_PRECISION,
    SCORE_WORK_SCALE,
    SHARE_SCALE,
)

# Every use is exact (products, sums, truncating division); the Inexact trap proves it per call.
_EXACT = Context(
    prec=decimal.MAX_PREC, Emax=decimal.MAX_EMAX, Emin=decimal.MIN_EMIN, traps=[decimal.Inexact]
)

EXACT = _EXACT

HALF_UP = "HALF_UP"
DOWN = "DOWN"
CEILING = "CEILING"
UNNECESSARY = "UNNECESSARY"

ZERO = Decimal(0)
ONE = Decimal(1)


class MoneyError(ValueError):
    """The Java `IllegalArgumentException` of a refused input."""


class MoneyArithmeticError(ArithmeticError):
    """The Java `ArithmeticException` (division by zero, an exact conversion that is not exact)."""


def _invalid(message: str) -> MoneyError:
    return MoneyError(message)


def _require(value: object, field: str) -> None:
    if value is None:
        raise _invalid(f"{field}: null")
    if isinstance(value, Decimal) and not value.is_finite():
        raise _invalid(f"{field}: not finite")


def _parts(value: Decimal) -> tuple[int, int]:
    """Signed unscaled integer and scale (Java `unscaledValue`, `scale`)."""
    sign, digits, exponent = value.as_tuple()
    if not isinstance(exponent, int):
        raise _invalid("not a finite number")
    unscaled = int("".join(map(str, digits)))
    return (-unscaled if sign else unscaled), -exponent


def _build(unscaled: int, scale: int) -> Decimal:
    digits = tuple(int(c) for c in str(abs(unscaled)))
    return Decimal((1 if unscaled < 0 else 0, digits, -scale))


def _round_quotient(numerator: int, denominator: int, mode: str) -> int:
    """Integer quotient of two integers under a Java rounding mode (denominator non-zero)."""
    negative = (numerator < 0) != (denominator < 0)
    n, d = abs(numerator), abs(denominator)
    q, r = divmod(n, d)
    if r:
        if mode == HALF_UP:
            if 2 * r >= d:
                q += 1
        elif mode == CEILING:
            if not negative:
                q += 1
        elif mode == UNNECESSARY:
            raise MoneyArithmeticError("rounding necessary")
        elif mode != DOWN:
            raise ValueError(mode)
    return -q if negative else q


def _rescale(value: Decimal, scale: int, mode: str) -> Decimal:
    unscaled, current = _parts(value)
    if scale >= current:
        return _build(unscaled * 10 ** (scale - current), scale)
    return _build(_round_quotient(unscaled, 10 ** (current - scale), mode), scale)


def _divide(numerator: Decimal, denominator: Decimal, scale: int, mode: str = HALF_UP) -> Decimal:
    """`numerator.divide(denominator, scale, mode)`; the denominator must not be zero."""
    a, sa = _parts(numerator)
    b, sb = _parts(denominator)
    # a/10^sa / (b/10^sb) = a*10^sb / (b*10^sa); the result has `scale` digits.
    exponent = scale + sb - sa
    top, bottom = (a * 10**exponent, b) if exponent >= 0 else (a, b * 10**-exponent)
    return _build(_round_quotient(top, bottom, mode), scale)


def _scale_of(value: Decimal) -> int:
    return _parts(value)[1]


def _precision_of(value: Decimal) -> int:
    return len(value.as_tuple().digits)


def _is_integer(value: Decimal) -> bool:
    return value == value.to_integral_value()


def _numeric(value: Decimal, precision: int, scale: int, field: str) -> None:
    try:
        scaled = _rescale(value, scale, UNNECESSARY)
    except MoneyArithmeticError:
        raise _invalid(f"{field}: not exact at the allowed scale") from None
    if _precision_of(scaled) > precision:
        raise _invalid(f"{field}: too many digits")


def _positive_integer(value: Decimal, field: str) -> None:
    _require(value, field)
    if value <= 0 or not _is_integer(value) or value > MAX_MONEY:
        raise _invalid(f"{field}: positive whole won")


def _non_negative_integer(value: Decimal, field: str) -> None:
    _require(value, field)
    if value < 0 or not _is_integer(value) or value > MAX_MONEY:
        raise _invalid(f"{field}: non-negative whole won")


def _stored_money(value: Decimal, field: str) -> Decimal:
    if value < 0 or value > MAX_MONEY:
        raise _invalid(f"{field}: out of NUMERIC(19,4)")
    return value


def _stored_signed_money(value: Decimal, field: str) -> Decimal:
    if abs(value) > MAX_MONEY:
        raise _invalid(f"{field}: out of NUMERIC(19,4)")
    return value


def _positive_int(value: int, field: str) -> None:
    if value <= 0:
        raise _invalid(f"{field}: positive")


def _non_negative_int(value: int, field: str) -> None:
    if value < 0:
        raise _invalid(f"{field}: non-negative")


def _non_negative_numeric(value: Decimal, precision: int, scale: int, field: str) -> None:
    _require(value, field)
    _numeric(value, precision, scale, field)
    if value < 0:
        raise _invalid(f"{field}: negative")


def _non_negative_rate(value: Decimal, field: str) -> None:
    _require(value, field)
    _numeric(value, RATE_PRECISION, RATE_SCALE, field)
    if value < 0 or value >= 1:
        raise _invalid(f"{field}: 0 <= rate < 1")


def _positive_rate(value: Decimal, field: str, precision: int, scale: int) -> None:
    _require(value, field)
    _numeric(value, precision, scale, field)
    if value <= 0:
        raise _invalid(f"{field}: positive")


def _return_value(value: Decimal, field: str, allow_minus_one: bool) -> None:
    _require(value, field)
    _numeric(value, RATE_PRECISION, RATE_SCALE, field)
    if value < -1 or (value == -1 and not allow_minus_one):
        raise _invalid(f"{field}: out of range")


def initial_price(base_price: Decimal, base_fx_rate: Decimal) -> Decimal:
    _positive_rate(base_price, "base price", 19, 4)
    _positive_rate(base_fx_rate, "base fx rate", 19, 4)
    return _stored_money(_rescale(_EXACT.multiply(base_price, base_fx_rate), 0, HALF_UP), "price")


def price_at(initial: Decimal, returns: Sequence[tuple[Decimal, Decimal]]) -> Decimal:
    _positive_integer(initial, "stored price")
    _require(returns, "returns")
    factor = ONE
    for local_return, fx_change in returns:
        _return_value(local_return, "local return", True)
        _return_value(fx_change, "fx change", False)
        factor = _EXACT.multiply(
            _EXACT.multiply(factor, _EXACT.add(ONE, local_return)), _EXACT.add(ONE, fx_change)
        )
    return _stored_money(_rescale(_EXACT.multiply(initial, factor), 0, HALF_UP), "price")


def fee_rate(market: str, kr_rate: Decimal, us_rate: Decimal, fx_rate: Decimal) -> Decimal:
    _require(market, "market")
    _non_negative_rate(kr_rate, "kr rate")
    _non_negative_rate(us_rate, "us rate")
    _non_negative_rate(fx_rate, "fx rate")
    if market == "KR":
        result = kr_rate
    elif market == "US":
        result = _EXACT.add(us_rate, fx_rate)
    else:
        raise _invalid("market must be KR or US")
    _non_negative_numeric(result, RATE_PRECISION, RATE_SCALE, "combined rate")
    return _rescale(result, RATE_SCALE, UNNECESSARY)


def fee(price: Decimal, quantity: int, rate: Decimal) -> Decimal:
    _positive_integer(price, "price")
    _positive_int(quantity, "quantity")
    _non_negative_numeric(rate, RATE_PRECISION, RATE_SCALE, "combined rate")
    amount = _EXACT.multiply(_EXACT.multiply(price, Decimal(quantity)), rate)
    return _stored_money(_rescale(amount, 0, HALF_UP), "fee")


def avg_price(old_quantity: int, old_avg_price: Decimal, quantity: int, price: Decimal) -> Decimal:
    _non_negative_int(old_quantity, "old quantity")
    _positive_int(quantity, "quantity")
    _require(old_avg_price, "old average price")
    _positive_integer(price, "price")
    if old_quantity == 0 and old_avg_price != 0:
        raise _invalid("old average price must be 0")
    if old_quantity > 0 and old_avg_price <= 0:
        raise _invalid("old average price must be positive")
    _numeric(old_avg_price, 19, 4, "old average price")
    total = old_quantity + quantity
    if total > INT_MAX:
        raise _invalid("quantity sum out of int range")
    value = _EXACT.add(
        _EXACT.multiply(old_avg_price, Decimal(old_quantity)),
        _EXACT.multiply(price, Decimal(quantity)),
    )
    return _stored_money(_divide(value, Decimal(total), 4, HALF_UP), "average price")


def realized_pnl(price: Decimal, avg: Decimal, quantity: int) -> Decimal:
    _positive_integer(price, "price")
    _require(avg, "average price")
    _numeric(avg, 19, 4, "average price")
    if avg <= 0:
        raise _invalid("average price must be positive")
    _positive_int(quantity, "quantity")
    amount = _EXACT.multiply(_EXACT.subtract(price, avg), Decimal(quantity))
    return _stored_signed_money(_rescale(amount, 4, HALF_UP), "realized pnl")


def affordable(available: Decimal, notional: Decimal, fee_amount: Decimal) -> bool:
    _non_negative_integer(available, "available")
    _non_negative_integer(notional, "notional")
    _non_negative_integer(fee_amount, "fee")
    return _EXACT.add(notional, fee_amount) <= available


def team_qty(budget: Decimal, price: Decimal, rate: Decimal) -> int:
    _non_negative_integer(budget, "budget")
    _positive_integer(price, "price")
    _non_negative_numeric(rate, RATE_PRECISION, RATE_SCALE, "combined rate")
    divisor = _EXACT.multiply(price, _EXACT.add(ONE, rate))
    quantity = int(_EXACT.divide_int(budget, divisor))
    if quantity > INT_MAX:
        raise _invalid("quantity out of int range")
    return quantity


def drawdown(total: Decimal, peak: Decimal) -> Decimal:
    _non_negative_integer(total, "total")
    _positive_integer(peak, "peak")
    if total >= peak:
        return _rescale(ZERO, 8, UNNECESSARY)
    return _divide(_EXACT.subtract(total, peak), peak, 8, HALF_UP)


def ratio(numerator: Decimal, denominator: Decimal) -> Decimal:
    _require(numerator, "numerator")
    _require(denominator, "denominator")
    if not _is_integer(numerator) or not _is_integer(denominator):
        raise _invalid("ratio needs whole numbers")
    if denominator == 0:
        raise _invalid("zero denominator")
    return _divide(numerator, denominator, 8, HALF_UP)


def valuation(price: Decimal, quantity: int) -> Decimal:
    _positive_integer(price, "price")
    _non_negative_int(quantity, "quantity")
    return _stored_money(_EXACT.multiply(price, Decimal(quantity)), "valuation")


def total_value(cash: Decimal, position_values: Sequence[Decimal]) -> Decimal:
    _non_negative_integer(cash, "cash")
    _require(position_values, "position values")
    result = cash
    for value in position_values:
        _non_negative_integer(value, "valuation")
        result = _EXACT.add(result, value)
    return _stored_money(result, "total value")


def _exact_scale(value: Decimal, scale: int, field: str) -> Decimal:
    try:
        return _rescale(value, scale, UNNECESSARY)
    except MoneyArithmeticError:
        raise _invalid(f"{field}: not exact at the allowed scale") from None


def exact_won(value: Decimal) -> Decimal:
    _require(value, "amount")
    return _exact_scale(value, 0, "amount")


def exact_avg_price(value: Decimal) -> Decimal:
    _require(value, "average price")
    return _exact_scale(value, 4, "average price")


def exact_stored_price(value: Decimal) -> Decimal:
    _require(value, "price")
    return _exact_scale(value, 4, "price")


def exact_rate(value: Decimal) -> Decimal:
    _require(value, "rate")
    return _exact_scale(value, 8, "rate")


def exact_probability(value: Decimal) -> Decimal:
    _require(value, "probability")
    return _exact_scale(value, 4, "probability")


def fraction_mean(numerators: Sequence[int], denominators: Sequence[int]) -> Decimal:
    _require(numerators, "numerators")
    _require(denominators, "denominators")
    n = len(numerators)
    if n == 0 or n != len(denominators):
        raise _invalid("fraction lists are empty or differ in length")
    num, den = 0, 1
    for a, b in zip(numerators, denominators, strict=True):
        num = num * b + a * den
        den = den * b
    return ratio(Decimal(num), Decimal(den * n))


def _group_weight(value: Decimal) -> int:
    if value == 0:
        return 0
    exponent = _precision_of(value) - _scale_of(value) - 1
    return exponent // 4


def _first_group(value: Decimal) -> int:
    if value == 0:
        return 0
    unscaled, scale = _parts(value)
    shift = scale + 4 * _group_weight(value)
    magnitude = abs(unscaled)
    if shift >= 0:
        return magnitude // 10**shift
    return magnitude * 10 ** (-shift)


def _div_scale(v1: Decimal, dscale1: int, v2: Decimal, dscale2: int) -> int:
    qweight = _group_weight(v1) - _group_weight(v2)
    if _first_group(v1) <= _first_group(v2):
        qweight -= 1
    rscale = max(16 - qweight * 4, dscale1, dscale2)
    return min(max(rscale, 0), 1000)


def pad40_divide(numerator: Decimal, denominator: Decimal) -> Decimal:
    _require(numerator, "numerator")
    _require(denominator, "denominator")
    if denominator == 0:
        raise _invalid("zero denominator")
    rscale = _div_scale(
        numerator,
        max(max(_scale_of(numerator), 0), PAD_SCALE),
        denominator,
        max(_scale_of(denominator), 0),
    )
    return _divide(numerator, denominator, rscale, HALF_UP)


def _sample_variance(values: Sequence[Decimal]) -> Decimal | None:
    _require(values, "values")
    n = len(values)
    if n < 2:
        return None
    s = 0
    sum_x = ZERO
    sum_x2 = ZERO
    for x in values:
        _require(x, "value")
        s = max(s, _scale_of(x))
        sum_x = _EXACT.add(sum_x, x)
        sum_x2 = _EXACT.add(sum_x2, _EXACT.multiply(x, x))
    numerator = _EXACT.subtract(_EXACT.multiply(Decimal(n), sum_x2), _EXACT.multiply(sum_x, sum_x))
    if numerator <= 0:
        return ZERO
    denominator = Decimal(n * (n - 1))
    rscale = _div_scale(numerator, 2 * s, denominator, 0)
    return _divide(numerator, denominator, rscale, HALF_UP)


def sample_stddev(values: Sequence[Decimal]) -> Decimal | None:
    variance = _sample_variance(values)
    if variance is None:
        return None
    if variance == 0:
        return ZERO
    unscaled, rscale = _parts(variance)
    scaled = unscaled * 10**rscale
    root = isqrt(scaled)
    if scaled - root * root > root:
        root += 1
    return _build(root, rscale)


def round8(value: Decimal) -> Decimal:
    _require(value, "value")
    return _rescale(value, 8, HALF_UP)


def sample_stddev8(values: Sequence[Decimal]) -> Decimal | None:
    stddev = sample_stddev(values)
    return None if stddev is None else round8(stddev)


def pg_divide(numerator: Decimal, denominator: Decimal) -> Decimal:
    _require(numerator, "numerator")
    _require(denominator, "denominator")
    if denominator == 0:
        raise _invalid("zero denominator")
    rscale = _div_scale(
        numerator, max(_scale_of(numerator), 0), denominator, max(_scale_of(denominator), 0)
    )
    return _divide(numerator, denominator, rscale, HALF_UP)


def pg_avg(values: Sequence[Decimal]) -> Decimal | None:
    _require(values, "values")
    if not values:
        return None
    total = ZERO
    for x in values:
        _require(x, "value")
        total = _EXACT.add(total, x)
    return pg_divide(total, Decimal(len(values)))


def share_down(valuation_amount: Decimal, total: Decimal) -> Decimal:
    _non_negative_integer(valuation_amount, "valuation")
    _positive_integer(total, "total value")
    if valuation_amount > total:
        raise _invalid("valuation exceeds the total value")
    return _divide(valuation_amount, total, SHARE_SCALE, DOWN)


def scale_by_multiple(total: Decimal, multiple: Decimal) -> Decimal:
    _positive_integer(total, "total value")
    _require(multiple, "multiple")
    if multiple < 0:
        raise _invalid("negative multiple")
    amount = _EXACT.multiply(total, multiple)
    return _stored_money(_rescale(amount, 0, HALF_UP), "scaled value")


def score_divide(numerator: Decimal, denominator: Decimal) -> Decimal:
    _require(numerator, "numerator")
    _require(denominator, "denominator")
    if denominator == 0:
        raise MoneyArithmeticError("division by zero")
    return _divide(numerator, denominator, SCORE_WORK_SCALE, HALF_UP)


def _strip_to_scale(unscaled: int, scale: int, preferred: int) -> tuple[int, int]:
    while scale > preferred and unscaled % 10 == 0 and unscaled != 0:
        unscaled //= 10
        scale -= 1
    return unscaled, scale


def divide_significant(numerator: Decimal, denominator: Decimal) -> Decimal:
    _require(numerator, "numerator")
    _require(denominator, "denominator")
    if denominator == 0:
        raise MoneyArithmeticError("division by zero")
    a, sa = _parts(numerator)
    b, sb = _parts(denominator)
    preferred = sa - sb
    if a == 0:
        return _build(0, preferred)
    negative = (a < 0) != (b < 0)
    a, b = abs(a), abs(b)
    precision = SCORE_ROOT_PRECISION
    shift = precision - (len(str(a)) - len(str(b)))
    while True:
        top, bottom = (a * 10**shift, b) if shift >= 0 else (a, b * 10 ** (-shift))
        q, r = divmod(top, bottom)
        digits = len(str(q))
        if digits == precision:
            break
        shift += precision - digits
    exact = r == 0
    if not exact and 2 * r >= bottom:
        q += 1
        if len(str(q)) > precision:
            q //= 10
            shift -= 1
    scale = shift + preferred
    if exact:
        q, scale = _strip_to_scale(q, scale, preferred)
    return _build(-q if negative else q, scale)


def sqrt_significant(value: Decimal) -> Decimal:
    _require(value, "value")
    if value < 0:
        raise MoneyArithmeticError("square root of a negative number")
    unscaled, scale = _parts(value)
    preferred = scale // 2 if scale >= 0 else -((-scale) // 2)
    if unscaled == 0:
        return _build(0, preferred)
    precision = SCORE_ROOT_PRECISION
    digits_before_point = (len(str(unscaled)) - scale + 1) // 2
    p = precision - digits_before_point + 6
    while True:
        p = max(p, (scale + 1) // 2)
        target = unscaled * 10 ** (2 * p - scale)
        root = isqrt(target)
        if len(str(root)) >= precision + 2:
            break
        p += 3
    exact_root = root * root == target
    drop = len(str(root)) - precision
    base = 10**drop
    q, rem = divmod(root, base)
    exact = exact_root and rem == 0
    if not exact:
        # round half up: sqrt(target) >= (q + 1/2) * base  <=>  4 * target >= ((2q + 1) * base)^2
        if 4 * target >= ((2 * q + 1) * base) ** 2:
            q += 1
        if len(str(q)) > precision:
            q //= 10
            drop += 1
    result_scale = p - drop
    # Java strips trailing zeros down to the preferred scale for an inexact root as well.
    q, result_scale = _strip_to_scale(q, result_scale, preferred)
    return _build(q, result_scale)


def round12(value: Decimal) -> Decimal:
    _require(value, "value")
    return _rescale(value, SHARE_SCALE, HALF_UP)


def round2(value: Decimal) -> Decimal:
    _require(value, "value")
    return _rescale(value, 2, HALF_UP)


def mean(values: Sequence[Decimal], scale: int) -> Decimal | None:
    if not values:
        return None
    total = ZERO
    for x in values:
        total = _EXACT.add(total, x)
    return _divide(total, Decimal(len(values)), scale, HALF_UP)


def percent_points(value: Decimal) -> Decimal:
    _require(value, "ratio")
    return _rescale(_EXACT.multiply(value, Decimal(100)), 0, HALF_UP)


def ceil_count(value: Decimal, n: int) -> int:
    _require(value, "ratio")
    result = int(_rescale(_EXACT.multiply(value, Decimal(n)), 0, CEILING))
    if not INT_MIN <= result <= INT_MAX:
        raise MoneyArithmeticError("count out of int range")
    return result

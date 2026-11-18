"""Price parity with the Java-owned vectors: same months per seed, same won per return.

Everything here is synthetic (`inputs-v1.csv`). The Java side (`PriceParityVectorsTest`) writes
`p0-prices-v1.csv` with MoneyMath.priceAt; this side picks the months with the port of the bootstrap
and multiplies with an exact decimal, so two independent implementations meet on every won.
"""

import decimal
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

import numpy as np
from parity_prices import (
    BASE_DATE,
    EXACT,
    INPUTS_FILE,
    P0_FILE,
    exact_factor,
    exact_value,
    load_inputs,
    paths_of,
    read_price_rows,
    won,
)

from antlab_engine.bootstrap.simple import generate_months
from antlab_engine.projection import ReturnPanel, ReturnRow
from antlab_engine.projection.evaluate import evaluate

INPUTS = load_inputs()
ROWS = read_price_rows(P0_FILE)
PATHS = paths_of(ROWS)


def test_input_file_has_the_designed_shape():
    assert INPUTS_FILE.is_file()
    assert len(INPUTS.months) >= 30
    assert date(2010, 4, 1) not in INPUTS.months
    assert {m.year for m in INPUTS.months} == set(range(2007, 2015))
    assert INPUTS.assets == ["KR:A001", "KR:A002", "KR:C001", "KR:C002", "US:B001", "US:B002"]
    assert {INPUTS.currency[a] for a in INPUTS.assets} == {"KRW", "USD"}
    for key, value in [*INPUTS.local.items(), *INPUTS.fx.items()]:
        assert value.as_tuple().exponent == -8, key
    assert all(v == 0 for (a, _), v in INPUTS.fx.items() if INPUTS.currency[a] == "KRW")
    assert Decimal("-0.99") in INPUTS.local.values()
    assert Decimal(0) in INPUTS.local.values()
    assert any(v == 0 for (a, _), v in INPUTS.fx.items() if INPUTS.currency[a] == "USD")


def test_python_port_picks_the_same_months_as_java():
    assert len(PATHS) == 40
    for (seed, length), expected in PATHS.items():
        actual = generate_months(INPUTS.months, BASE_DATE, length, seed)
        assert actual == expected, f"seed={seed} length={length}"


def test_exact_decimal_product_equals_every_java_price():
    assert len(ROWS) == 20 * 36 * 6
    for seed, length, turn, asset, month, price in ROWS:
        path = PATHS[(seed, length)]
        assert path[turn - 1] == month
        assert won(exact_value(INPUTS, asset, path[:turn])) == price, (seed, length, turn, asset)


def test_the_exact_arithmetic_never_needs_the_working_precision():
    # the Inexact trap of parity_prices.EXACT would have raised above; this records the headroom
    digits = 0
    for (_, length), path in PATHS.items():
        for asset in INPUTS.assets:
            digits = max(digits, len(exact_value(INPUTS, asset, path[:length]).as_tuple().digits))
    assert digits < 1500


def test_float64_multiple_of_the_projection_library_tracks_the_exact_factor():
    rows = [
        ReturnRow(asset, month, INPUTS.local[(asset, month)], INPUTS.fx[(asset, month)])
        for asset in INPUTS.assets
        for month in INPUTS.months
    ]
    panel = ReturnPanel.from_rows(INPUTS.months, INPUTS.assets, rows)
    row_of = {m: i for i, m in enumerate(panel.months)}
    worst = Decimal(0)
    for (_, length), path in PATHS.items():
        paths = np.array([[row_of[m] for m in path]], dtype=np.int32)
        for column, asset in enumerate(panel.assets):
            (values,) = evaluate(panel.factors, paths, [(((column, 1.0),), 0.0)])
            for turn in range(1, length + 1):
                exact = exact_factor(INPUTS, asset, path[:turn])
                error = abs(Decimal(float(values[0, turn])) - exact) / exact
                worst = max(worst, error)
    assert worst <= Decimal("1e-9")


def _mismatches(price_of) -> int:
    return sum(
        1
        for seed, length, turn, asset, _, price in ROWS
        if price_of(asset, PATHS[(seed, length)], turn) != price
    )


def test_half_even_rounding_would_break_the_vectors():
    def half_even(asset, path, turn):
        value = exact_value(INPUTS, asset, path[:turn])
        with decimal.localcontext(decimal.Context(prec=4000, rounding=ROUND_HALF_EVEN)) as ctx:
            return int(ctx.quantize(value, Decimal(1)))

    assert _mismatches(half_even) >= 20


def test_rounding_every_turn_would_break_the_vectors():
    def monthly(asset, path, turn):
        value = Decimal(INPUTS.initial[asset])
        for month in path[:turn]:
            step = exact_factor(INPUTS, asset, [month])
            value = Decimal(won(EXACT.multiply(value, step)))
        return int(value)

    assert _mismatches(monthly) >= 100


def test_dropping_the_currency_change_would_break_the_vectors():
    def no_fx(asset, path, turn):
        value = Decimal(INPUTS.initial[asset])
        for month in path[:turn]:
            value = EXACT.multiply(value, EXACT.add(Decimal(1), INPUTS.local[(asset, month)]))
        return won(value)

    assert _mismatches(no_fx) >= 100


def test_the_vectors_pass_exact_half_won_cases():
    halves = [
        r
        for r in ROWS
        if exact_value(INPUTS, r[3], PATHS[(r[0], r[1])][: r[2]]) % 1 == Decimal("0.5")
    ]
    assert len(halves) >= 20

"""The Java-written money vectors replayed against the Python money kernel.

`money-vectors-v1.jsonl` is written by `SimMoneyVectorsTest` (Java is the oracle) and read in place.
Every case must give the same value and the same decimal scale, or the same refusal class.
"""

import json
from collections import Counter
from decimal import Decimal
from pathlib import Path

import pytest

from antlab_engine.sim import money

VECTORS = Path(__file__).resolve().parents[2] / "src/test/resources/sim/money-vectors-v1.jsonl"

# Argument codes: d decimal string, i integer, s text, D decimals, I big integers (strings),
# F list of [local return, fx change] pairs.
SIGNATURES = {
    "initialPrice": (money.initial_price, "dd"),
    "priceAt": (money.price_at, "dF"),
    "feeRate": (money.fee_rate, "sddd"),
    "fee": (money.fee, "did"),
    "avgPrice": (money.avg_price, "idid"),
    "realizedPnl": (money.realized_pnl, "ddi"),
    "affordable": (money.affordable, "ddd"),
    "teamQty": (money.team_qty, "ddd"),
    "drawdown": (money.drawdown, "dd"),
    "ratio": (money.ratio, "dd"),
    "valuation": (money.valuation, "di"),
    "totalValue": (money.total_value, "dD"),
    "exactWon": (money.exact_won, "d"),
    "exactAvgPrice": (money.exact_avg_price, "d"),
    "exactStoredPrice": (money.exact_stored_price, "d"),
    "exactRate": (money.exact_rate, "d"),
    "exactProbability": (money.exact_probability, "d"),
    "fractionMean": (money.fraction_mean, "II"),
    "pad40Divide": (money.pad40_divide, "dd"),
    "sampleStddev8": (money.sample_stddev8, "D"),
    "sampleStddev": (money.sample_stddev, "D"),
    "pgDivide": (money.pg_divide, "dd"),
    "pgAvg": (money.pg_avg, "D"),
    "round8": (money.round8, "d"),
    "shareDown": (money.share_down, "dd"),
    "scaleByMultiple": (money.scale_by_multiple, "dd"),
    "scoreDivide": (money.score_divide, "dd"),
    "divideSignificant": (money.divide_significant, "dd"),
    "sqrtSignificant": (money.sqrt_significant, "d"),
    "round12": (money.round12, "d"),
    "round2": (money.round2, "d"),
    "mean": (money.mean, "Di"),
    "percentPoints": (money.percent_points, "d"),
    "ceilCount": (money.ceil_count, "di"),
}


def decode(code: str, raw):
    if raw is None:
        return None
    match code:
        case "d":
            return Decimal(raw)
        case "i" | "s":
            return raw
        case "D":
            return [Decimal(x) for x in raw]
        case "I":
            return [int(x) for x in raw]
        case "F":
            return [(Decimal(a), Decimal(b)) for a, b in raw]
    raise AssertionError(code)


def load():
    return [json.loads(line) for line in VECTORS.read_text(encoding="utf-8").splitlines()]


CASES = load()


def run(case):
    function, codes = SIGNATURES[case["fn"]]
    args = [decode(code, raw) for code, raw in zip(codes, case["args"], strict=True)]
    try:
        return function(*args), None
    except money.MoneyArithmeticError:
        return None, "ARITHMETIC"
    except money.MoneyError:
        return None, "INVALID"


def same(got, expected) -> bool:
    if expected is None:
        return got is None
    if isinstance(expected, bool):
        return isinstance(got, bool) and got == expected
    if isinstance(expected, int):
        return isinstance(got, int) and not isinstance(got, bool) and got == expected
    return isinstance(got, Decimal) and got.as_tuple() == Decimal(expected).as_tuple()


def test_the_file_covers_every_function_and_enough_cases():
    counts = Counter(case["fn"] for case in CASES)
    assert len(CASES) >= 2000
    assert set(counts) == set(SIGNATURES)
    assert all(count >= 30 for count in counts.values())
    assert {c["error"] for c in CASES if "error" in c} == {"INVALID", "ARITHMETIC"}


def test_every_vector_matches_java_in_value_and_scale():
    failures = []
    for number, case in enumerate(CASES, start=1):
        got, error = run(case)
        expected_error = case.get("error")
        if error != expected_error or (error is None and not same(got, case["result"])):
            failures.append(
                f"line {number} {case['fn']}: {error or got!r} != "
                f"{expected_error or case['result']!r}"
            )
    assert not failures, "\n".join(failures[:20]) + f"\n{len(failures)} of {len(CASES)} differ"


@pytest.mark.parametrize("fn", sorted(SIGNATURES))
def test_each_function_matches(fn):
    for case in (c for c in CASES if c["fn"] == fn):
        got, error = run(case)
        assert error == case.get("error"), case
        if error is None:
            assert same(got, case["result"]), case


def test_the_money_package_has_no_float_and_no_io():
    package = Path(money.__file__).parent
    for source in package.glob("*.py"):
        text = source.read_text(encoding="utf-8")
        assert "float" not in text, source.name
        for banned in ("psycopg", "httpx", "requests", "import random", "import numpy"):
            assert banned not in text, (source.name, banned)


@pytest.mark.parametrize(
    ("call", "expected"),
    [
        # Hand values of Java BigDecimal.divide(…, scale, HALF_UP) with a negative-scale numerator.
        (lambda: money.ratio(Decimal("1E+2"), Decimal("3")), "33.33333333"),
        (
            lambda: money.pg_divide(Decimal("1E+2"), Decimal("7")),
            "14.2857142857142857",
        ),
        (lambda: money.score_divide(Decimal("1E+2"), Decimal("3")), "33." + "3" * 30),
        (lambda: money.divide_significant(Decimal(1), Decimal("0.01")), "1E+2"),
    ],
)
def test_negative_scale_inputs_stay_exact_integers(call, expected):
    assert call().as_tuple() == Decimal(expected).as_tuple()


@pytest.mark.parametrize("bad", [Decimal("NaN"), Decimal("Infinity"), Decimal("-Infinity")])
def test_non_finite_numbers_are_refused_like_any_invalid_input(bad):
    with pytest.raises(money.MoneyError):
        money.fee(bad, 1, Decimal("0.00015000"))
    with pytest.raises(money.MoneyError):
        money.price_at(Decimal(1000), [(bad, Decimal(0))])
    with pytest.raises(money.MoneyError):
        money.ratio(bad, Decimal(1))
    with pytest.raises(money.MoneyError):
        money.pg_avg([Decimal(1), bad])

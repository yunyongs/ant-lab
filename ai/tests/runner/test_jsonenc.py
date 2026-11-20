from decimal import Decimal

import pytest

from antlab_ai.runner import jsonenc
from antlab_ai.runner.jsonenc import RunnerBodyError


def test_a_decimal_keeps_every_digit_under_the_allowed_key():
    big = Decimal("12345678901234567890.123456789012345678")
    text = jsonenc.dumps(
        {"id": 1, "decision": {"w": [big, Decimal("1.50")]}}, decimals_under="decision"
    )
    assert text == '{"id":1,"decision":{"w":[12345678901234567890.123456789012345678,1.50]}}'


def test_a_decimal_outside_the_allowed_key_is_refused():
    for body in (
        {"x": Decimal("1")},
        {"calls": [{"c": Decimal("1")}]},
        {"decision": 1, "y": Decimal(1)},
    ):
        with pytest.raises(RunnerBodyError):
            jsonenc.dumps(body, decimals_under="decision")
    with pytest.raises(RunnerBodyError):
        jsonenc.dumps({"decision": Decimal("1")})


@pytest.mark.parametrize(
    "bad", [Decimal("NaN"), Decimal("sNaN"), Decimal("Infinity"), Decimal("-Infinity")]
)
def test_non_finite_numbers_are_refused(bad):
    with pytest.raises(RunnerBodyError):
        jsonenc.dumps({"decision": {"a": [bad]}}, decimals_under="decision")


@pytest.mark.parametrize("bad", [1.5, float("nan"), float("inf")])
def test_a_binary_float_is_refused_even_under_the_allowed_key(bad):
    with pytest.raises(RunnerBodyError):
        jsonenc.dumps({"decision": {"a": bad}}, decimals_under="decision")


def test_other_types_and_non_text_keys_are_refused_and_plain_values_pass():
    for body in ({"x": object()}, {1: "a"}, {"x": {1, 2}}):
        with pytest.raises(RunnerBodyError):
            jsonenc.dumps(body)
    assert jsonenc.dumps({"a": "한글\x00", "b": [True, None, 3, False], "c": ()}) == (
        '{"a":"한글\\u0000","b":[true,null,3,false],"c":[]}'
    )

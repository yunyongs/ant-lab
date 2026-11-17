from decimal import Decimal

import pytest

from antlab_ai.detection.plan import DEFAULT_PROXIES, build_units, render_unit
from antlab_ai.detection.render import format_percent, krw_return, render_window


def pct(market: str, local: str, fx: str) -> str:
    return format_percent(krw_return(market, Decimal(local), Decimal(fx)))


def test_us_return_is_compounded_with_fx():
    assert pct("US", "0.10000000", "0.05000000") == "15.50%"


def test_kr_return_ignores_fx():
    assert pct("KR", "0.10000000", "0.05000000") == "10.00%"


@pytest.mark.parametrize(
    ("local", "expected_ratio", "expected_text"),
    [
        ("0.12345000", "0.1235", "12.35%"),
        ("-0.12345000", "-0.1235", "-12.35%"),
        ("0.12344999", "0.1234", "12.34%"),
        ("-0.12344999", "-0.1234", "-12.34%"),
        ("0.00005000", "0.0001", "0.01%"),
        ("-0.00005000", "-0.0001", "-0.01%"),
    ],
)
def test_half_up_at_four_decimals_rounds_ties_away_from_zero(local, expected_ratio, expected_text):
    ratio = krw_return("KR", Decimal(local), Decimal("0"))
    assert ratio == Decimal(expected_ratio)
    assert format_percent(ratio) == expected_text


def test_us_tie_after_compounding_rounds_half_up():
    # (1 + 0.1) * (1 + 0.02) - 1 = 0.122 exactly; the second case lands just above a tie.
    assert krw_return("US", Decimal("0.10000000"), Decimal("0.02000000")) == Decimal("0.1220")
    assert krw_return("US", Decimal("0.00004500"), Decimal("0.00000500")) == Decimal("0.0001")


@pytest.mark.parametrize("local", ["0.00000000", "-0.00000000", "0.00004999", "-0.00004999"])
def test_zero_never_renders_a_minus_sign(local):
    assert pct("KR", local, "0.00000000") == "0.00%"


def test_rendered_window_is_relative_and_ordered(sample):
    system, user = render_unit(sample, build_units(sample)[0])
    assert "Window length: 1 month(s)" in user
    rows = [line.split(":")[0] for line in user.splitlines() if line[:3] in ("KR ", "US ")]
    assert rows == [
        "KR 069500 Synthetic KR Proxy",
        "KR T0001 Synthetic Alpha",
        "US SPY Synthetic US Proxy",
        "US T0002 Synthetic Beta",
    ]
    assert "KR 069500, US SPY" in user
    assert "{{" not in system + user


def test_window_cells_are_oldest_first(sample):
    _, user = render_window(sample, 0, 3, DEFAULT_PROXIES)
    row = next(line for line in user.splitlines() if line.startswith("KR 069500 "))
    values = row.split(": ", 1)[1].split(", ")
    expected = [
        format_percent(krw_return("KR", v, sample.fx[i]))
        for i, v in enumerate(sample.returns[("KR", "069500")][:3])
    ]
    assert values == expected

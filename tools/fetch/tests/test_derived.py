from decimal import Decimal

import pytest

from derived import cash_accrual, etf_proxy_return, level_return, mix_50_50, mix_50_50_fx, mix_weighted, par_bond_return, to_krw

D = Decimal


def _price(coupon: float, y: float, years: int) -> float:
    """Independent check: discount every coupon and the principal one by one."""
    n, j, c = 2 * years, y / 2, coupon / 2
    return sum(c / (1 + j) ** k for k in range(1, n + 1)) + 1 / (1 + j) ** n


def test_unchanged_yield_earns_the_carry_only():
    assert par_bond_return(D("0.05"), D("0.05"), 10).quantize(D("1e-12")) == (D("0.05") / 12).quantize(D("1e-12"))


@pytest.mark.parametrize("y0,y1,years", [("0.05", "0.06", 10), ("0.05", "0.04", 3), ("0.03", "0.0325", 20), ("0.10", "0.15", 3), ("0.02", "0.01", 2)])
def test_matches_a_cash_flow_by_cash_flow_repricing(y0, y1, years):
    expected = float(D(y0)) / 12 + _price(float(y0), float(y1), years) - 1
    assert float(par_bond_return(D(y0), D(y1), years)) == pytest.approx(expected, abs=1e-12)


def test_golden_value_for_the_readme():
    # 10-year bond, yield 5.00 % -> 6.00 %: carry +0.4167 %, price effect about -7.44 %, month return -7.02 %
    r = par_bond_return(D("0.05"), D("0.06"), 10)
    assert r.quantize(D("1e-8")) == D("-0.07022071")


def test_rising_yields_lose_and_falling_yields_gain_and_longer_moves_more():
    up, down = par_bond_return(D("0.04"), D("0.05"), 10), par_bond_return(D("0.04"), D("0.03"), 10)
    assert up < 0 < down
    assert abs(par_bond_return(D("0.04"), D("0.05"), 20)) > abs(up) > abs(par_bond_return(D("0.04"), D("0.05"), 2))


def test_zero_yield_has_no_division_by_zero():
    assert par_bond_return(D("0"), D("0"), 5) == 0


def test_rejects_nonsense():
    with pytest.raises(ValueError):
        par_bond_return(D("0.05"), D("0.05"), 0)
    with pytest.raises(ValueError):
        par_bond_return(D("-1"), D("0.05"), 3)


def test_cash_and_mix_and_currency_and_fee():
    assert cash_accrual(D("0.036")) == D("0.003")
    assert mix_50_50(D("0.02"), D("-0.01")) == D("0.005")
    assert to_krw(D("0.01"), D("0.02")) == D("0.0302")  # (1.01)(1.02) - 1: applied once, not added
    assert to_krw(D("0"), D("0")) == 0
    assert etf_proxy_return(D("0.01"), D("0.0012")) == D("0.0099")
    assert level_return(D("100"), D("101")) == D("0.01")
    with pytest.raises(ValueError):
        level_return(D("0"), D("1"))


def test_fx_mix_converts_the_usd_leg_only():
    # 0.5 * ((1.02)(1.10) - 1) + 0.5 * 0.002 = 0.5 * 0.122 + 0.001
    assert mix_50_50_fx(D("0.02"), D("0.002"), D("0.10")) == D("0.062")
    assert mix_50_50_fx(D("0.02"), D("0.002"), D("0")) == mix_50_50(D("0.02"), D("0.002"))
    # converting the whole mix would also scale the won leg: (1.011)(1.1) - 1 = 0.1121
    assert mix_50_50_fx(D("0.02"), D("0.002"), D("0.10")) != to_krw(mix_50_50(D("0.02"), D("0.002")), D("0.10"))


def test_weighted_mix():
    assert mix_weighted(D("0.10"), D("0.00"), 70) == D("0.07")
    assert mix_weighted(D("0.02"), D("0.02"), 50) == mix_50_50(D("0.02"), D("0.02"))
    for w in (0, 100, -5):
        with pytest.raises(ValueError):
            mix_weighted(D("0.1"), D("0.1"), w)

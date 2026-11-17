import re
from datetime import date
from decimal import Decimal

import numpy as np
import pytest
from synthetic_months import month_range
from synthetic_panel import make_panel

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection import Portfolio, ReturnPanel, ReturnRow

JAN = date(2010, 1, 1)
FEB = date(2010, 2, 1)
MONTH_TEXT = re.compile(r"\d{4}-\d{2}")


def test_factor_is_the_exact_product_converted_once():
    row = ReturnRow("US:AAA", JAN, Decimal("0.1"), Decimal("0.05"))
    assert row.factor() == float(Decimal("1.155"))
    panel = ReturnPanel.from_rows([JAN], ["US:AAA"], [row])
    assert panel.factors[0, 0] == float(Decimal("1.155"))


def test_kr_rows_with_zero_fx_use_the_local_return_only():
    panel = make_panel([JAN], {"KR:005930": {JAN: "-0.25"}})
    assert panel.factors[0, 0] == 0.75


def test_total_loss_is_a_valid_zero_factor():
    assert ReturnRow("KR:A", JAN, Decimal("-1"), Decimal(0)).factor() == 0.0


def test_missing_cells_are_nan_and_not_present():
    panel = make_panel([JAN, FEB], {"KR:A": {JAN: "0.1", FEB: "0.1"}, "KR:B": {JAN: "0.2"}})
    assert panel.assets == ("KR:A", "KR:B")
    assert panel.present.tolist() == [[True, True], [True, False]]
    assert np.isnan(panel.factors[1, 1])
    assert panel.rows_with(["KR:A", "KR:B"]).tolist() == [0]
    assert panel.rows_with(["KR:A"]).tolist() == [0, 1]
    assert panel.rows_with([]).tolist() == [0, 1]


def test_arrays_are_read_only():
    panel = make_panel([JAN], {"KR:A": {JAN: "0.1"}})
    for array in (panel.factors, panel.present):
        with pytest.raises(ValueError, match="read-only"):
            array[0, 0] = 1


def test_repr_has_no_months():
    panel = make_panel(month_range(JAN, date(2012, 1, 1)), {"KR:A": {JAN: "0.1"}})
    assert not MONTH_TEXT.search(repr(panel))
    assert "datetime.date" not in repr(panel)


def test_row_repr_has_no_date_and_no_return():
    row = ReturnRow("US:AAA", JAN, Decimal("0.123456"), Decimal("-0.0456"))
    text = repr(row)
    assert "US:AAA" in text
    for secret in ("datetime.date", "2010", "0.123456", "0.0456", "Decimal"):
        assert secret not in text
    assert not MONTH_TEXT.search(text) and not MONTH_TEXT.search(str(row))


@pytest.mark.parametrize(
    "build",
    [
        lambda: ReturnRow("KR:A", JAN, Decimal("-1.00000001"), Decimal(0)),
        lambda: ReturnRow("KR:A", JAN, Decimal(0), Decimal("-1")),
        lambda: ReturnRow("KR:A", JAN, Decimal("NaN"), Decimal(0)),
        lambda: ReturnRow("KR:A", JAN, Decimal(0), Decimal("Infinity")),
        lambda: ReturnRow("KR:A", JAN, Decimal("1E+400"), Decimal(0)),
        lambda: ReturnRow("KR:A", JAN, Decimal("1E+199"), Decimal("1E+199")),
        lambda: ReturnRow("KR:A", JAN, Decimal("1E+999999"), Decimal(0)),
        lambda: ReturnRow("KR:A", JAN, Decimal("1E-201"), Decimal(0)),
        lambda: ReturnRow("KR:A", JAN, Decimal("sNaN"), Decimal(0)),
        lambda: ReturnRow("KR:A", JAN, 0.1, Decimal(0)),
        lambda: ReturnRow("KR:A", date(2010, 1, 2), Decimal(0), Decimal(0)),
        lambda: ReturnRow("kr:a", JAN, Decimal(0), Decimal(0)),
        lambda: ReturnRow("JP:A", JAN, Decimal(0), Decimal(0)),
        lambda: ReturnPanel.from_rows(
            [JAN, JAN], ["KR:A"], [ReturnRow("KR:A", JAN, Decimal(0), Decimal(0))]
        ),
        lambda: ReturnPanel.from_rows([JAN], ["KR:A", "KR:A"], []),
        lambda: ReturnPanel.from_rows([date(2010, 1, 5)], ["KR:A"], []),
        lambda: ReturnPanel.from_rows(
            [JAN], ["KR:A"], [ReturnRow("KR:A", FEB, Decimal(0), Decimal(0))]
        ),
        lambda: ReturnPanel.from_rows(
            [JAN], ["KR:A"], [ReturnRow("KR:B", JAN, Decimal(0), Decimal(0))]
        ),
        lambda: ReturnPanel.from_rows(
            [JAN], ["KR:A"], [ReturnRow("KR:A", JAN, Decimal(0), Decimal(0))] * 2
        ),
    ],
)
def test_invalid_panel_input_is_rejected_without_months(build):
    with pytest.raises(InvalidArgument) as caught:
        build()
    assert not MONTH_TEXT.search(str(caught.value))


def test_portfolio_weights_are_sorted_and_zero_weights_are_inactive():
    weights = {"US:B": Decimal("0.5"), "KR:A": Decimal("0.5"), "KR:C": Decimal(0)}
    p = Portfolio("TEAM", weights, Decimal(0))
    assert list(p.weights) == ["KR:A", "KR:C", "US:B"]
    assert [a for a, _ in p.active] == ["KR:A", "US:B"]
    with pytest.raises(TypeError):
        p.weights["KR:A"] = Decimal(1)


def test_the_exactness_limit_also_keeps_every_factor_inside_a_float():
    big = Decimal("1E+199")
    assert ReturnRow("KR:A", JAN, big, Decimal(0)).factor() == 1e199
    with pytest.raises(InvalidArgument):
        ReturnRow("KR:A", JAN, big, big)


def raw(factors, present, months=(JAN,), assets=("KR:A",)):
    return ReturnPanel(months, assets, factors, present)


@pytest.mark.parametrize(
    "build",
    [
        lambda: raw(np.array([[-2.0]]), np.array([[True]])),
        lambda: raw(np.array([[np.nan]]), np.array([[True]])),
        lambda: raw(np.array([[np.inf]]), np.array([[True]])),
        lambda: raw(np.array([[1.0]]), np.array([[False]])),
        lambda: raw(np.array([[1.0, 1.0]]), np.array([[True, True]])),
        lambda: raw(np.array([[1.0]], dtype=np.float32), np.array([[True]])),
        lambda: raw(np.array([[1.0]]), np.array([[1]])),
        lambda: raw(np.array([1.0]), np.array([True])),
        lambda: raw([[1.0]], np.array([[True]])),
        lambda: raw(np.array([[1.0]]), np.array([[True]]), months=(date(2010, 1, 2),)),
        lambda: raw(np.zeros((2, 1)), np.ones((2, 1), bool), months=(FEB, JAN)),
        lambda: raw(np.zeros((2, 1)), np.ones((2, 1), bool), months=(JAN, JAN)),
        lambda: raw(np.zeros((1, 2)), np.ones((1, 2), bool), assets=("KR:B", "KR:A")),
        lambda: raw(np.array([[1.0]]), np.array([[True]]), assets=("kr:a",)),
    ],
)
def test_the_constructor_checks_what_from_rows_checks(build):
    with pytest.raises(InvalidArgument) as caught:
        build()
    assert not MONTH_TEXT.search(str(caught.value))


def test_the_constructor_copies_and_freezes_its_arrays():
    factors, present = np.array([[1.5]]), np.array([[True]])
    panel = raw(factors, present)
    factors[0, 0] = -3.0
    assert panel.factors[0, 0] == 1.5
    with pytest.raises(ValueError, match="read-only"):
        panel.factors[0, 0] = 2.0


def test_panel_size_is_capped_before_any_allocation(monkeypatch):
    from antlab_engine.projection.panel import MAX_PANEL_ASSETS, MAX_PANEL_MONTHS

    def no_allocation(*_args, **_kwargs):
        raise AssertionError("allocated before the size check")

    monkeypatch.setattr(np, "full", no_allocation)
    monkeypatch.setattr(np, "zeros", no_allocation)

    months = month_range(date(1800, 1, 1), date(1800, 1, 1))
    many_months = [date(1900 + i // 12, i % 12 + 1, 1) for i in range(MAX_PANEL_MONTHS + 1)]
    with pytest.raises(InvalidArgument):
        ReturnPanel.from_rows(many_months, ["KR:A"], [])
    many_assets = [f"KR:T{i:04d}" for i in range(MAX_PANEL_ASSETS + 1)]
    with pytest.raises(InvalidArgument):
        ReturnPanel.from_rows(months, many_assets, [])
    shape = (MAX_PANEL_MONTHS + 1, 1)
    with pytest.raises(InvalidArgument):
        ReturnPanel(tuple(many_months), ("KR:A",), np.ones(shape), np.ones(shape, bool))
    monkeypatch.undo()
    fits = [f"KR:T{i:04d}" for i in range(MAX_PANEL_ASSETS)]
    assert ReturnPanel.from_rows(months, fits, []).assets == tuple(fits)


def test_rows_with_rejects_an_unknown_asset():
    panel = make_panel([JAN], {"KR:A": {JAN: "0.1"}})
    with pytest.raises(InvalidArgument):
        panel.rows_with(["KR:ZZZ"])

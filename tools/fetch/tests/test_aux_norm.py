import csv
from pathlib import Path

import pytest

import aux_norm
from proxy_sources import AuxError
from tests.test_proxy_sources import _xlsx

MAPDIR = Path(__file__).resolve().parent / "fixtures" / "build_full" / "map"  # its proxy_series.csv names the WB columns Gold and Copper


def _book(tmp_path, **kw):
    (tmp_path / "aux" / "wb").mkdir(parents=True, exist_ok=True)
    return _xlsx(tmp_path / "aux" / "wb" / aux_norm.WORKBOOK, **kw)


def test_flatten_writes_one_row_per_month_with_a_value(tmp_path):
    _book(tmp_path, rows=(("1996M01", "399.00000000000006", "2616"), ("1996M02", "405", "…")))
    out = aux_norm.flatten_wb(tmp_path, MAPDIR)
    with out.open(encoding="utf-8", newline="") as h:
        rows = list(csv.reader(h))
    assert rows == [["series", "month", "value"], ["Gold", "1996-01", "399.0000"], ["Gold", "1996-02", "405.0000"], ["Copper", "1996-01", "2616.0000"]]


def test_flatten_refuses_a_missing_workbook_and_an_unknown_column(tmp_path):
    with pytest.raises(AuxError, match="missing workbook"):
        aux_norm.flatten_wb(tmp_path, MAPDIR)
    _book(tmp_path, header_gold="Silver")
    with pytest.raises(AuxError):
        aux_norm.flatten_wb(tmp_path, MAPDIR)
    assert not (tmp_path / "aux" / "wb" / aux_norm.FLAT).exists()


def test_cli_returns_1_without_a_workbook(tmp_path, capsys):
    assert aux_norm.main(["wb", "--raw-dir", str(tmp_path), "--full", str(MAPDIR)]) == 1
    assert "missing workbook" in capsys.readouterr().err

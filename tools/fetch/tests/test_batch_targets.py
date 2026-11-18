from datetime import date

import pytest

import batch
from batch import drip_range, estimate_daily_rows, load_fetch_targets, pages_for
from kis import KisError
from pipeline import check_edges
from tests.batch_helpers import Env, kr, us, write_targets
from tests.fake_kis import clock_at


def row(m="KR", t="005930", excd="", basis="ADJUSTED", kf="2001-03", yf="", cut="", o="1"):
    return [m, t, excd, basis, kf, yf, cut, o]


def test_loader_reads_valid_file(tmp_path):
    p = write_targets(tmp_path / "t.csv", [row(), row("US", "SPY", "AMS", "ADJUSTED", "2007-08", "SPY", "2007-12", "2"),
                                          row("KR", "069500", "", "RAW", "", "069500.KS", "", "3")])
    ts = load_fetch_targets(p)
    assert [t.key for t in ts] == ["KR:005930", "US:SPY", "KR:069500"]
    assert ts[0].kis_from == date(2001, 3, 1) and ts[1].yf_canonical_to == date(2007, 12, 1)
    assert ts[2].kis_from is None and not ts[2].adjusted


@pytest.mark.parametrize("rows", [
    [row(o="2"), row(t="000660", o="2")],                       # order not increasing
    [row(o="2"), row(t="000660", o="1")],                       # order goes back
    [row(), row()],                                             # duplicate asset
    [row("US", "SPY", "", "ADJUSTED", "2007-08", "", "", "1")], # US without exchange
    [row("US", "SPY", "AMS", "RAW", "2007-08", "", "", "1")],   # US raw
    [row(excd="NAS")],                                          # KR with exchange
    [row(kf="2001-13")], [row(kf="2001")], [row(cut="2007-12")],# bad month / KR cut
    [row(o="x")], [row(basis="adj")], [row(t="a/b")], [row(m="JP")],
])
def test_loader_refuses(tmp_path, rows):
    with pytest.raises(ValueError):
        load_fetch_targets(write_targets(tmp_path / "t.csv", rows))


def test_loader_refuses_unknown_column_or_order(tmp_path):
    p = tmp_path / "t.csv"
    p.write_text("market,ticker,kis_excd,kis_basis,kis_from,yf_symbol,yf_canonical_to,order_no,extra\n", encoding="utf-8")
    with pytest.raises(ValueError):
        load_fetch_targets(p)
    p.write_text("ticker,market,kis_excd,kis_basis,kis_from,yf_symbol,yf_canonical_to,order_no\n", encoding="utf-8")
    with pytest.raises(ValueError):
        load_fetch_targets(p)


def test_drip_range_rules():
    assert drip_range(kr(kis_from="1995-12")) == (date(1995, 12, 1), False)
    assert drip_range(kr(kis_from="2010-03")) == (date(2010, 3, 1), True)
    assert drip_range(us(kis_from="2007-08", cut="2007-12")) == (date(2007, 12, 1), False)
    assert drip_range(us("TLT", kis_from="2015-01", cut="2007-12")) == (date(2015, 1, 1), True)


def test_estimate_is_an_upper_bound_for_a_full_kr_stock():
    ft = kr(kis_from="1995-12")
    pages = pages_for(estimate_daily_rows(ft, date(1995, 12, 1), date(2026, 1, 2)))
    assert 77 <= pages <= 90


def _rows(*ds):
    return [{"date": d} for d in ds]


def test_listing_month_edge_passes_inside_first_month_and_fails_after(tmp_path):
    tg = kr().as_target()
    start, end = date(2010, 3, 1), date(2010, 4, 30)
    rows = _rows("2010-03-15", "2010-04-30")
    check_edges(rows, tg, start, end, listing_month=True)           # first row mid-month: listed that month
    with pytest.raises(KisError):
        check_edges(rows, tg, start, end)                           # strict rule keeps failing (P0-08 behaviour)
    with pytest.raises(KisError):
        check_edges(_rows("2010-04-01", "2010-04-30"), tg, start, end, listing_month=True)  # first row in a later month


def test_drip_listing_aware_end_to_end(tmp_path):
    env = Env(tmp_path, listings={"111111": date(2010, 3, 15)})
    r = env.run("drip", [kr("111111", kis_from="2010-03")])
    assert r.stop_reason == "DONE" and r.assets_done == 1
    env2 = Env(tmp_path / "b", listings={"111111": date(2010, 3, 15)})
    r2 = env2.run("drip", [kr("111111", kis_from="2010-02")])    # sweep said Feb but the first daily row is in March
    assert r2.failed == ["KR:111111:DATA_CHECK"]


def test_targets_file_drives_basis_and_exchange(tmp_path):
    env = Env(tmp_path)
    ts = [kr("069500", 1, "2020-01", basis="RAW"), kr("005930", 2, "2020-01"), us("SPY", 3, "2007-08", "2007-12", "AMS")]
    env.run("drip", ts)
    kr_sent = [q for q in env.fake.requested if "FID_INPUT_ISCD" in q]
    assert {q["FID_ORG_ADJ_PRC"] for q in kr_sent if q["FID_INPUT_ISCD"] == "069500" and q["FID_INPUT_DATE_1"] == "20200101"} == {"1"}
    assert {q["FID_ORG_ADJ_PRC"] for q in kr_sent if q["FID_INPUT_ISCD"] == "005930" and q["FID_INPUT_DATE_1"] == "20200101"} == {"0"}
    us_sent = [q for q in env.fake.requested if "SYMB" in q]
    assert {q["EXCD"] for q in us_sent} == {"AMS"}
    assert {q["MODP"] for q in us_sent if q["BYMD"] != "20260102"} == {"1"}
    # the US daily series starts at the cut month, not at the KIS floor
    assert min(q["BYMD"] for q in us_sent if q["BYMD"] != "20260102") >= "20071201"
